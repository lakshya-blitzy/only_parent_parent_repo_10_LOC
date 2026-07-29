#!/usr/bin/env bash
# =============================================================================
# verify-health.sh - the single shared /health endpoint-contract assertion.
#
# PURPOSE
#   This is acceptance gate G9. It starts one application at a time in --serve
#   mode, polls until the listener answers, asserts the complete frozen response
#   contract, asserts the unknown-path and wrong-method behaviours, and shuts the
#   server down on every exit path.
#
#   It is deliberately ONE file rather than three. A single shared assertion set
#   is what guarantees the Python, JavaScript and Java implementations cannot
#   drift apart: every language takes the identical code path through the
#   identical assertions, so a difference between them is a failure rather than a
#   difference between three test scripts nobody diffed. Never split it per
#   language, and never give one language its own assertion path.
#
#   Its second purpose is to make the CI gate runnable locally. The workflow in
#   .github/workflows/ci.yml invokes exactly the commands documented below, so a
#   developer running them by hand gets the same verdict CI gets. The CLI in
#   usage() is therefore a contract: changing it changes what the workflow must
#   call.
#
# USAGE
#   scripts/verify-health.sh [python|node|java|all ...] [--host H] [--port P]
#                            [--path P] [--timeout SEC] [-h|--help]
#
#   With no language selector every language is verified, because that is the
#   bare invocation README.md documents:  bash scripts/verify-health.sh
#
# THE FROZEN CONTRACT THIS SCRIPT ASSERTS
#   Route          the RESOLVED health path (default /health); the query string
#                  is stripped before matching and exactly ONE trailing slash is
#                  accepted, so <path>, <path>/ and <path>?x=1 all answer 200
#                  while <path>// answers 404
#   Method         GET only; any other method answers 405 with an Allow header
#   Success        200
#   Headers        Content-Type: application/json
#                  Cache-Control: no-cache, no-store, must-revalidate
#                  Content-Length: <the body's exact byte count>
#                  no Server banner, and no header outside the frozen set
#   Body           compact JSON, no whitespace, keys in exactly this order:
#                  name, version, timestamp, status
#   name           non-empty; equals the resolved application name
#   version        three-part dotted numeric; equals the resolved version
#   timestamp      YYYY-MM-DDTHH:MM:SSZ - asserted by FORMAT ONLY, NEVER by
#                  value, because it is a clock reading and a value comparison
#                  would make this gate fail for a reason unrelated to
#                  correctness
#   status         the exact literal UP
#   Unknown path   404 with the compact body {"error":"Not Found"}
#   Non-GET        405 with the compact body {"error":"Method Not Allowed"} and
#                  an Allow: GET header
#
#   Under the default configuration the body is exactly 108 bytes. That length is
#   asserted only when the name, version and path are all the defaults, because
#   an override legitimately changes it.
#
# EXIT CODES
#   0  every selected language satisfied every assertion
#   1  an assertion failed, or an operational error occurred (a runtime is
#      missing, a server did not become ready, no JSON reader is available).
#      The first failure exits immediately: no gate here is advisory, and no
#      failed assertion is ever retried.
#   2  a usage or configuration error - the arguments or the environment cannot
#      produce a meaningful run, and nothing was started.
#
# ENVIRONMENT VARIABLES HONOURED
#   Precedence for every value is: environment variable > app.config.properties >
#   built-in default. PORT outranks the per-language port variables.
#
#     APP_NAME      expected name field           default only_parent_parent_repo_10_LOC
#     APP_VERSION   expected version field        default 1.1.0
#     HEALTH_PATH   route to assert               default /health
#     APP_HOST      listener bind address         default 0.0.0.0
#     PORT          port for the ONE selected language (illegal with more than
#                   one, because three servers cannot share a port)
#     PYTHON_PORT   Python listener               default 8000
#     NODE_PORT     Node listener                 default 8001
#     JAVA_PORT     Java listener                 default 8002
#
#   The three defaults are deliberately distinct so that all three servers can be
#   verified on one CI runner without a bind collision. There is deliberately no
#   environment variable for the readiness budget: --timeout is a flag only, so
#   the environment surface stays exactly the list above.
#
# REQUIRED TOOLS
#   bash, curl, and one JSON reader: jq, python3 or node - whichever is present,
#   in that order. Nothing is installed by this script and no third-party package
#   is used anywhere: that is why the container HEALTHCHECK invokes each
#   application's own --probe mode instead of curl, and why this script refuses
#   to grow a dependency of its own.
#
#   Each language additionally needs its own runtime (python3 or python, node,
#   java). A missing runtime is a hard failure with an actionable message, never
#   a silent skip: a gate that skips is a gate that proves nothing.
#
# THREE MEASURED TRAPS - DO NOT "SIMPLIFY" THESE AWAY
#   1. Every header assertion folds case. The Java server
#      (com.sun.net.httpserver) normalises response field names and emits
#      "Content-type" where Python and Node emit "Content-Type". RFC 9110 makes
#      field names case-insensitive, so this is a difference in bytes and not in
#      meaning, and it is not correctable from application code. A case-sensitive
#      assertion would pass for two implementations and fail for the third.
#   1b. Folding case lowercases header VALUES too, so "Allow: GET" becomes
#      "allow: get". The Allow assertion matches "get", never "GET".
#   2. NEVER use curl -I. The -I option issues a HEAD request, which this
#      GET-only endpoint correctly answers with 405 (measured). Headers are
#      captured with a GET that discards the body:
#          curl -sS -D "$hdr" -o "$body" -w '%{http_code}' "$url"
#      which takes the status, the headers and the body in one exchange.
#   3. A Date header is tolerated, and only a Date header. Measured with a
#      dedicated probe: com.sun.net.httpserver writes Date unconditionally - an
#      application-set sentinel value is overwritten and removing the field after
#      sendResponseHeaders still sends it - so the Java implementation cannot
#      suppress it, exactly as User.java documents. RFC 9110 section 6.6.1 says
#      an origin server SHOULD send Date, and its value discloses nothing about
#      the runtime. Rather than relax the check, the header assertion is made
#      STRONGER and uniform: the response's field-name SET must equal the frozen
#      set, with Date the single tolerated platform-inserted extra, and Date's
#      FORMAT is asserted when it is present. Set equality proves absence as well
#      as presence in one assertion, so a Server banner, an X-Powered-By, a
#      Keep-Alive advertising the idle timeout, an ETag or a Set-Cookie all fail -
#      which a pair of presence checks could never prove. Python suppresses both
#      Server and Date with send_response_only(), Node suppresses them with
#      res.sendDate = false, and each of their own unit suites asserts that
#      absence directly.
#
# WHY HEAD IS NOT ASSERTED
#   The mandatory non-GET assertion is POST. HEAD is answered 405 as well - that
#   is the documented, deliberate deviation from
#   draft-inadarei-api-health-check-06, and it is the reason curl -I must never be
#   used here - but com.sun.net.httpserver applies its own restrictions to
#   writing a body on a HEAD response, so asserting HEAD would risk this gate
#   failing for a runtime quirk rather than for a contract violation. Gates must
#   fail only for real reasons.
#
# STANDARDS
#   The asserted contract follows draft-inadarei-api-health-check-06: a JSON
#   body, a status field, a 2xx code for a passing status, no-store caching, and
#   UP as a standards-recognised alias of pass. Two deviations are deliberate and
#   are asserted as such: the media type is plain application/json rather than the
#   draft's health-specific type, and HEAD is refused.
#
# GUARANTEES THIS SCRIPT MAKES ABOUT ITSELF
#   * It is read-only with respect to the repository. It creates, modifies, moves
#     and deletes nothing inside the working tree, and every temporary file it
#     writes lives in a mktemp directory outside it, removed on every exit path.
#   * It passes --serve and nothing else to an application. It never passes
#     --probe, which exists for the container HEALTHCHECK, and it never invokes an
#     application in a way that could disturb its default-mode output.
#   * It leaves no process behind. The server is stopped from a trap that runs on
#     success, on failure, on an unhandled error and on an interrupt.
# =============================================================================

set -euo pipefail

# --------------------------------------------------------------------------- #
# Identity and location
#
# The repository root is derived from this script's own location and never from
# the caller's working directory, so `bash scripts/verify-health.sh` and
# `cd /tmp && bash /path/to/repo/scripts/verify-health.sh` behave identically -
# which is what makes the local run and the CI run the same run.
# --------------------------------------------------------------------------- #

# Derived with pure parameter expansion rather than with `basename`/`dirname`.
# Those two coreutils binaries would be dependencies the contract does not need,
# and a PATH that lacks them would make this gate fail before it ever reached an
# assertion - exactly the "fails for the wrong reason" outcome S6 forbids.
# `${var##*/}` is basename; `${var%/*}` is dirname, with the two degenerate
# cases (a bare filename with no slash, and a file directly under /) handled
# explicitly because parameter expansion alone gets both wrong.

# Kept in a function so that every top-level name in this script stays an
# UPPERCASE global: the intermediate directory value is a local, not a stray
# lowercase global. BASH_SOURCE[0] inside a function still names the file the
# function was defined in, which is this script.
script_directory() {
  local source="${BASH_SOURCE[0]}"
  local directory="${source%/*}"
  if [ "$directory" = "$source" ]; then
    directory="."                       # invoked as `verify-health.sh`, no slash
  elif [ -z "$directory" ]; then
    directory="/"                       # invoked as `/verify-health.sh`
  fi
  ( cd -- "$directory" && pwd )
}

SCRIPT_NAME="${BASH_SOURCE[0]##*/}"
SCRIPT_DIR="$(script_directory)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly SCRIPT_NAME SCRIPT_DIR REPO_ROOT

# The single cross-language source of truth. A missing file is not fatal: the
# built-in defaults below are the same values it carries, so the script still
# runs and still asserts a meaningful contract.
CONFIG_PATH="$REPO_ROOT/app.config.properties"
readonly CONFIG_PATH

# --------------------------------------------------------------------------- #
# Built-in defaults - the last resort in the precedence chain, and identical to
# the defaults compiled into app.py, index.js and User.java.
# --------------------------------------------------------------------------- #

readonly DEFAULT_APP_NAME="only_parent_parent_repo_10_LOC"
readonly DEFAULT_APP_VERSION="1.1.0"
readonly DEFAULT_HEALTH_PATH="/health"
readonly DEFAULT_APP_HOST="0.0.0.0"
readonly DEFAULT_PYTHON_PORT="8000"
readonly DEFAULT_NODE_PORT="8001"
readonly DEFAULT_JAVA_PORT="8002"

# --------------------------------------------------------------------------- #
# The frozen contract, expressed once so that all three languages are asserted
# against the same constants.
# --------------------------------------------------------------------------- #

# Comma-joined key list in DECLARATION order. The order is part of the contract,
# which is why the JSON reader must preserve insertion order and must never sort.
readonly EXPECTED_KEYS="name,version,timestamp,status"
readonly EXPECTED_STATUS="UP"
readonly EXPECTED_CONTENT_TYPE="application/json"
readonly REQUIRED_CACHE_DIRECTIVE="no-store"
readonly EXPECTED_NOT_FOUND_BODY='{"error":"Not Found"}'
readonly EXPECTED_METHOD_NOT_ALLOWED_BODY='{"error":"Method Not Allowed"}'
readonly EXPECTED_ALLOW_METHOD="get"          # lower case: see TRAP 1b
readonly REFERENCE_BODY_BYTES="108"

# A target that cannot collide with any sane health path. It is asserted to
# differ from the resolved path before it is used, so this check cannot silently
# become a second assertion of the happy path.
readonly UNKNOWN_PATH="/__no_such_path__"

readonly VERSION_PATTERN='^[0-9]+\.[0-9]+\.[0-9]+$'
readonly TIMESTAMP_PATTERN='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
# IMF-fixdate (RFC 9110 section 5.6.7) already folded to lower case, because the
# captured header block is lowercased before anything is matched against it.
readonly HTTP_DATE_PATTERN='^(mon|tue|wed|thu|fri|sat|sun), [0-9]{2} (jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec) [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} gmt$'

# --------------------------------------------------------------------------- #
# Operational constants
# --------------------------------------------------------------------------- #

readonly DEFAULT_TIMEOUT_SECONDS="10"
readonly READINESS_POLL_INTERVAL="0.25"       # 4 attempts per second
# Bounds a single readiness attempt. This is deliberately much shorter than
# CURL_MAX_TIME: a peer that completes the TCP handshake and then never replies
# - a port held by an unrelated process is the everyday case - blocks curl until
# its own timeout expires, so without a short cap here one attempt could swallow
# the entire readiness budget and --timeout would stop meaning what it says.
readonly READINESS_PROBE_MAX_TIME="2"
readonly SHUTDOWN_POLL_ATTEMPTS="8"           # 8 x 0.25s = ~2s before SIGKILL
readonly CURL_MAX_TIME="10"                   # bounds every exchange
readonly LOG_TAIL_LINES="20"
readonly EXIT_FAILURE="1"
readonly EXIT_USAGE="2"

# --------------------------------------------------------------------------- #
# Mutable state. Every one of these is set before it is read; `set -u` turns any
# mistake here into an immediate error rather than an empty string.
# --------------------------------------------------------------------------- #

SERVER_PID=""            # the one child this script manages at a time
SERVER_OUT=""            # its stdout, captured but never asserted (see below)
SERVER_ERR=""            # its stderr, where all three write their banners
READINESS_LOG=""         # curl's own diagnostics from the readiness loop
TMPDIR_RUN=""            # mktemp -d, outside the repository, removed on exit
JSON_READER=""           # jq | python3 | node
PYTHON_RUNTIME=""        # python3 | python

SELECTED_LANGUAGES=()    # in selection order, de-duplicated
MULTI_LANGUAGE="no"      # "yes" once more than one language is selected
LAST_BODY_BYTES=""       # byte count of the last body asserted, for the PASS line
OPT_HOST=""
OPT_PORT=""
OPT_PATH=""
OPT_TIMEOUT=""

RESOLVED_NAME=""
RESOLVED_VERSION=""
RESOLVED_PATH=""
RESOLVED_HOST=""
CONNECT_HOST=""
TIMEOUT_SECONDS=""

HTTP_CODE=""             # set by http_request
HTTP_CURL_RC="0"         # curl's own exit status, for precise diagnostics

# =============================================================================
# Reporting
# =============================================================================

# usage: the CLI contract. Printed to stdout for --help (an intentional request
# is not an error) and to stderr for a usage error.
usage() {
  cat <<USAGE
$SCRIPT_NAME - assert the /health endpoint contract (acceptance gate G9)

Usage:
  $SCRIPT_NAME [LANGUAGE ...] [OPTIONS]

Languages:
  python            verify app.py    (default port $DEFAULT_PYTHON_PORT, env PYTHON_PORT)
  node              verify index.js  (default port $DEFAULT_NODE_PORT, env NODE_PORT)
  java              verify User.java (default port $DEFAULT_JAVA_PORT, env JAVA_PORT)
  all               verify python, node and java - this is also the default when
                    no language is named, so a bare invocation verifies all three

Options:
  --host HOST       bind address for the server under test
                    (default: app.host, currently $DEFAULT_APP_HOST; a wildcard bind is
                    connected to on 127.0.0.1)
  --port PORT       port for the server under test. Legal only when EXACTLY ONE
                    language is selected: three servers cannot share one port
  --path PATH       health route to serve and to assert (default: health.path,
                    currently $DEFAULT_HEALTH_PATH). The value is exported to the server, so
                    the route asserted is the route served
  --timeout SEC     readiness budget per language, in seconds (default $DEFAULT_TIMEOUT_SECONDS).
                    This bounds how long the listener may take to answer; it
                    never retries a failed assertion
  -h, --help        print this help on stdout and exit 0

Exit codes:
  0                 every selected language satisfied every assertion
  1                 an assertion failed, or an operational error occurred
  2                 a usage or configuration error; nothing was started

Environment (precedence: environment > app.config.properties > built-in default):
  APP_NAME APP_VERSION HEALTH_PATH APP_HOST PORT PYTHON_PORT NODE_PORT JAVA_PORT
  PORT outranks the per-language port variables and is refused when more than one
  language is selected.

Examples:
  $SCRIPT_NAME                            # all three languages, default ports
  $SCRIPT_NAME python                     # one language
  $SCRIPT_NAME python node                # two languages, two distinct ports
  $SCRIPT_NAME java --port 18002          # one language, explicit port
  $SCRIPT_NAME --path /healthz            # serve and assert an alternative route
USAGE
}

# fail: report one precise assertion failure and stop. Every assertion has its
# own message naming what was observed and what was expected, because "something
# failed" is not a diagnosis.
#
# IMPORTANT: never call fail (or usage_error) from inside a command substitution.
# `exit` there would end only the subshell and the script would carry on with a
# failed assertion behind it. Helpers used inside $( ) return a status instead,
# and their caller does the failing.
fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit "$EXIT_FAILURE"
}

# usage_error: the arguments or the environment cannot produce a meaningful run.
# Distinguished from an assertion failure by exit code 2, and by the fact that
# nothing has been started yet.
usage_error() {
  printf 'FAIL: %s\n' "$*" >&2
  printf '\n' >&2
  usage >&2
  exit "$EXIT_USAGE"
}

# report: one line of progress on stdout. Deliberately terse and free of any
# environment dump - a health gate should not become a disclosure surface of its
# own.
report() {
  printf '%s\n' "$*"
}

# dump_server_log: the last few lines the server wrote to stderr, plus the last
# thing curl complained about. Used only on the failure paths, where the
# operator's next question is always "what did the server say?".
#
# All three applications write their startup and diagnostic lines to stderr and
# keep stdout for their legacy output, so stderr is where the answer is - and
# stderr output is NOT itself a failure signal.
dump_server_log() {
  local label="$1"
  if [ -n "$SERVER_ERR" ] && [ -s "$SERVER_ERR" ]; then
    printf -- '--- %s: last %s lines of server stderr ---\n' "$label" "$LOG_TAIL_LINES" >&2
    tail -n "$LOG_TAIL_LINES" -- "$SERVER_ERR" >&2
  fi
  if [ -n "$READINESS_LOG" ] && [ -s "$READINESS_LOG" ]; then
    printf -- '--- %s: last readiness probe error ---\n' "$label" >&2
    tail -n 2 -- "$READINESS_LOG" >&2
  fi
  if [ -n "$SERVER_OUT" ] && [ -s "$SERVER_OUT" ]; then
    printf -- '--- %s: server stdout was not empty ---\n' "$label" >&2
    tail -n "$LOG_TAIL_LINES" -- "$SERVER_OUT" >&2
  fi
}

# =============================================================================
# Process lifecycle
#
# "Cleans up its own process on any exit path" is part of this file's purpose,
# not a nicety: a leaked listener holds a port that the next language, the next
# job or the developer's next run needs.
# =============================================================================

# stop_server: shut the managed child down and reap it.
#
# TERM first, because all three runtimes close their listener cleanly on it;
# then a bounded wait; then KILL for a process that ignored TERM. Every signal is
# guarded by a liveness check rather than by `|| true`, because a child that
# exited on its own between the check and the signal is an expected race and not
# a failure to swallow.
stop_server() {
  if [ -z "$SERVER_PID" ]; then
    return 0
  fi

  local pid="$SERVER_PID" waited=0
  # Cleared before signalling so that a second call - cleanup running after an
  # explicit stop, for instance - cannot signal a pid this script no longer owns.
  SERVER_PID=""

  if kill -0 "$pid" 2>/dev/null; then
    if kill -TERM "$pid" 2>/dev/null; then
      while [ "$waited" -lt "$SHUTDOWN_POLL_ATTEMPTS" ] && kill -0 "$pid" 2>/dev/null; do
        sleep "$READINESS_POLL_INTERVAL"
        waited=$((waited + 1))
      done
    fi
    if kill -0 "$pid" 2>/dev/null; then
      if kill -KILL "$pid" 2>/dev/null; then
        # Give the kernel a moment to tear the process down so that the port is
        # released before the next language tries to bind.
        sleep "$READINESS_POLL_INTERVAL"
      fi
    fi
  fi

  # The ONE tolerated `|| true` in this script. Reaping a child that a signal has
  # just terminated yields that signal's status, and a reaped child's status is
  # not an assertion outcome. Nothing else in this file suppresses a status.
  wait "$pid" 2>/dev/null || true
  return 0
}

# cleanup: the EXIT trap. Runs on success, on a failed assertion, on an
# unexpected error and on an interrupt.
cleanup() {
  # Captured FIRST: everything below would otherwise clobber the status the
  # script is exiting with.
  local rc=$?

  stop_server

  if [ -n "$TMPDIR_RUN" ] && [ -d "$TMPDIR_RUN" ]; then
    if ! rm -rf -- "$TMPDIR_RUN"; then
      printf '%s: warning: could not remove the temporary directory %s\n' \
        "$SCRIPT_NAME" "$TMPDIR_RUN" >&2
    fi
  fi

  exit "$rc"
}

trap cleanup EXIT
# An interrupt and a termination request both route through the EXIT trap, so the
# server is stopped and the temporary directory removed on those paths too. The
# statuses are the conventional 128+signal values.
trap 'exit 130' INT
trap 'exit 143' TERM

# =============================================================================
# Configuration resolution
#
# One properties file holds every configurable value, and the precedence is fixed:
# environment variable > app.config.properties > built-in default. PORT outranks
# the per-language port keys, following the twelve-factor convention for a single
# application per container.
# =============================================================================

# trim: strip leading and trailing whitespace. Used by the properties parser, so
# that `app.name = value` and `app.name=value` mean the same thing - which is what
# java.util.Properties does, and therefore what User.java sees.
trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

# properties_value: the value of one key from app.config.properties, or nothing.
#
# The parsing rules are the applications' rules, deliberately: blank lines and
# lines whose first non-space character is # or ! are comments, the split is on
# the FIRST = only, and both halves are trimmed. A missing file yields nothing,
# which lets the built-in defaults take over instead of crashing the run.
properties_value() {
  local key="$1" line stripped candidate

  if [ ! -f "$CONFIG_PATH" ]; then
    return 0
  fi

  # `|| [ -n "$line" ]` so that a final line with no newline is still read.
  while IFS= read -r line || [ -n "$line" ]; do
    stripped="$(trim "$line")"
    case "$stripped" in
      '' | '#'* | '!'*) continue ;;
    esac
    case "$stripped" in
      *=*) ;;
      *) continue ;;
    esac
    candidate="$(trim "${stripped%%=*}")"
    if [ "$candidate" = "$key" ]; then
      trim "${stripped#*=}"
      return 0
    fi
  done < "$CONFIG_PATH"

  return 0
}

# config_value: resolve one setting across the whole precedence chain.
#   $1 properties key, $2 environment variable name, $3 built-in default
#
# An empty value is treated as absent at every level, so exporting an empty
# variable does not blank out a configured value - the same rule all three
# applications apply.
config_value() {
  local key="$1" env_name="$2" fallback="$3" value

  value="$(trim "${!env_name-}")"
  if [ -n "$value" ]; then
    printf '%s' "$value"
    return 0
  fi

  value="$(properties_value "$key")"
  if [ -n "$value" ]; then
    printf '%s' "$value"
    return 0
  fi

  printf '%s' "$fallback"
}

# connect_authority: the authority this script dials for a given bind address.
#
# A wildcard bind names every interface and is not itself a destination, so it is
# dialled on loopback; connecting to 0.0.0.0 is not portable. Any other address is
# dialled as configured, so a listener deliberately bound to 127.0.0.2 is checked
# where it actually is. A bare IPv6 literal is bracketed so that its colons cannot
# be misread as a port separator.
connect_authority() {
  local host lowered
  host="$(trim "$1")"
  # The ASCII range map is deliberate, not an oversight: a host token is ASCII, and
  # a locale-aware fold ([:upper:]/[:lower:]) would make this comparison depend on
  # the runner's locale - in a Turkish locale it maps I to a dotless i, which would
  # break a comparison that must be byte-deterministic in CI and locally alike.
  # shellcheck disable=SC2018,SC2019
  lowered="$(printf '%s' "$host" | tr 'A-Z' 'a-z')"

  case "$lowered" in
    '' | '0.0.0.0' | '*' | '::' | '[::]')
      printf '127.0.0.1'
      return 0
      ;;
  esac

  case "$host" in
    '['*']')
      printf '%s' "$host"
      return 0
      ;;
    *:*)
      printf '[%s]' "$host"
      return 0
      ;;
  esac

  printf '%s' "$host"
}

# resolve_configuration: fill in every RESOLVED_* value once, before anything is
# started, so that the value the server is given and the value the assertions
# expect are the same value by construction.
resolve_configuration() {
  RESOLVED_NAME="$(config_value 'app.name' 'APP_NAME' "$DEFAULT_APP_NAME")"
  RESOLVED_VERSION="$(config_value 'app.version' 'APP_VERSION' "$DEFAULT_APP_VERSION")"

  if [ -n "$OPT_PATH" ]; then
    RESOLVED_PATH="$OPT_PATH"
  else
    RESOLVED_PATH="$(config_value 'health.path' 'HEALTH_PATH' "$DEFAULT_HEALTH_PATH")"
  fi

  if [ -n "$OPT_HOST" ]; then
    RESOLVED_HOST="$OPT_HOST"
  else
    RESOLVED_HOST="$(config_value 'app.host' 'APP_HOST' "$DEFAULT_APP_HOST")"
  fi

  CONNECT_HOST="$(connect_authority "$RESOLVED_HOST")"

  if [ -n "$OPT_TIMEOUT" ]; then
    TIMEOUT_SECONDS="$OPT_TIMEOUT"
  else
    TIMEOUT_SECONDS="$DEFAULT_TIMEOUT_SECONDS"
  fi

  # The resolved values must themselves be usable, or every later assertion would
  # be measuring the wrong thing. These are configuration errors rather than
  # contract violations, hence the usage exit code.
  case "$RESOLVED_PATH" in
    /*) ;;
    *) usage_error "the resolved health path '$RESOLVED_PATH' does not start with '/'" ;;
  esac
  case "$RESOLVED_PATH" in
    *[[:space:]]* | *'?'* | *'#'*)
      usage_error "the resolved health path '$RESOLVED_PATH' must not contain whitespace, '?' or '#'"
      ;;
  esac
  if [ -z "$RESOLVED_NAME" ]; then
    usage_error "the resolved application name is empty; set APP_NAME or app.name"
  fi
  if [ -z "$CONNECT_HOST" ]; then
    usage_error "the resolved host '$RESOLVED_HOST' yields no address to connect to"
  fi
  if [ "$UNKNOWN_PATH" = "$RESOLVED_PATH" ]; then
    # Defensive: the negative-path assertion would otherwise be a second
    # assertion of the happy path, and would pass while proving nothing.
    usage_error "the resolved health path collides with the unknown-path probe '$UNKNOWN_PATH'"
  fi
}

# port_for_language: the effective port for one language.
#
#   exactly one language selected:  --port > PORT > <LANG>_PORT > file > default
#   more than one selected:                  <LANG>_PORT > file > default
#
# PORT is refused outright for a multi-language run (see parse_arguments), because
# it applies to all three applications and would collide.
port_for_language() {
  local lang="$1" key env_name fallback value

  case "$lang" in
    python) key='python.port'; env_name='PYTHON_PORT'; fallback="$DEFAULT_PYTHON_PORT" ;;
    node)   key='node.port';   env_name='NODE_PORT';   fallback="$DEFAULT_NODE_PORT" ;;
    java)   key='java.port';   env_name='JAVA_PORT';   fallback="$DEFAULT_JAVA_PORT" ;;
    *)      return 1 ;;
  esac

  if [ -n "$OPT_PORT" ]; then
    printf '%s' "$OPT_PORT"
    return 0
  fi

  value="$(trim "${PORT-}")"
  if [ -n "$value" ]; then
    printf '%s' "$value"
    return 0
  fi

  config_value "$key" "$env_name" "$fallback"
}


# =============================================================================
# JSON reading without a dependency
#
# One cascade, one function per operation, used by every assertion for every
# language - so the three implementations are read the same way and cannot be
# compared through three different lenses. Nothing is installed: whichever of jq,
# python3 or node is already present is used, and if none is, that is a hard
# failure rather than a skipped assertion.
#
# Order matters. jq is purpose-built and fastest; python3's json module preserves
# insertion order and exits non-zero on a missing key, which makes it fail closed;
# node is last because a repository that has node almost certainly has one of the
# other two, and because JSON.stringify's escaping rules are the ones already
# proven byte-identical against index.js's own output.
# =============================================================================

select_json_reader() {
  if command -v jq >/dev/null 2>&1; then
    JSON_READER="jq"
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    JSON_READER="python3"
    return 0
  fi
  if command -v node >/dev/null 2>&1; then
    JSON_READER="node"
    return 0
  fi
  return 1
}

# json_keys: the object's keys, comma joined, in DECLARATION order.
#
# jq's keys_unsorted is mandatory here: plain `keys` sorts alphabetically and
# would silently destroy the key-order half of the contract, turning a real
# ordering regression into a passing gate. The python and node readers preserve
# insertion order natively.
#
# Returns non-zero when the document is not a JSON object, so an HTML error page
# or a JSON array fails closed instead of yielding an empty key list.
json_keys() {
  local file="$1"
  case "$JSON_READER" in
    jq)
      jq -er 'if type == "object" then (keys_unsorted | join(",")) else error("not an object") end' < "$file"
      ;;
    python3)
      python3 -c '
import json, sys
try:
    document = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
if not isinstance(document, dict):
    sys.exit(1)
sys.stdout.write(",".join(document.keys()))
' < "$file"
      ;;
    node)
      node -e '
const text = require("fs").readFileSync(0, "utf8");
let document;
try {
  document = JSON.parse(text);
} catch (error) {
  process.exit(1);
}
if (document === null || typeof document !== "object" || Array.isArray(document)) {
  process.exit(1);
}
process.stdout.write(Object.keys(document).join(","));
' < "$file"
      ;;
    *)
      return 1
      ;;
  esac
}

# json_field: one field's value, or a non-zero status when the key is absent.
#
# A missing key must fail rather than read as an empty string, or "the field is
# gone" would be indistinguishable from "the field is empty" - and only one of
# those is a contract violation the operator can act on quickly.
json_field() {
  local file="$1" key="$2"
  case "$JSON_READER" in
    jq)
      jq -er --arg k "$key" 'if has($k) then (.[$k] | tostring) else error("missing key") end' < "$file"
      ;;
    python3)
      python3 -c '
import json, sys
try:
    document = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
if not isinstance(document, dict):
    sys.exit(1)
key = sys.argv[1]
if key not in document:
    sys.exit(1)
value = document[key]
sys.stdout.write(value if isinstance(value, str) else json.dumps(value))
' "$key" < "$file"
      ;;
    node)
      node -e '
const text = require("fs").readFileSync(0, "utf8");
let document;
try {
  document = JSON.parse(text);
} catch (error) {
  process.exit(1);
}
if (document === null || typeof document !== "object" || Array.isArray(document)) {
  process.exit(1);
}
const key = process.argv[1];
if (!Object.prototype.hasOwnProperty.call(document, key)) {
  process.exit(1);
}
const value = document[key];
process.stdout.write(typeof value === "string" ? value : JSON.stringify(value));
' "$key" < "$file"
      ;;
    *)
      return 1
      ;;
  esac
}

# json_compact: the same document re-serialised with no whitespace.
#
# Comparing this against the raw bytes proves two things in one assertion: the
# body is valid JSON, and it is compact. Non-ASCII escaping is disabled on the
# python path (ensure_ascii=False) because jq and JSON.stringify emit raw UTF-8,
# and an escaping difference would fail the comparison for the wrong reason.
json_compact() {
  local file="$1"
  case "$JSON_READER" in
    jq)
      jq -cj . < "$file"
      ;;
    python3)
      python3 -c '
import json, sys
try:
    document = json.load(sys.stdin)
except ValueError:
    sys.exit(1)
sys.stdout.write(json.dumps(document, separators=(",", ":"), ensure_ascii=False))
' < "$file"
      ;;
    node)
      node -e '
const text = require("fs").readFileSync(0, "utf8");
let document;
try {
  document = JSON.parse(text);
} catch (error) {
  process.exit(1);
}
process.stdout.write(JSON.stringify(document));
' < "$file"
      ;;
    *)
      return 1
      ;;
  esac
}

# =============================================================================
# Runtime discovery
#
# A missing runtime is a hard failure with an actionable message. A silent skip
# would let a green run mean "two of three languages were checked", which is
# exactly the sort of gate that proves nothing.
# =============================================================================

# other_languages: the two languages that are not $1, for the actionable half of
# a missing-runtime message.
other_languages() {
  local excluded="$1" lang result=""
  for lang in python node java; do
    if [ "$lang" != "$excluded" ]; then
      if [ -z "$result" ]; then
        result="$lang"
      else
        result="$result $lang"
      fi
    fi
  done
  printf '%s' "$result"
}

require_runtime() {
  local command_name="$1" lang="$2"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    fail "'$command_name' not found on PATH, so the $lang health endpoint cannot be verified; install it, or run 'bash scripts/$SCRIPT_NAME $(other_languages "$lang")' to verify only the runtimes you have"
  fi
}

# resolve_python_runtime: python3 by preference, python as the fallback - the same
# pair the container stage and the documentation use. Sets a global rather than
# printing, so that the failure path can exit the script instead of a subshell.
resolve_python_runtime() {
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_RUNTIME="python3"
    return 0
  fi
  if command -v python >/dev/null 2>&1; then
    PYTHON_RUNTIME="python"
    return 0
  fi
  fail "neither 'python3' nor 'python' was found on PATH, so the python health endpoint cannot be verified; install a Python 3 runtime, or run 'bash scripts/$SCRIPT_NAME $(other_languages python)' to verify only the runtimes you have"
}

# =============================================================================
# HTTP
#
# TRAP 2, restated where it matters: curl -I issues a HEAD request, which this
# GET-only endpoint answers with 405. Headers are therefore captured with a GET
# whose body is written to a file and whose status comes from -w, which takes all
# three parts of the response in a single exchange. Do not "simplify" this to -I.
#
# Under `set -euo pipefail` a curl that cannot connect exits 7, which would abort
# the script mid-assertion, so the status is captured with an explicit guard and
# then inspected: an unreachable server must be reported as a precise failure,
# not as an abort with no message.
# =============================================================================

http_request() {
  local url="$1" method="$2" header_file="$3" body_file="$4"

  HTTP_CODE=""
  HTTP_CURL_RC="0"

  # -sS keeps curl quiet except for real errors, which land on stderr where they
  # help; -D and -o capture the headers and the body; -w yields the status code.
  HTTP_CODE="$(curl -sS -X "$method" -D "$header_file" -o "$body_file" \
    -w '%{http_code}' --max-time "$CURL_MAX_TIME" -- "$url")" || HTTP_CURL_RC="$?"

  return 0
}

# expect_status: one request, one status assertion, one precise message. Used for
# the path variants, where the status is the whole contract.
expect_status() {
  local label="$1" url="$2" method="$3" expected="$4"

  http_request "$url" "$method" "$TMPDIR_RUN/variant.h" "$TMPDIR_RUN/variant.b"
  if [ "$HTTP_CODE" != "$expected" ]; then
    fail "$label: $method $url returned HTTP '${HTTP_CODE:-none}' (curl exit $HTTP_CURL_RC), expected $expected"
  fi
}

# lowered_headers: the captured header block with carriage returns removed and
# every byte folded to lower case.
#
# TRAP 1: the Java server normalises response field names, so this fold is what
# lets one assertion set cover all three implementations. TRAP 1b: the fold
# lowercases VALUES too, which is why the Allow assertion looks for 'get'.
lowered_headers() {
  local header_file="$1" destination="$2"
  # The ASCII range map is deliberate. RFC 9110 field names are ASCII tokens, so
  # accent and foreign-alphabet support is not wanted here; a locale-aware fold
  # would make this gate's verdict depend on the runner's locale, and in a Turkish
  # locale it maps I to a dotless i, which would stop 'content-type' matching.
  # shellcheck disable=SC2018,SC2019
  tr -d '\r' < "$header_file" | tr 'A-Z' 'a-z' > "$destination"
}

# header_names: every field name in a lowercased block, one per line.
#
# A line qualifies when it contains a colon that is not its first character and
# does not begin with whitespace, which excludes the status line (no colon) and an
# obsolete folded continuation (leading whitespace).
header_names() {
  awk 'index($0, ":") > 1 && $0 !~ /^[ \t]/ { print substr($0, 1, index($0, ":") - 1) }' < "$1"
}

header_present() {
  local name="$1" lowered="$2"
  grep -Eq "^$name:" -- "$lowered"
}

header_value() {
  local name="$1" lowered="$2"
  awk -v key="$name:" '$1 == key { sub(/^[^:]*:[ \t]*/, "", $0); print; exit }' < "$lowered"
}

# wait_until_ready: bounded readiness polling.
#
# This is readiness, NOT a retry: it runs before the first assertion, and no
# assertion is ever attempted twice. A server that has to compile itself first -
# `java User.java` does - legitimately needs a moment, and the budget is what
# separates "still starting" from "never going to answer".
#
# -f is legal here because the call sits inside an `if` condition, where a
# non-zero exit is data rather than an abort. curl's own complaints go to the
# readiness log so that forty attempts do not bury the real output; the last of
# them is printed if the budget runs out.
wait_until_ready() {
  local url="$1" label="$2"

  # The budget is enforced as WALL CLOCK, not as an attempt count. An attempt
  # count would be a false promise: each attempt can block until curl's own
  # timeout, so "40 attempts" could run far past the seconds the caller asked
  # for. Bash's SECONDS builtin gives a true deadline and costs no external
  # process, keeping the script's tool surface at curl plus a JSON reader.
  local probe_max_time="$READINESS_PROBE_MAX_TIME"
  if [ "$probe_max_time" -gt "$TIMEOUT_SECONDS" ]; then
    probe_max_time="$TIMEOUT_SECONDS"   # a 1s budget must not wait 2s
  fi

  local started="$SECONDS"
  local attempt=0
  local elapsed=0

  while :; do
    # Checked FIRST and every iteration: a child that has already exited will
    # never answer, so failing here turns a full-budget wait into an immediate,
    # precise diagnosis with the server's own log as evidence.
    if [ -n "$SERVER_PID" ] && ! kill -0 "$SERVER_PID" 2>/dev/null; then
      dump_server_log "$label"
      fail "$label: the server exited before it answered $url"
    fi

    # -f is permitted here ONLY because it sits inside an `if` condition. As a
    # bare statement it would abort the script under `set -e` (a non-2xx makes
    # curl exit 22). This bounded polling is readiness, NEVER a retry of a
    # failed assertion - assertions themselves are never retried (S6).
    if curl -fsS -o /dev/null --max-time "$probe_max_time" -- "$url" 2>>"$READINESS_LOG"; then
      return 0
    fi

    attempt=$((attempt + 1))
    elapsed=$((SECONDS - started))
    if [ "$elapsed" -ge "$TIMEOUT_SECONDS" ]; then
      break
    fi
    sleep "$READINESS_POLL_INTERVAL"
  done

  dump_server_log "$label"
  fail "$label: $url did not answer within the ${TIMEOUT_SECONDS}s readiness budget ($attempt attempts over ${elapsed}s)"
}


# =============================================================================
# The assertion set - acceptance gate G9
#
# Every assertion below is applied to every selected language, in the same order,
# from the same code. The first failure exits: no gate here is advisory, and a
# failed assertion is never retried.
# =============================================================================

# assert_content_length_matches: the declared length is the length that arrived.
# A Content-Length that disagrees with the body is a framing bug, and it is the
# one header whose correctness a client actually depends on.
assert_content_length_matches() {
  local label="$1" lowered="$2" body_file="$3" declared actual

  declared="$(header_value 'content-length' "$lowered")"
  case "$declared" in
    '' | *[!0-9]*)
      fail "$label: Content-Length is '$declared', which is not a plain non-negative decimal"
      ;;
  esac

  # `wc -c < file` rather than `wc -c file`, so the count arrives without the
  # filename; trimmed because some platforms pad the number.
  actual="$(trim "$(wc -c < "$body_file")")"
  if [ "$declared" != "$actual" ]; then
    fail "$label: Content-Length declares $declared bytes but the body is $actual bytes on the wire"
  fi
}

# assert_frozen_headers: the response's field-name SET, asserted by equality.
#
# Set equality is what makes this a disclosure check as well as a contract check:
# it proves in one assertion that the required fields are present AND that nothing
# else is - a Server banner, an X-Powered-By, a Keep-Alive advertising the idle
# timeout, an ETag, a Set-Cookie. A pair of presence checks can only ever prove
# the first half, and the half it misses is the half that leaks.
#
#   $1 label, $2 lowercased header block, $3 scratch file for the names,
#   $4 "yes" when an Allow header is required (the 405 refusal) or "no"
assert_frozen_headers() {
  local label="$1" lowered="$2" names_file="$3" allow_expected="$4"
  local name value

  if ! header_present 'content-type' "$lowered"; then
    fail "$label: the response carries no Content-Type header; the contract requires '$EXPECTED_CONTENT_TYPE'"
  fi
  if ! grep -Eq "^content-type:[[:space:]]*$EXPECTED_CONTENT_TYPE" -- "$lowered"; then
    fail "$label: Content-Type is '$(header_value 'content-type' "$lowered")', expected '$EXPECTED_CONTENT_TYPE' - the plain JSON media type, which is a deliberate deviation from the health-check draft's own media type"
  fi

  if ! header_present 'cache-control' "$lowered"; then
    fail "$label: the response carries no Cache-Control header; a health answer served from a cache is worse than no answer at all"
  fi
  value="$(header_value 'cache-control' "$lowered")"
  case "$value" in
    *"$REQUIRED_CACHE_DIRECTIVE"*) ;;
    *)
      fail "$label: Cache-Control is '$value', which does not contain the required '$REQUIRED_CACHE_DIRECTIVE' directive"
      ;;
  esac

  if ! header_present 'content-length' "$lowered"; then
    fail "$label: the response carries no Content-Length header"
  fi

  # The disclosure-bearing field, asserted absent for every implementation with no
  # exception: nothing prevents any of the three from suppressing it, and Python
  # would otherwise advertise its interpreter version here.
  if header_present 'server' "$lowered"; then
    fail "$label: a Server header is present ('$(header_value 'server' "$lowered")'); the contract forbids a server banner, which discloses the runtime and its version"
  fi

  header_names "$lowered" > "$names_file"
  while IFS= read -r name || [ -n "$name" ]; do
    case "$name" in
      '')
        ;;
      content-type | cache-control | content-length)
        ;;
      date)
        # Tolerated, and tolerated only here: com.sun.net.httpserver writes Date
        # unconditionally - a sentinel value set by the application is overwritten
        # and removing the field after sendResponseHeaders still sends it - so the
        # Java implementation cannot suppress it. RFC 9110 section 6.6.1 says an
        # origin server SHOULD send Date and its value discloses nothing about the
        # runtime. Its FORMAT is asserted; its value never is, for the same reason
        # the payload timestamp is never compared: it is a clock reading.
        value="$(header_value 'date' "$lowered")"
        if ! [[ "$value" =~ $HTTP_DATE_PATTERN ]]; then
          fail "$label: the Date header value '$value' is not a well-formed HTTP-date"
        fi
        ;;
      allow)
        if [ "$allow_expected" != "yes" ]; then
          fail "$label: an Allow header is present on a response that must not carry one; Allow belongs to the 405 refusal alone"
        fi
        value="$(header_value 'allow' "$lowered")"
        case "$value" in
          *"$EXPECTED_ALLOW_METHOD"*) ;;
          *)
            fail "$label: Allow is '$value', expected it to name '$EXPECTED_ALLOW_METHOD' - the header block is folded to lower case, so GET reads as get"
            ;;
        esac
        ;;
      *)
        fail "$label: unexpected response header '$name'; the frozen set is Content-Type, Cache-Control and Content-Length, plus Allow on a 405 and the Date the Java runtime inserts unconditionally - anything else is a disclosure this contract does not permit"
        ;;
    esac
  done < "$names_file"

  if [ "$allow_expected" = "yes" ] && ! header_present 'allow' "$lowered"; then
    fail "$label: the 405 response carries no Allow header; the contract requires 'Allow: GET'"
  fi
}

# assert_health_body: the document itself.
assert_health_body() {
  local label="$1" body_file="$2"
  local keys raw compact compact_bytes actual
  local name version timestamp status

  keys="$(json_keys "$body_file")" ||
    fail "$label: the response body is not a JSON object (read with $JSON_READER)"
  if [ "$keys" != "$EXPECTED_KEYS" ]; then
    fail "$label: the body's keys are '$keys', expected exactly '$EXPECTED_KEYS' in that order - a missing, extra or reordered field is a change to the contract"
  fi

  # Compactness, proved by round trip: re-serialising the parsed document with no
  # whitespace must reproduce the bytes that arrived. This single comparison proves
  # the body is valid JSON AND that it carries no whitespace.
  raw="$(cat -- "$body_file")"
  compact="$(json_compact "$body_file")" ||
    fail "$label: the response body could not be re-serialised as JSON (read with $JSON_READER)"
  if [ "$raw" != "$compact" ]; then
    fail "$label: the response body is not compact JSON; re-serialising it compactly yields '$compact'"
  fi
  # The comparison above is made on strings, which cannot see a trailing newline,
  # so the byte counts are compared as well: whitespace at the end of the body is
  # still whitespace.
  actual="$(trim "$(wc -c < "$body_file")")"
  compact_bytes="$(trim "$(printf '%s' "$compact" | wc -c)")"
  if [ "$actual" != "$compact_bytes" ]; then
    fail "$label: the response body is $actual bytes but its compact form is $compact_bytes bytes, so the body carries stray whitespace"
  fi

  name="$(json_field "$body_file" 'name')" ||
    fail "$label: the body has no 'name' field"
  if [ -z "$name" ]; then
    fail "$label: the 'name' field is empty, and the contract requires a non-empty application name"
  fi
  if [ "$name" != "$RESOLVED_NAME" ]; then
    fail "$label: name field is '$name', expected '$RESOLVED_NAME'"
  fi

  version="$(json_field "$body_file" 'version')" ||
    fail "$label: the body has no 'version' field"
  if ! [[ "$version" =~ $VERSION_PATTERN ]]; then
    fail "$label: version field is '$version', which is not a three-part dotted numeric version"
  fi
  if [ "$version" != "$RESOLVED_VERSION" ]; then
    fail "$label: version field is '$version', expected '$RESOLVED_VERSION'"
  fi

  timestamp="$(json_field "$body_file" 'timestamp')" ||
    fail "$label: the body has no 'timestamp' field"
  # FORMAT ONLY, NEVER value. This is the one non-deterministic field in the
  # document, and a value comparison would make this gate fail for a reason
  # unrelated to correctness.
  if ! [[ "$timestamp" =~ $TIMESTAMP_PATTERN ]]; then
    fail "$label: timestamp field is '$timestamp', which does not match the required format YYYY-MM-DDTHH:MM:SSZ"
  fi

  status="$(json_field "$body_file" 'status')" ||
    fail "$label: the body has no 'status' field"
  if [ "$status" != "$EXPECTED_STATUS" ]; then
    fail "$label: status field is '$status', expected literal '$EXPECTED_STATUS'"
  fi

  # The reference document is fixed width, so its length is asserted - but only
  # under the DEFAULT configuration, because an override to the name, the version
  # or the path legitimately changes it. This is a condition on the inputs, not an
  # advisory assertion.
  if [ "$RESOLVED_NAME" = "$DEFAULT_APP_NAME" ] &&
    [ "$RESOLVED_VERSION" = "$DEFAULT_APP_VERSION" ] &&
    [ "$RESOLVED_PATH" = "$DEFAULT_HEALTH_PATH" ]; then
    if [ "$actual" != "$REFERENCE_BODY_BYTES" ]; then
      fail "$label: the body is $actual bytes under the default configuration, expected exactly $REFERENCE_BODY_BYTES"
    fi
  fi

  # Recorded for the PASS line, so the operator sees the size of the document that
  # was actually asserted rather than the size of the last thing fetched.
  LAST_BODY_BYTES="$actual"
}

# assert_health_response: status, headers and body of the happy path, from a
# single exchange - see TRAP 2 for why the headers are not fetched separately.
assert_health_response() {
  local label="$1" url="$2"
  local header_file="$TMPDIR_RUN/health.headers"
  local body_file="$TMPDIR_RUN/health.body"
  local lowered="$TMPDIR_RUN/health.headers.lower"
  local names_file="$TMPDIR_RUN/health.names"

  http_request "$url" 'GET' "$header_file" "$body_file"
  if [ "$HTTP_CODE" != "200" ]; then
    dump_server_log "$label"
    fail "$label: GET $url returned HTTP '${HTTP_CODE:-none}' (curl exit $HTTP_CURL_RC), expected 200 - a passing status must use a 2xx code"
  fi

  lowered_headers "$header_file" "$lowered"
  assert_frozen_headers "$label" "$lowered" "$names_file" 'no'
  assert_content_length_matches "$label" "$lowered" "$body_file"
  assert_health_body "$label" "$body_file"
}

# assert_path_variants: the route's own rules. The exact path is not repeated
# here - assert_health_response has already asserted it - so each request below
# exercises exactly one rule of the contract.
assert_path_variants() {
  local label="$1" base="$2"

  expect_status "$label (one trailing slash accepted)" \
    "$base$RESOLVED_PATH/" 'GET' '200'
  expect_status "$label (query string stripped before matching)" \
    "$base$RESOLVED_PATH?x=1" 'GET' '200'
  # Exactly one forgiving slash: two describe a different path, and a route that
  # answered both would be ambiguous for anything downstream that matches on a
  # path - a proxy rule, an access log, a rate limiter.
  expect_status "$label (two trailing slashes rejected)" \
    "$base$RESOLVED_PATH//" 'GET' '404'
}

# assert_unknown_path: the repository's first implemented request validation.
assert_unknown_path() {
  local label="$1" base="$2"
  local url="$base$UNKNOWN_PATH"
  local header_file="$TMPDIR_RUN/notfound.headers"
  local body_file="$TMPDIR_RUN/notfound.body"
  local body

  http_request "$url" 'GET' "$header_file" "$body_file"
  if [ "$HTTP_CODE" != "404" ]; then
    fail "$label: GET $url returned HTTP '${HTTP_CODE:-none}' (curl exit $HTTP_CURL_RC), expected 404 for an unknown path"
  fi
  body="$(cat -- "$body_file")"
  if [ "$body" != "$EXPECTED_NOT_FOUND_BODY" ]; then
    fail "$label: the 404 body is '$body', expected exactly '$EXPECTED_NOT_FOUND_BODY'"
  fi
}

# assert_method_not_allowed: POST is the mandatory non-GET assertion. HEAD is
# deliberately not asserted - see the header comment for why - and curl -I must
# never be used anywhere in this script for the same reason.
assert_method_not_allowed() {
  local label="$1" url="$2"
  local header_file="$TMPDIR_RUN/refusal.headers"
  local body_file="$TMPDIR_RUN/refusal.body"
  local lowered="$TMPDIR_RUN/refusal.headers.lower"
  local names_file="$TMPDIR_RUN/refusal.names"
  local body

  http_request "$url" 'POST' "$header_file" "$body_file"
  if [ "$HTTP_CODE" != "405" ]; then
    fail "$label: POST $url returned HTTP '${HTTP_CODE:-none}' (curl exit $HTTP_CURL_RC), expected 405 for a method other than GET"
  fi

  lowered_headers "$header_file" "$lowered"
  assert_frozen_headers "$label (POST refusal)" "$lowered" "$names_file" 'yes'
  assert_content_length_matches "$label (POST refusal)" "$lowered" "$body_file"

  body="$(cat -- "$body_file")"
  if [ "$body" != "$EXPECTED_METHOD_NOT_ALLOWED_BODY" ]; then
    fail "$label: the 405 body is '$body', expected exactly '$EXPECTED_METHOD_NOT_ALLOWED_BODY'"
  fi

  # The happy path is asserted last as well, so that a refused request cannot have
  # left the listener unable to serve: the endpoint must still be there afterwards.
  # This is a fresh assertion, not a retry of a failed one.
  expect_status "$label (still serving after a refusal)" "$url" 'GET' '200'
}


# =============================================================================
# Launching one server
#
# The working directory is the repository root before anything is launched. That
# matters for the Java implementation: it looks for app.config.properties beside
# its own code source and then falls back to the process working directory, which
# is also what the container image relies on (WORKDIR /app). app.py resolves the
# file relative to __file__ and index.js relative to __dirname, so those two are
# indifferent - but a single, predictable working directory is what makes all three
# read the SAME configuration, which is the whole point of having one.
#
# --serve is the only flag ever passed. --probe belongs to the container
# HEALTHCHECK, and the default no-flag invocation belongs to the backward
# compatibility gates, which are the workflow's business and not this script's.
# =============================================================================

start_server() {
  local lang="$1" runtime="$2" entry="$3" port="$4" port_var="$5"

  SERVER_OUT="$TMPDIR_RUN/$lang.stdout.log"
  SERVER_ERR="$TMPDIR_RUN/$lang.stderr.log"
  READINESS_LOG="$TMPDIR_RUN/$lang.readiness.log"
  : > "$SERVER_OUT"
  : > "$SERVER_ERR"
  : > "$READINESS_LOG"

  # The resolved path and host are exported into the child, so the route the
  # server serves is the route this script asserts. Without that, `--path /healthz`
  # would leave the server on /health and the gate would fail for the wrong reason.
  #
  # stdin is /dev/null so the child cannot consume this script's input, and its two
  # output streams are captured separately: all three applications write their
  # banner and diagnostics to stderr and keep stdout for their legacy output, so
  # stderr output here is expected and is NOT a failure signal.
  if [ "$MULTI_LANGUAGE" = 'yes' ]; then
    # PORT applies to every application, so a multi-language run strips it from
    # the child environment and names the per-language variable instead. An
    # inherited PORT was already refused during argument validation; this is what
    # makes that refusal effective rather than advisory.
    env -u PORT \
      "HEALTH_PATH=$RESOLVED_PATH" \
      "APP_HOST=$RESOLVED_HOST" \
      "$port_var=$port" \
      "$runtime" "$entry" --serve \
      < /dev/null > "$SERVER_OUT" 2> "$SERVER_ERR" &
  else
    # One language, so PORT is unambiguous - and it outranks every other source
    # inside all three applications, which makes it the one variable that cannot
    # be overruled by a stale properties value.
    env \
      "HEALTH_PATH=$RESOLVED_PATH" \
      "APP_HOST=$RESOLVED_HOST" \
      "PORT=$port" \
      "$runtime" "$entry" --serve \
      < /dev/null > "$SERVER_OUT" 2> "$SERVER_ERR" &
  fi

  SERVER_PID="$!"
}

# validate_port: a port that cannot be bound is a configuration error, and saying
# so plainly beats reporting the listener as unreachable and sending an operator
# looking for a network fault.
validate_port() {
  local port="$1" source="$2"
  case "$port" in
    '' | *[!0-9]*)
      usage_error "the port resolved for $source is '$port', which is not a decimal number"
      ;;
  esac
  if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
    usage_error "the port resolved for $source is $port, outside the usable range 1-65535"
  fi
}

# =============================================================================
# Verifying one language
# =============================================================================

verify_language() {
  local lang="$1"
  local runtime entry port_var port base url

  case "$lang" in
    python)
      resolve_python_runtime
      runtime="$PYTHON_RUNTIME"
      entry='app.py'
      port_var='PYTHON_PORT'
      ;;
    node)
      require_runtime 'node' 'node'
      runtime='node'
      entry='index.js'
      port_var='NODE_PORT'
      ;;
    java)
      require_runtime 'java' 'java'
      runtime='java'
      # Single-file source launch, deliberately: it needs no javac step and leaves
      # no User.class behind, so the working tree stays clean without this script
      # having to clean up after itself. `java -cp . User` would require a compiled
      # class in the repository root and would dirty the tree.
      entry='User.java'
      port_var='JAVA_PORT'
      ;;
    *)
      usage_error "unknown language selector '$lang' (expected python, node, java or all)"
      ;;
  esac

  if [ ! -f "$REPO_ROOT/$entry" ]; then
    fail "$lang: $REPO_ROOT/$entry does not exist, so there is nothing to verify"
  fi

  port="$(port_for_language "$lang")" ||
    fail "$lang: no port could be resolved"
  validate_port "$port" "$lang"

  base="http://$CONNECT_HOST:$port"
  url="$base$RESOLVED_PATH"

  start_server "$lang" "$runtime" "$entry" "$port" "$port_var"
  wait_until_ready "$url" "$lang"

  assert_health_response "$lang" "$url"
  assert_path_variants "$lang" "$base"
  assert_unknown_path "$lang" "$base"
  assert_method_not_allowed "$lang" "$url"

  # Stopped before the next language starts, so at most one server is live and at
  # most one pid is trap-managed at any moment - which is what lets a run with no
  # --port use the three default ports without a collision.
  stop_server

  report "PASS $lang $url ($LAST_BODY_BYTES bytes)"
}

# =============================================================================
# Command line
# =============================================================================

add_language() {
  local candidate="$1" existing
  if [ "${#SELECTED_LANGUAGES[@]}" -gt 0 ]; then
    for existing in "${SELECTED_LANGUAGES[@]}"; do
      if [ "$existing" = "$candidate" ]; then
        # Naming a language twice is harmless and means the same thing once.
        return 0
      fi
    done
  fi
  SELECTED_LANGUAGES+=("$candidate")
}

# require_option_value: --flag VALUE needs its VALUE, and a flag that swallowed the
# next flag would produce a confusing failure much later.
require_option_value() {
  local flag="$1" remaining="$2"
  if [ "$remaining" -lt 2 ]; then
    usage_error "$flag requires a value"
  fi
}

parse_arguments() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      python | node | java)
        add_language "$1"
        ;;
      all)
        add_language 'python'
        add_language 'node'
        add_language 'java'
        ;;
      --host)
        require_option_value '--host' "$#"
        OPT_HOST="$2"
        shift
        ;;
      --host=*)
        OPT_HOST="${1#*=}"
        ;;
      --port)
        require_option_value '--port' "$#"
        OPT_PORT="$2"
        shift
        ;;
      --port=*)
        OPT_PORT="${1#*=}"
        ;;
      --path)
        require_option_value '--path' "$#"
        OPT_PATH="$2"
        shift
        ;;
      --path=*)
        OPT_PATH="${1#*=}"
        ;;
      --timeout)
        require_option_value '--timeout' "$#"
        OPT_TIMEOUT="$2"
        shift
        ;;
      --timeout=*)
        OPT_TIMEOUT="${1#*=}"
        ;;
      -h | --help)
        # An explicit request for help is not an error, so it goes to stdout and
        # exits 0.
        usage
        exit 0
        ;;
      *)
        usage_error "unknown argument '$1' (expected a language selector - python, node, java or all - or one of --host, --port, --path, --timeout, --help)"
        ;;
    esac
    shift
  done

  # No selector means every language, because that is the bare invocation
  # README.md documents.
  if [ "${#SELECTED_LANGUAGES[@]}" -eq 0 ]; then
    SELECTED_LANGUAGES=('python' 'node' 'java')
  fi
  if [ "${#SELECTED_LANGUAGES[@]}" -gt 1 ]; then
    MULTI_LANGUAGE='yes'
  fi

  if [ -n "$OPT_HOST" ]; then
    case "$OPT_HOST" in
      *[[:space:]]*) usage_error "--host '$OPT_HOST' must not contain whitespace" ;;
    esac
  fi

  if [ -n "$OPT_TIMEOUT" ]; then
    case "$OPT_TIMEOUT" in
      '' | *[!0-9]*) usage_error "--timeout '$OPT_TIMEOUT' is not a whole number of seconds" ;;
    esac
    if [ "$OPT_TIMEOUT" -lt 1 ]; then
      usage_error "--timeout must be at least 1 second"
    fi
  fi

  # Three servers cannot share one port, so an explicit port is legal only when
  # exactly one language is selected. Failing closed here beats a bind collision
  # reported as an unreachable listener.
  if [ -n "$OPT_PORT" ] && [ "${#SELECTED_LANGUAGES[@]}" -ne 1 ]; then
    usage_error "--port requires exactly one language selector (three languages need three distinct ports)"
  fi
  if [ -n "$OPT_PORT" ]; then
    validate_port "$OPT_PORT" '--port'
  fi

  # An inherited PORT has exactly the same problem, and it is easier to miss
  # because nobody typed it on this command line.
  if [ "${#SELECTED_LANGUAGES[@]}" -gt 1 ] && [ -n "$(trim "${PORT-}")" ]; then
    usage_error "PORT is set in the environment; it applies to all three applications and would collide. Select one language or unset PORT."
  fi
}

# =============================================================================
# Entry point
# =============================================================================

main() {
  parse_arguments "$@"

  # curl is not optional: it is the only HTTP client this script uses, chosen
  # because it is already present on every runner and developer machine that can
  # run this repository's CI.
  if ! command -v curl >/dev/null 2>&1; then
    fail "'curl' not found on PATH; it is required to exercise the endpoint"
  fi
  if ! select_json_reader; then
    fail "no JSON reader available (need jq, python3 or node); nothing is installed by this script, so one of the three must already be present"
  fi

  resolve_configuration

  # Outside the repository, always: the working tree must stay clean, including
  # untracked files, and a header capture left in the checkout would fail that
  # check. Removed by the EXIT trap on every path.
  TMPDIR_RUN="$(mktemp -d "${TMPDIR:-/tmp}/verify-health.XXXXXXXX")"

  cd -- "$REPO_ROOT"

  local lang
  local verified=0
  local total="${#SELECTED_LANGUAGES[@]}"

  for lang in "${SELECTED_LANGUAGES[@]}"; do
    verify_language "$lang"
    verified=$((verified + 1))
  done

  report "PASS $verified/$total verified against the frozen /health contract: ${SELECTED_LANGUAGES[*]}"
}

main "$@"
