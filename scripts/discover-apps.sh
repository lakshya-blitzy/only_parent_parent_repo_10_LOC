#!/usr/bin/env bash
#
# discover-apps.sh - recursively enumerate this project's application entry
# points, including any that live inside a Git submodule at any nesting depth.
#
# PURPOSE
#   The /health feature has to reach every application in the project, so the
#   set of applications must be discoverable mechanically rather than recalled
#   from memory.  This script is that mechanism.  It prints one relative path
#   per entry point, in a deterministic order, so that a person, a CI job and a
#   diff all consume the same list and reach the same conclusion.
#
# SUBMODULES
#   Recursion is the entire answer.  After a recursive checkout a submodule
#   working tree is an ordinary directory on disk, so descending through every
#   subdirectory covers a child submodule, a submodule of a submodule, and any
#   deeper nesting - with no special case and without invoking Git at all.  (A
#   submodule's ".git" is a file rather than a directory; the prune clause below
#   discards either form.)  This project currently contains no submodule, and
#   none is invented here: the coverage is structural, so a submodule added
#   later is picked up with no edit to this script, and the script keeps working
#   on a plain tarball export where no Git metadata exists at all.
#
# USAGE
#   discover-apps.sh [ROOT]
#   discover-apps.sh -h | --help
#
#   ROOT defaults to the repository root, resolved from this script's own
#   location rather than from the caller's working directory, so the same
#   command produces the same list from anywhere on the filesystem.
#
# MATCHED FILE NAMES (exact names, never by extension)
#   app.py   index.js   User.java
#   The test siblings test_app.py, index.test.js and UserTest.java are
#   deliberately not matched: they exercise the applications, they are not
#   applications themselves.
#
# PRUNED DIRECTORIES (never descended into)
#   .git   node_modules   __pycache__   classes   .pytest_cache
#   Version-control metadata, installed dependencies and build output - the
#   places where a copy of an entry point is not a distinct application.  Note
#   that .github is NOT pruned: it holds committed workflow source.
#
# OUTPUT
#   stdout  the sorted path list and nothing else, one path per line, so that
#           `discover-apps.sh > entry-points.txt` yields a clean, diffable file
#   stderr  diagnostics only, and only when something is actually wrong
#   Ordering is byte order under LC_ALL=C, which is locale independent and
#   therefore reproducible on any machine.  One visible consequence is that
#   "User.java" sorts ahead of the lower-case names; that is correct and
#   intentional, not a defect to be tidied away with a case-folding sort.
#   The output is line oriented, so a path containing a newline character
#   cannot be represented; no such path exists in this project.
#
# DELIBERATE BEHAVIOURS AND THEIR LIMITS
#   Each of the four below is a decision rather than an oversight, and each is
#   recorded here so that no reader has to rediscover it from the code and no
#   later change mistakes it for a defect.
#
#   Regular files only.  The search matches -type f, so an entry point that is a
#   SYMLINK is not listed even when it resolves to a real app.py, index.js or
#   User.java.  That is the prescribed behaviour and it is also the safer one: a
#   link can point outside the tree being searched, so following one would let a
#   link inside a submodule enumerate a file that is not part of this project,
#   and a link cycle could otherwise duplicate the output or send the walk
#   running away.  No file in this project is a symlink.  Listing them would be
#   a deliberate widening of the contract - adding -L, or matching -type l as
#   well - and not a bug fix.
#
#   Lexical root resolution.  The default ROOT is the parent of this script's
#   own directory, derived textually from BASH_SOURCE.  Invoked through a
#   SYMLINK to this file that lives outside the repository, the default ROOT is
#   therefore the symlink's own lexical parent rather than the real checkout, and
#   the run reports whatever is (usually nothing) under that directory.
#   Resolving the physical path would mean calling readlink or realpath, and
#   neither is in the four-command requirement list below; the documented
#   invocations - `bash scripts/discover-apps.sh`, `./scripts/discover-apps.sh`,
#   or any path to the real file - are unaffected.  When invoking through a link,
#   pass ROOT explicitly.
#
#   One record per line.  A directory or file name containing a NEWLINE cannot
#   be represented in newline-delimited output and would be read as two records.
#   The line-oriented format is the prescribed one and is precisely what makes
#   the list diffable, greppable and safe to redirect into a file; a
#   NUL-delimited mode would be a different contract, not a hardening of this
#   one.  No such name exists in this project.
#
#   Trust in the invoking environment.  Like every shell script, this one calls
#   find, sed and sort by name, and so inherits whatever the invoking
#   environment has already arranged - a shell function of the same name
#   exported through BASH_ENV, for instance, could substitute its own output.
#   Hardening against that would not buy anything: a caller able to set BASH_ENV
#   in this process already executes arbitrary code, so that trust boundary
#   belongs to the environment, not to this file.
#
# EXIT STATUS
#   0       success, including a legitimately empty result set
#   1       usage error, or ROOT is not a directory
#   other   propagated unchanged from the search itself when find, sed or sort
#           fails - an unreadable directory, for example.  A failure is always
#           re-raised and never swallowed, so a caller can trust a zero status.
#
# REQUIREMENTS
#   bash, find, sed and sort.  Nothing else: no third-party package, no Git
#   command, no network access and no installation step, which keeps the
#   repository's zero-dependency property intact.
#
# READ-ONLY
#   This script creates, modifies, moves and deletes nothing.  It reads
#   directory entries and writes to stdout and stderr, and that is all.
#
set -euo pipefail

# CDPATH would make `cd` echo its destination - polluting any command
# substitution around it - and could resolve a relative ROOT to a directory
# somewhere else entirely.  Clearing it makes every `cd` below predictable.
CDPATH=''

# --- Globals -----------------------------------------------------------------

# The path this script was invoked as.  Empty only when bash read the script
# from standard input, in which case its location cannot be derived and the
# script says so rather than guessing.
SCRIPT_PATH="${BASH_SOURCE[0]:-}"

# Prefix for every diagnostic, taken from the file itself so that it can never
# drift from the real file name.  The fallback keeps diagnostics sane in the
# read-from-stdin case described above.
PROGRAM_NAME="${SCRIPT_PATH##*/}"
: "${PROGRAM_NAME:=discover-apps.sh}"

readonly SCRIPT_PATH PROGRAM_NAME

# Status reported for a caller mistake, kept deliberately distinct from the
# status a failing search propagates.
readonly EXIT_USAGE=1

# Directory to search, and whether the caller supplied it.  Both are set by
# parse_args and read by main.
ROOT=''
ROOT_GIVEN=0

# --- Helpers -----------------------------------------------------------------

# Print the help text on stdout.  A caller that is reporting an error redirects
# it to stderr instead, so stdout stays reserved for the path list.
#
# printf is a shell builtin, which is why it is used here in preference to a
# `cat` heredoc: the documented requirement list stays exactly bash, find, sed
# and sort.
usage() {
  printf '%s\n' \
    "Usage: ${PROGRAM_NAME} [ROOT]" \
    "       ${PROGRAM_NAME} -h | --help" \
    "" \
    "Recursively list this project's application entry points, one relative" \
    "path per line, in a deterministic order.  A Git submodule is covered at" \
    "any nesting depth, because a submodule working tree is an ordinary" \
    "directory on disk." \
    "" \
    "Arguments:" \
    "  ROOT        Directory to search.  Defaults to the repository root," \
    "              resolved from this script's own location, so the result" \
    "              never depends on the current working directory.  Paths are" \
    "              printed relative to ROOT.  That resolution is lexical, so" \
    "              pass ROOT explicitly when invoking this script through a" \
    "              symlink from outside the repository." \
    "" \
    "Options:" \
    "  -h, --help  Print this help on stdout and exit 0." \
    "  --          End of options: the next argument is ROOT even if it" \
    "              begins with a dash." \
    "" \
    "Matched file names (exact names, never by extension):" \
    "  app.py  index.js  User.java" \
    "  Regular files only: a symlink to an entry point is deliberately not" \
    "  listed, and no symlink is ever followed." \
    "" \
    "Pruned directories (never descended into):" \
    "  .git  node_modules  __pycache__  classes  .pytest_cache" \
    "" \
    "Output:" \
    "  stdout carries the sorted path list and nothing else; diagnostics go" \
    "  to stderr.  Ordering is byte order under LC_ALL=C, so it is stable" \
    "  across machines and locales." \
    "" \
    "Exit status:" \
    "  0           Success, including a legitimately empty result set." \
    "  1           Usage error, or ROOT is not a directory." \
    "  other       Propagated from find, sed or sort when the search fails." \
    "" \
    "Examples:" \
    "  bash scripts/${PROGRAM_NAME}" \
    "  bash scripts/${PROGRAM_NAME} > entry-points.txt" \
    "  bash scripts/${PROGRAM_NAME} /path/to/another/checkout"
}

# Report a fatal problem on stderr and stop with the usage status.  The message
# always names the offending value, so the cause is never ambiguous.
die() {
  printf '%s: %s\n' "${PROGRAM_NAME}" "$*" >&2
  exit "${EXIT_USAGE}"
}

# Report a caller mistake, follow it with the help text on stderr, and stop.
die_usage() {
  printf '%s: %s\n' "${PROGRAM_NAME}" "$*" >&2
  usage >&2
  exit "${EXIT_USAGE}"
}

# Parse the command line into the ROOT and ROOT_GIVEN globals.
#
# At most one positional argument is accepted; anything else is a caller
# mistake and fails closed rather than being silently ignored.
parse_args() {
  local end_of_options=0
  local arg

  while [[ "$#" -gt 0 ]]; do
    arg="$1"

    if [[ "${end_of_options}" -eq 0 ]]; then
      case "${arg}" in
        -h | --help)
          usage
          exit 0
          ;;
        --)
          end_of_options=1
          shift
          continue
          ;;
        -?*)
          # A lone "-" is not matched by this pattern, so it stays available as
          # an ordinary (if eccentric) directory name.
          die_usage "unknown option: ${arg}"
          ;;
        *)
          # Anything else is a positional argument.  Saying so explicitly makes
          # the fall-through deliberate rather than merely implied.
          ;;
      esac
    fi

    if [[ "${ROOT_GIVEN}" -ne 0 ]]; then
      die_usage "at most one ROOT argument is accepted; unexpected extra argument: ${arg}"
    fi

    ROOT="${arg}"
    ROOT_GIVEN=1
    shift
  done

  if [[ "${ROOT_GIVEN}" -ne 0 ]] && [[ -z "${ROOT}" ]]; then
    die_usage "ROOT must not be empty"
  fi
}

# Resolve the default ROOT: the parent of the directory holding this script.
#
# Deriving it from the script's own location - never from the caller's working
# directory and never from a Git command - is what makes `bash
# scripts/discover-apps.sh`, `./scripts/discover-apps.sh` and an invocation by
# absolute path from any directory all produce byte-identical output.
resolve_default_root() {
  local script_dir

  if [[ -z "${SCRIPT_PATH}" ]]; then
    die "cannot determine this script's own location; invoke it by path, for example: bash scripts/${PROGRAM_NAME}"
  fi

  # The directory part of the script's path, computed with parameter expansion
  # so that no external tool is required.  Two shapes need care: a path with no
  # slash at all is relative to the current directory, and a path whose only
  # slash is the leading one lives at the filesystem root.
  script_dir="${SCRIPT_PATH%/*}"
  if [[ "${script_dir}" = "${SCRIPT_PATH}" ]]; then
    script_dir='.'
  elif [[ -z "${script_dir}" ]]; then
    script_dir='/'
  fi

  # `cd` and `pwd` are both builtins, and the command substitution runs them in
  # a subshell, so the caller's working directory is left untouched.
  ROOT="$(cd -- "${script_dir}/.." && pwd)" ||
    die "cannot resolve the repository root above: ${script_dir}"
}

# Print every application entry point at or below the current directory, one
# relative path per line, sorted deterministically.
#
# The prune clause MUST come first.  find evaluates the expression left to
# right, so the decision to skip a directory has to be reached before the
# -print clause is considered; swapping the two clauses silently disables the
# pruning.  Symlinks are deliberately left untraversed: no option that would
# make find dereference one is passed, so a symlink loop can neither duplicate
# the output nor send the walk running away.
discover_entry_points() {
  find . \
    \( -name .git -o -name node_modules -o -name __pycache__ -o -name classes -o -name .pytest_cache \) -prune \
    -o -type f \( -name 'app.py' -o -name 'index.js' -o -name 'User.java' \) -print \
    | sed 's|^\./||' \
    | LC_ALL=C sort
}

main() {
  parse_args "$@"

  if [[ "${ROOT_GIVEN}" -eq 0 ]]; then
    resolve_default_root
  fi

  if [[ ! -d "${ROOT}" ]]; then
    die "not a directory: ${ROOT}"
  fi

  # Search from inside ROOT so that every emitted path is relative to it, which
  # is what keeps the output diffable between runs, machines and checkouts.
  cd -- "${ROOT}" || die "cannot enter directory: ${ROOT}"

  # The search status is captured and then re-raised, never discarded.  An
  # empty result set is a success - find prints nothing and exits 0, so the
  # empty case needs no special handling - whereas a real failure such as an
  # unreadable directory must reach the caller.  Nothing anywhere in this
  # script relaxes errexit, forces a zero status, or redirects a diagnostic
  # into oblivion.
  local status=0
  # shellcheck disable=SC2310 # errexit is intentionally bypassed here: the very
  # point of this call is to observe a failure, name the root that failed, and
  # then exit with the status that was observed rather than a substituted one.
  discover_entry_points || status="$?"

  if [[ "${status}" -ne 0 ]]; then
    printf '%s: search failed under: %s\n' "${PROGRAM_NAME}" "${ROOT}" >&2
    exit "${status}"
  fi
}

main "$@"
