"""only_parent_parent_repo_10_LOC - Python application and /health endpoint.

Two things live in this module: the original ``greet`` helper, preserved
verbatim, and the ``/health`` endpoint that reports this application's identity
to a container runtime, an orchestrator or a monitoring agent.

Three invocation modes, dispatched by the guard at the very bottom of the file:

    python3 app.py            legacy default - prints "Hello Lakshya", exits 0
    python3 app.py --serve    binds app.host:python.port, serves GET /health
    python3 app.py --probe    GETs its own /health; exit 0 healthy, 1 otherwise

The default mode is byte-for-byte what it has always been.  The listener is
strictly opt-in, which is how backward compatibility is guaranteed here by
construction rather than by testing after the fact: no existing invocation
reaches any of the new code.

``--probe`` exists because slim and JRE container images ship neither ``curl``
nor ``wget``.  Installing one of them to satisfy a HEALTHCHECK would enlarge
the image, widen its attack surface and hand an attacker a download helper, so
the application checks its own endpoint using the runtime already present.

Only the Python standard library is used, so the repository keeps the
zero-dependency property that makes it trivially portable.

Configuration comes from ``app.config.properties``, the single cross-language
source of truth shared with the JavaScript and Java implementations.  An
environment variable overrides a value in that file, which overrides a built-in
default; the universal ``PORT`` variable outranks everything for the listener
port.  Every value has a working default, so the endpoint still serves when the
properties file is absent - for example from a partial container copy.

Request parsing is owned by this module rather than delegated to
:class:`http.server.BaseHTTPRequestHandler`.  That is a correctness requirement,
not a preference.  The inherited machinery collapses leading slashes in the
request target before any application code sees it, answers an unknown verb with
a 501 HTML page that echoes the verb back to the caller, and - when a request
line cannot be parsed at all - falls back to HTTP/0.9, which suppresses the
status line and the entire header block and emits a bare HTML body.  All three
behaviours contradict the frozen contract and diverge from the JavaScript and
Java implementations, and none of them is reachable from an override of the
individual ``do_*`` methods.  :meth:`HealthRequestHandler.handle_one_request`
therefore reads the request line, validates it, reads the header block, drains
any body and writes the response itself, so that every byte on the wire is
assembled by code in this file.

Public surface:
    greet, read_properties, config_value, load_config, normalize_path,
    strip_authority, health_route, health_timestamp, build_payload,
    render_payload, is_method_token, is_request_target, parse_request_line,
    RequestLine, header_value, contains_token, sanitize_for_log,
    HealthRequestHandler, HealthServer, create_server, serve, probe.

The response contract is frozen and identical across all three language
implementations; see the ``HealthRequestHandler`` docstring for its full text.
"""

import datetime
import json
import os
import socket
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def greet(name):
    return f"Hello {name}"


# --------------------------------------------------------------------------- #
# Configuration
#
# app.config.properties is the single source of truth.  It is resolved relative
# to THIS FILE rather than to the process working directory, so the endpoint
# behaves identically whether it is launched from the repository root, from a
# container WORKDIR, or from a test harness running somewhere else entirely.
# --------------------------------------------------------------------------- #

CONFIG_FILENAME = "app.config.properties"

CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), CONFIG_FILENAME)

#: Built-in fallbacks, used only when neither the environment nor the properties
#: file supplies a value.  ``app.version`` must stay in step with
#: app.config.properties, pyproject.toml, package.json and README.md - the CI
#: version-consistency gate fails the build when those four disagree.
DEFAULTS = {
    "app.name": "only_parent_parent_repo_10_LOC",
    "app.version": "1.1.0",
    "health.path": "/health",
    "app.host": "0.0.0.0",
    "python.port": "8000",
}

#: Properties key -> the environment variable that overrides it.
ENV_OVERRIDES = {
    "app.name": "APP_NAME",
    "app.version": "APP_VERSION",
    "health.path": "HEALTH_PATH",
    "app.host": "APP_HOST",
    "python.port": "PYTHON_PORT",
}

#: The listener port key, and the universal variable that outranks every other
#: source for it.  This follows the twelve-factor convention for a single
#: application per container, where the platform injects PORT and knows nothing
#: about this repository's per-language keys.
PORT_KEY = "python.port"
UNIVERSAL_PORT_ENV = "PORT"

#: Bind addresses that mean "every interface".  A wildcard-bound listener is not
#: reachable at its bind address, so ``probe`` substitutes loopback for these.
WILDCARD_HOSTS = frozenset({"0.0.0.0", "::", "*", ""})
LOOPBACK_HOST = "127.0.0.1"

# --------------------------------------------------------------------------- #
# Frozen response contract
#
# These constants are the contract.  They are named rather than inlined so that
# the handler, the tests and any future consumer all read the same value, and so
# that a change to the contract is a one-line, reviewable change.
# --------------------------------------------------------------------------- #

#: The literal status value the endpoint reports.  ``UP`` is the Java and Spring
#: Boot convention and is an accepted alias in the IETF health-check draft, so
#: this is a standards-recognised value rather than an arbitrary string.
HEALTH_STATUS = "UP"

#: Plain JSON rather than the health-specific media type the IETF health-check
#: draft proposes.  This is the second deliberate deviation from that draft (the
#: first being the 405 answer to HEAD): generic tooling, the shared verification
#: script and a browser all understand ``application/json``, whereas the
#: draft-specific type would be treated as an opaque download by every one of
#: them for no gain in meaning.
CONTENT_TYPE = "application/json"

#: A health response served from a cache is worse than no health response, so
#: caching is refused explicitly rather than left to a heuristic.
CACHE_CONTROL = "no-cache, no-store, must-revalidate"

#: The only method this endpoint serves, and the one whose response carries a
#: Content-Length but no body.  ``ALLOWED_METHODS`` is the value of the ``Allow``
#: header on a 405 and is derived from the dispatch constant rather than repeated,
#: so the header can never advertise a method the dispatcher does not accept.
METHOD_GET = "GET"
METHOD_HEAD = "HEAD"
ALLOWED_METHODS = METHOD_GET

#: Error bodies are deliberately constant.  They never echo the requested path,
#: the method or any traceback: this endpoint is network-reachable and must
#: disclose the minimum possible about the deployment behind it.
NOT_FOUND_BODY = {"error": "Not Found"}
METHOD_NOT_ALLOWED_BODY = {"error": "Method Not Allowed"}

#: The four transport-level rejections, keyed by status code.  A request that
#: never became a routable request still gets a contract response: same media
#: type, same cache directive, same constant-shaped JSON.  These are the only
#: responses that also carry ``Connection: close``, because once a request line
#: or a header block could not be framed, nothing after it on that connection
#: can be framed either.
TRANSPORT_ERROR_BODIES = {
    400: {"error": "Bad Request"},
    414: {"error": "URI Too Long"},
    431: {"error": "Request Header Fields Too Large"},
    505: {"error": "HTTP Version Not Supported"},
}

#: Reason phrases for every status this endpoint can emit.  They are written out
#: rather than taken from :data:`http.server.BaseHTTPRequestHandler.responses` so
#: that the status line is byte-identical to the JavaScript and Java
#: implementations, which hardcode the same phrases.
REASON_PHRASES = {
    200: "OK",
    400: "Bad Request",
    404: "Not Found",
    405: "Method Not Allowed",
    414: "URI Too Long",
    431: "Request Header Fields Too Large",
    500: "Internal Server Error",
    505: "HTTP Version Not Supported",
}

#: Emitted verbatim as the status line of every response.  The listener speaks
#: HTTP/1.1 unconditionally, including when it is rejecting an HTTP/1.0 or an
#: unparseable request: a response must be framed in a version the client can
#: read, and every HTTP/1.x client can read this one.
HTTP_VERSION = "HTTP/1.1"

#: Line terminator for the status line and every header field.
CRLF = "\r\n"

#: The interim response to ``Expect: 100-continue``.  Sent before the body is
#: drained, because a client that is waiting for it will not send the body
#: otherwise and the exchange would stall until the idle timeout fired.
CONTINUE_RESPONSE = f"{HTTP_VERSION} 100 Continue{CRLF}{CRLF}"

#: How long ``probe`` waits for the endpoint to answer.  Short enough that a
#: container HEALTHCHECK timeout fires on the probe's own verdict rather than on
#: the runtime killing it mid-request.
PROBE_TIMEOUT_SECONDS = 3.0

#: How long a connection may stay idle before the handler closes it.  Without a
#: timeout a client that connects and never sends a request line would pin a
#: worker thread for the lifetime of the process.
CONNECTION_TIMEOUT_SECONDS = 30

#: How long a retiring connection is drained for.  See
#: :meth:`HealthRequestHandler.finish`.
LINGER_TIMEOUT_SECONDS = 1.0

#: Read/discard buffer size for body and linger draining.
IO_BUFFER_BYTES = 8192

#: Largest request line this listener will read, in bytes.  A longer one is a
#: 414: the target cannot be evaluated, so it is refused rather than truncated
#: and misrouted.
MAX_REQUEST_LINE_BYTES = 65536

#: Largest single header field line, in bytes.
MAX_HEADER_LINE_BYTES = 16384

#: Largest total header block, in bytes, and the largest number of fields in it.
#: Both are 431.  Without a ceiling a client could stream header fields forever
#: and hold a worker thread and its memory for as long as it liked.
MAX_HEADER_BLOCK_BYTES = 16384
MAX_HEADER_FIELDS = 100

#: Largest request body this listener will read and discard.  Nothing this
#: endpoint answers depends on a body, but a body left unread desynchronises a
#: persistent connection, and closing a socket with unread data queued makes the
#: kernel answer with a reset - which is what turns an answered request into
#: "no response" at the client.  Draining is part of answering correctly.  A body
#: larger than this is still answered; the connection is simply retired
#: afterwards rather than drained to the end.
MAX_REQUEST_DRAIN_BYTES = 8 * 1024 * 1024
MAX_CHUNKED_BODY_BYTES = MAX_REQUEST_DRAIN_BYTES

#: Largest number of trailer fields accepted after a chunked body, and how many
#: bytes a retiring connection is drained for before it is simply closed.
MAX_TRAILER_FIELDS = MAX_HEADER_FIELDS
MAX_LINGER_DRAIN_BYTES = 1024 * 1024

#: Base of a chunked-body size line.
CHUNK_SIZE_RADIX = 16

#: Exactly the three tokens a request line must carry: method, target, version.
REQUEST_LINE_TOKENS = 3

#: The punctuation RFC 9110 allows in a method token, alongside ALPHA and DIGIT.
#: This is what makes an unknown or wrongly-cased verb a 405 rather than a parse
#: failure - ``FOO``, ``GETX``, ``get`` and ``Get`` are all legal tokens.
METHOD_TOKEN_SPECIALS = "!#$%&'*+-.^_`|~"

#: A request target must be visible US-ASCII, end to end.  That excludes the
#: space, every control character including CR, LF and TAB, DEL, and every byte
#: above the ASCII range.  A tab inside a target is therefore a 400 rather than
#: something the router has to reason about, and a CR or LF can never reach the
#: router at all - which is what makes response-header injection through the
#: target structurally impossible.
TARGET_MIN_CHAR = 0x21
TARGET_MAX_CHAR = 0x7E

#: Separator between the scheme and the authority of an absolute-form target.
SCHEME_SEPARATOR = "://"

#: Request header field names this listener reads, already lowercased.
HEADER_NAME_HOST = "host"
HEADER_NAME_CONTENT_LENGTH = "content-length"
HEADER_NAME_TRANSFER_ENCODING = "transfer-encoding"
HEADER_NAME_CONNECTION = "connection"
HEADER_NAME_EXPECT = "expect"

#: Header values this listener acts on.
TRANSFER_ENCODING_CHUNKED = "chunked"
CONNECTION_CLOSE = "close"
CONNECTION_KEEP_ALIVE = "keep-alive"
EXPECT_CONTINUE = "100-continue"

#: The route every configured health path reduces to when it is empty.
ROOT_PATH = "/"


def read_properties(path=None):
    """Parse a Java-native ``key=value`` properties file into a ``dict``.

    Blank lines are skipped, as are comment lines whose first non-space
    character is ``#`` or ``!``.  A line is split on its FIRST ``=`` only, so a
    value may itself contain that character, and both halves are stripped of
    surrounding whitespace.  Values are never unquoted and never have a trailing
    comment removed, because ``java.util.Properties`` does neither - keeping the
    three language implementations in agreement about what the file says.

    A missing file is not an error: the caller falls back to the environment and
    to the built-in defaults, so the endpoint still serves from an image that
    copied only the application source.  Any other failure is reported on stderr
    - a corrupt configuration file should not be invisible - and also degrades
    to the defaults rather than taking the process down.

    :param path: file to read; defaults to :data:`CONFIG_PATH`.
    :returns: the parsed keys, or an empty dict when the file cannot be read.
    """
    resolved = CONFIG_PATH if path is None else path
    properties = {}
    try:
        with open(resolved, encoding="utf-8") as handle:
            for raw_line in handle:
                line = raw_line.strip()
                if not line or line[0] in ("#", "!") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                properties[key.strip()] = value.strip()
    except FileNotFoundError:
        # Expected and silent: defaults cover every key.
        return {}
    except (OSError, UnicodeDecodeError) as exc:
        sys.stderr.write(f"[app.py] cannot read {resolved}: {exc}; using defaults\n")
        return {}
    return properties


def config_value(key, env_name=None, default=None, props=None, env=None):
    """Resolve one configuration key.

    Precedence is fixed: environment variable, then the properties file, then
    the built-in default.  For the listener port the universal ``PORT`` variable
    is consulted first of all.

    An empty value is treated as absent at every level.  A caller that exports
    ``APP_NAME=""`` gets the configured name rather than an empty ``name`` field,
    which the response contract forbids.

    ``env`` and ``props`` are injectable so that tests can assert the precedence
    order against a fake environment mapping and a temporary file without
    mutating :data:`os.environ` for the whole interpreter.

    :param key: properties key, e.g. ``app.version``.
    :param env_name: overriding variable; looked up in :data:`ENV_OVERRIDES`
        when omitted.
    :param default: fallback; taken from :data:`DEFAULTS` when omitted.
    :param props: pre-parsed properties; read from disk when omitted.
    :param env: environment mapping; :data:`os.environ` when omitted.
    :returns: the resolved value as a string, or ``None`` if the key is unknown
        and no default was supplied.
    """
    environ = os.environ if env is None else env
    properties = read_properties() if props is None else props
    if env_name is None:
        env_name = ENV_OVERRIDES.get(key)
    if default is None:
        default = DEFAULTS.get(key)

    if key == PORT_KEY:
        universal = environ.get(UNIVERSAL_PORT_ENV)
        if universal:
            return universal
    if env_name:
        override = environ.get(env_name)
        if override:
            return override
    value = properties.get(key)
    if value:
        return value
    return default


def load_config(path=None, env=None):
    """Resolve every configuration key in one pass.

    The properties file is read exactly once here and shared across the keys,
    which keeps a request that needs several values to a single filesystem
    touch.  The returned mapping is keyed by properties key, so it can be handed
    straight to :func:`build_payload`, :func:`create_server` or :func:`probe`.

    :param path: properties file to read; defaults to :data:`CONFIG_PATH`.
    :param env: environment mapping; :data:`os.environ` when omitted.
    :returns: ``dict`` with a value for every key in :data:`DEFAULTS`.
    """
    environ = os.environ if env is None else env
    properties = read_properties(path)
    return {
        key: config_value(key, ENV_OVERRIDES.get(key), default, props=properties, env=environ)
        for key, default in DEFAULTS.items()
    }


def _as_port(value):
    """Coerce a configured port to a valid TCP port number.

    Configuration arrives as text, so a typo must fail with a message that names
    the offending value instead of surfacing as an opaque error from deep inside
    the socket layer.  Port 0 is permitted: it is how a test binds an ephemeral
    port and then reads the assignment back from the server.

    :raises ValueError: when the value is not an integer in 0-65535.
    """
    try:
        port = int(str(value).strip())
    except (TypeError, ValueError):
        raise ValueError(f"invalid port {value!r}: expected an integer") from None
    if not 0 <= port <= 65535:
        raise ValueError(f"invalid port {port}: outside the range 0-65535")
    return port


def normalize_path(target):
    """Reduce a request target to the path this endpoint routes on.

    Four transformations, in this order: an absolute-form target is reduced to
    its path component, the query string is discarded, the fragment is discarded,
    and at most ONE trailing slash is removed.  ``/health``, ``/health/``,
    ``/health?probe=1``, ``/health/?probe=1``, ``/health#frag`` and
    ``http://host/health`` therefore all resolve to ``/health``, while
    ``/health//`` deliberately does not - one forgiving slash is a convenience,
    two is a different path.

    Three transformations are deliberately NOT performed, and their absence is
    the contract rather than an omission:

    * no percent-decoding, so ``/health%2f`` is a different path from
      ``/health/`` and answers 404;
    * no dot-segment resolution, so ``/health/../health`` answers 404;
    * no collapsing of leading slashes, so ``//health`` and ``///health`` answer
      404.

    Each of those would let a caller reach the endpoint by a name that is not the
    configured one, which makes the route ambiguous for anything downstream that
    matches on a path - a proxy rule, an access log, a rate limiter.  The
    JavaScript and Java implementations perform exactly the same four
    transformations and refuse exactly the same three, so all three agree on
    every one of these targets.

    This is written out rather than delegated to :func:`urllib.parse.urlsplit`
    because that function is a URI parser, not a request-target router: it reads
    ``//health`` as an authority with an empty path and ``///health`` as an empty
    authority followed by ``/health``, which would make the second of those match
    the route.  The stdlib request handler compounds that by rewriting ``//x`` to
    ``/x`` inside ``parse_request`` before any application code runs, which is
    why :meth:`HealthRequestHandler.handle_one_request` never calls it.
    """
    if not target:
        return ROOT_PATH
    path = strip_authority(target)
    query = path.find("?")
    if query >= 0:
        path = path[:query]
    fragment = path.find("#")
    if fragment >= 0:
        path = path[:fragment]
    if len(path) > 1 and path.endswith(ROOT_PATH):
        path = path[:-1]
    return path or ROOT_PATH


def strip_authority(target):
    """Reduce an absolute-form request target to its path component.

    RFC 9112 permits ``GET http://host/health HTTP/1.1`` on any request, not only
    to a proxy, and an origin server is required to accept it.  The scheme is
    validated before anything is stripped, so a relative target whose query
    string happens to contain ``://`` - a redirect parameter, for instance - is
    left completely alone.

    :param target: the raw request target.
    :returns: the path component of an absolute-form target, or the target
        unchanged when it is not in absolute form.
    """
    separator = target.find(SCHEME_SEPARATOR)
    if separator <= 0 or not _is_scheme(target[:separator]):
        return target
    authority_start = separator + len(SCHEME_SEPARATOR)
    path_start = target.find("/", authority_start)
    return ROOT_PATH if path_start < 0 else target[path_start:]


def _is_scheme(candidate):
    """Report whether a string is a URI scheme as RFC 3986 defines one.

    ALPHA followed by any number of ALPHA, DIGIT, ``+``, ``-`` or ``.``.  The
    character classes are spelled out against the ASCII range rather than taken
    from :meth:`str.isalpha`, which is true for non-ASCII letters and would make
    a target containing a non-ASCII scheme-like prefix strip differently here
    than it does in the other two implementations.
    """
    if not candidate or not _is_ascii_letter(candidate[0]):
        return False
    for current in candidate[1:]:
        if not (_is_ascii_letter(current) or _is_ascii_digit(current) or current in "+-."):
            return False
    return True


def _is_ascii_letter(current):
    """Return ``True`` for A-Z and a-z only, never for a non-ASCII letter."""
    return ("a" <= current <= "z") or ("A" <= current <= "Z")


def _is_ascii_digit(current):
    """Return ``True`` for 0-9 only, never for a non-ASCII digit."""
    return "0" <= current <= "9"


def health_route(config=None):
    """Return the normalised route the endpoint answers on.

    A leading slash is supplied when the configured value omits one, so
    ``health.path`` values of ``/health``, ``health`` and ``/health/`` all
    describe the same route.  Used for matching in the handler and for building
    the URL in :func:`probe`, which guarantees the two cannot disagree.
    """
    resolved = load_config() if config is None else config
    path = resolved.get("health.path") or DEFAULTS["health.path"]
    if not path.startswith("/"):
        path = "/" + path
    return normalize_path(path)


def health_timestamp():
    """Return the current UTC instant, truncated to whole seconds, ``...Z``.

    Fixed width, 20 characters, e.g. ``2026-07-28T13:47:08Z``: an RFC 3339
    instant with the zone rendered as ``Z`` rather than ``+00:00`` so that all
    three language implementations emit an identically shaped field.

    This is the only non-deterministic value in the payload and therefore the
    only one that must be asserted by FORMAT and never by value - an assertion
    on the value would make a gate fail for a reason unrelated to correctness.
    """
    return (
        datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def build_payload(config=None):
    """Build the frozen four-key health document.

    Insertion order IS the wire order - ``dict`` preserves it and the renderer
    does not sort - so the keys are emitted as ``name``, ``version``,
    ``timestamp``, ``status``.  Nothing beyond these four fields is included:
    on a network-reachable surface every extra field is disclosure, and the
    contract is frozen so that monitoring tools can depend on its shape.

    :param config: mapping from :func:`load_config`; loaded when omitted.
    """
    resolved = load_config() if config is None else config
    return {
        "name": resolved.get("app.name") or DEFAULTS["app.name"],
        "version": resolved.get("app.version") or DEFAULTS["app.version"],
        "timestamp": health_timestamp(),
        "status": HEALTH_STATUS,
    }


def render_payload(payload):
    """Serialise a payload as compact JSON.

    Two arguments here are load-bearing rather than cosmetic, and both exist to
    hold byte parity with the JavaScript and Java implementations - parity that
    is part of the contract, because a ``Content-Length`` that differs between
    two implementations of the same endpoint means they are not the same
    endpoint.

    ``separators``: the default inserts a space after every ``:`` and ``,``,
    which would make this body 115 bytes where the other two produce 108.

    ``ensure_ascii=False``: the default escapes every non-ASCII character as
    ``\\uXXXX``, so a configured name of ``h\u00e9llo`` would be serialised as
    ``h\\u00e9llo`` - seven bytes where ``JSON.stringify`` and the Java renderer
    both emit the two UTF-8 bytes of the character itself.  Measured on the
    shared configuration, the difference is a 119-byte body here against a
    102-byte body in the other two.  Turning it off makes all three agree; the
    result is still valid JSON, because RFC 8259 defines JSON text as UTF-8 and
    escaping non-ASCII is optional.  Control characters, the quotation mark and
    the backslash are still escaped, by the same short forms the other two use.

    Keys are never sorted, because insertion order is the specified field order.
    """
    return json.dumps(payload, separators=(",", ":"), ensure_ascii=False)


# --------------------------------------------------------------------------- #
# Request line parsing
#
# This section is the reason this module does not use the stdlib request parser.
# Every rule below is stated identically in the JavaScript and Java
# implementations, and the three are verified against one shared matrix of
# request lines, so a divergence here is a defect rather than a dialect.
# --------------------------------------------------------------------------- #


class RequestLine:
    """One parsed request line, or the status code that rejects it.

    ``failure`` is ``0`` for a line that may be routed and otherwise the status
    to answer with.  ``method`` and ``target`` are the tokens exactly as sent -
    never decoded, never normalised - so that routing decisions are made on what
    the client actually wrote.
    """

    __slots__ = ("method", "target", "major", "minor", "failure")

    def __init__(self, method="", target="", major=0, minor=0, failure=0):
        self.method = method
        self.target = target
        self.major = major
        self.minor = minor
        self.failure = failure

    @property
    def persistent(self):
        """Return ``True`` when the client may reuse the connection by default.

        True from HTTP/1.1 onwards.  This also decides whether a missing ``Host``
        header is fatal: RFC 9112 requires one from 1.1, and permits its absence
        in 1.0.
        """
        return self.major > 1 or (self.major == 1 and self.minor >= 1)

    def __repr__(self):
        return (
            f"RequestLine(method={self.method!r}, target={self.target!r}, "
            f"major={self.major}, minor={self.minor}, failure={self.failure})"
        )


def is_method_token(candidate):
    """Report whether a string is a method token as RFC 9110 defines one.

    This is what makes an unknown or wrongly-cased verb a 405 rather than a parse
    failure: ``FOO``, ``GETX``, ``get`` and ``Get`` are all valid tokens, so all
    four are routed and all four are answered 405 with ``Allow: GET``, exactly as
    ``TRACE`` and ``PROPFIND`` are.  A first token that is not a token at all -
    ``<script>alert(1)</script>``, say - is a malformed request line and answers
    400, because there is no method there to refuse.
    """
    if not candidate:
        return False
    for current in candidate:
        if not (
            _is_ascii_letter(current)
            or _is_ascii_digit(current)
            or current in METHOD_TOKEN_SPECIALS
        ):
            return False
    return True


def is_request_target(candidate):
    """Report whether a request target is made only of characters allowed in one.

    Every character must be visible US-ASCII, ``0x21`` through ``0x7E``.  See
    :data:`TARGET_MIN_CHAR` for why that range and not a wider one.
    """
    if not candidate:
        return False
    for current in candidate:
        if not TARGET_MIN_CHAR <= ord(current) <= TARGET_MAX_CHAR:
            return False
    return True


def parse_request_line(line):
    """Parse and validate a request line against RFC 9112.

    The line must be exactly three single-space separated tokens; the method must
    be a token; the target must be non-empty and made only of visible US-ASCII;
    and the version must be ``HTTP/``, digits, ``.``, digits.  Anything else is a
    400.  A well-formed version whose major is not 1 is a 505 - the request was
    understood, it simply cannot be served.

    :param line: the request line with its terminator already removed.
    :returns: a :class:`RequestLine`, rejected or routable.
    """
    tokens = line.split(" ")
    if len(tokens) != REQUEST_LINE_TOKENS:
        return RequestLine(failure=400)
    method, target, version = tokens
    if not is_method_token(method) or not is_request_target(target):
        return RequestLine(failure=400)
    if not version.startswith("HTTP/"):
        return RequestLine(failure=400)
    number = version[len("HTTP/"):]
    dot = number.find(".")
    if dot <= 0 or dot == len(number) - 1:
        return RequestLine(failure=400)
    major_text, minor_text = number[:dot], number[dot + 1:]
    if not _is_digits(major_text) or not _is_digits(minor_text):
        return RequestLine(failure=400)
    major, minor = _bounded_number(major_text), _bounded_number(minor_text)
    if major != 1:
        return RequestLine(method, target, major, minor, 505)
    return RequestLine(method, target, major, minor, 0)


def _is_digits(candidate):
    """Return ``True`` if every character is an ASCII digit and there is one."""
    if not candidate:
        return False
    for current in candidate:
        if not _is_ascii_digit(current):
            return False
    return True


def _bounded_number(digits):
    """Parse a run of ASCII digits without letting an absurd one dominate.

    A version number of a thousand digits is legal input to reject, not a reason
    to allocate an arbitrary-precision integer, so anything wider than a machine
    word saturates.  The value is only ever compared against 1.
    """
    if len(digits) > 9:
        return sys.maxsize
    return int(digits)


def header_value(header_lines, name):
    """Return the first value of a header field, or ``None``.

    ``name`` must already be lowercase.  Field names are case-insensitive per RFC
    9110, so the comparison is made on a lowercased copy of what arrived.  The
    FIRST match wins: a request carrying two ``Host`` headers is answered from
    the first rather than rejected, which is what the JavaScript implementation's
    parser does, and rejecting here would create a divergence between the two for
    no gain.

    :param header_lines: the header block, one raw line per field, in order.
    :param name: lowercase field name to look for.
    """
    for field in header_lines:
        colon = field.find(":")
        if colon <= 0:
            # No name, or an obs-fold continuation line: nothing to match on.
            continue
        if field[:colon].strip().lower() == name:
            return field[colon + 1:].strip()
    return None


def contains_token(value, token):
    """Report whether a comma-separated header value contains a token.

    ``Connection: keep-alive, Upgrade`` contains ``keep-alive``; the substring
    test that would also be true of ``Connection: no-keep-alive`` is not used.
    """
    if not value:
        return False
    for part in value.split(","):
        if part.strip().lower() == token:
            return True
    return False


def sanitize_for_log(text):
    """Strip control characters from text before it reaches a log line.

    Configuration is an input, and this module writes configured values - the
    resolved route, the bind address - to stderr at startup.  A value carrying a
    CR or an LF would forge additional log lines, and one carrying a terminal
    escape sequence would rewrite what an operator sees.  Every character below
    ``0x20`` and DEL is therefore dropped rather than escaped: this text exists
    to be read by a human, and a stripped character reads better than an escape.

    :param text: the value to make safe for a single log line.
    :returns: the value with every control character removed.
    """
    if not text:
        return ""
    return "".join(
        current for current in text if ord(current) >= 0x20 and ord(current) != 0x7F
    )


# --------------------------------------------------------------------------- #
# HTTP surface
# --------------------------------------------------------------------------- #


class HealthRequestHandler(BaseHTTPRequestHandler):
    """Serve the frozen health contract, and nothing else.

    ================  ==========================================================
    Route             the configured health path, default ``/health``; query
                      string and fragment stripped, one optional trailing slash
                      accepted, absolute-form accepted
    Method            GET only
    Success           ``200`` with the compact four-key JSON document
    Headers           ``Content-Type: application/json``,
                      ``Cache-Control: no-cache, no-store, must-revalidate``,
                      ``Content-Length``; no ``Server``, no ``Date`` and no
                      transport header on a contract response
    Unknown path      ``404`` with ``{"error":"Not Found"}``
    Other methods     ``405`` with ``{"error":"Method Not Allowed"}`` and
                      ``Allow: GET``
    Malformed line    ``400`` with ``{"error":"Bad Request"}``
    Oversized line    ``414`` with ``{"error":"URI Too Long"}``
    Oversized head    ``431`` with ``{"error":"Request Header Fields Too Large"}``
    Other version     ``505`` with ``{"error":"HTTP Version Not Supported"}``
    ================  ==========================================================

    Request classification order, applied exactly as written, identically in all
    three language implementations:

    1. at most ONE leading empty line is skipped, per RFC 9112 - a client that
       terminated its previous request with a spare CRLF is common and harmless;
       a second empty line is a malformed request line;
    2. a request line longer than :data:`MAX_REQUEST_LINE_BYTES` is a 414;
    3. the line must be ``METHOD SP target SP HTTP/d.d`` with a token method and
       a visible-US-ASCII target, or it is a 400;
    4. a well-formed version whose major is not 1 is a 505;
    5. a header block over :data:`MAX_HEADER_BLOCK_BYTES`, a field line over
       :data:`MAX_HEADER_LINE_BYTES` or more than :data:`MAX_HEADER_FIELDS`
       fields is a 431; a stream that ends inside the block is a 400;
    6. an HTTP/1.1 request with no ``Host`` header is a 400, checked BEFORE the
       method so that a malformed request is never answered as a routing
       decision;
    7. a method other than ``GET`` is a 405 with ``Allow: GET``;
    8. the target is normalised by :func:`normalize_path` and compared with the
       configured route: equal is a 200, anything else a 404.

    Three design points are worth stating explicitly.

    A health response must never be served from a cache: a cached "healthy" is
    worse than no answer at all, hence the unconditional no-store directive on
    every response including the error paths.

    HEAD is answered with 405 rather than being supported.  RFC 9110 expects
    HEAD support wherever GET is supported, so this is a deliberate, documented
    deviation: the contract is frozen identically across three independent
    language implementations and no identified consumer of this endpoint issues
    HEAD.  The practical consequence is that response headers must be inspected
    with a GET that discards the body, never with ``curl -I``.  The 405 answer to
    HEAD is still a well-formed HEAD response - status and headers only, no body
    - because refusing a method is no excuse for corrupting the connection.

    A contract response carries no ``Connection`` header even when the connection
    is about to be closed.  RFC 9110 makes that a SHOULD rather than a MUST, and
    the deviation is deliberate: the header-name set of a 200, a 404 and a 405 is
    asserted to be identical across all three implementations, and the JavaScript
    runtime cannot be made to emit a ``Connection`` header only on some contract
    responses.  Holding all three to exactly three header fields is worth more
    than an advisory hint the client can infer from the FIN that follows.  The
    four transport rejections do carry it, because there the client genuinely
    cannot know whether anything it sends next will be read.
    """

    #: Keep-alive is required for correctness here, not performance: an accurate
    #: Content-Length accompanies every response, so a client may reuse the
    #: connection instead of paying a fresh handshake per poll.
    protocol_version = "HTTP/1.1"

    #: Idle-connection ceiling; see CONNECTION_TIMEOUT_SECONDS.
    timeout = CONNECTION_TIMEOUT_SECONDS

    #: Set TCP_NODELAY on the accepted connection.  Every response this class
    #: writes is assembled in full and sent as ONE socket write, so the header
    #: block and the body can never be split across two segments and the
    #: delayed-ACK interaction that made a two-write response cost a median
    #: 41.00 ms against a 0.83 ms floor cannot arise.  The switch is kept
    #: because it also covers the interim ``100 Continue`` response, which is by
    #: definition a small write followed by more traffic on the same connection,
    #: and because a health endpoint exists to be polled: there is no batching
    #: to gain and a whole round trip to lose.
    disable_nagle_algorithm = True

    #: Least disclosure.  No response emitted by this class carries a Server
    #: header at all, but these two attributes feed ``version_string()``, which
    #: inherited machinery could still reach, so the interpreter version is
    #: removed at the source as well as at every call site.
    server_version = "health"
    sys_version = ""

    #: Set by :meth:`_write` when the response could not reach the client.  A
    #: connection whose response was not delivered is never reused.
    write_failed = False

    def _snapshot(self):
        """Return the configuration snapshot and route this server was built on.

        Configuration is resolved ONCE, when the server is constructed, and every
        response is served from that snapshot.  A listener that re-read its
        configuration per request would answer two concurrent polls with two
        different documents while a deployment was mid-write, and the JavaScript
        and Java implementations both snapshot at construction, so doing anything
        else here would make the three disagree about what a health response even
        means.  Reloading is what a restart is for.

        The fallback exists for a handler mounted on a plain
        :class:`http.server.HTTPServer` rather than on :class:`HealthServer` -
        legitimate in a test - and resolves configuration the only way it can.
        """
        config = getattr(self.server, "health_config", None)
        if config is None:
            config = load_config()
        route = getattr(self.server, "health_route", None)
        if route is None:
            route = health_route(config)
        return config, route

    # ----------------------------------------------------------------------- #
    # Writing responses
    # ----------------------------------------------------------------------- #

    def _write(self, status, body, send_allow=False, send_close=False):
        """Write one complete response as a single socket write.

        Every byte this class puts on the wire is assembled here, which is the
        whole point of owning the socket: the three contract headers appear in a
        fixed order with fixed casing and nothing is added behind this method's
        back - no ``Date``, no ``Server``, no transport header on a contract
        response.  None of the inherited ``send_response``/``send_header``/
        ``end_headers`` machinery is used, so none of its behaviour - the
        interpreter version banner, the ``Date`` header, or the HTTP/0.9 fallback
        that silently suppresses the status line and the whole header block when a
        request line could not be parsed - can reach a client.

        ``Content-Length`` is the encoded byte length and never the character
        count, so a multi-byte character in a configured value cannot
        desynchronise the length from the body.  The head is encoded as
        ISO-8859-1 and every value placed in it is a module constant or a number:
        no request-supplied text reaches it, which is what makes header injection
        through the request line structurally impossible.

        RFC 9110 section 9.3.2: a response to HEAD carries no message body.
        ``Content-Length`` still advertises what the equivalent GET would return,
        which the specification explicitly permits and which keeps the header set
        identical to the GET response.  This is not a nicety - connections
        persist, so writing a body here would leave those bytes in the stream for
        the client to parse as the start of the NEXT response.

        :param status: the status code, which must be in :data:`REASON_PHRASES`.
        :param body: the complete response body, already compact JSON.
        :param send_allow: add ``Allow: GET``.
        :param send_close: add ``Connection: close``.
        """
        encoded = body.encode("utf-8")
        head = [
            f"{HTTP_VERSION} {status} {REASON_PHRASES[status]}",
            f"Content-Type: {CONTENT_TYPE}",
            f"Cache-Control: {CACHE_CONTROL}",
            f"Content-Length: {len(encoded)}",
        ]
        if send_allow:
            head.append(f"Allow: {ALLOWED_METHODS}")
        if send_close:
            head.append(f"Connection: {CONNECTION_CLOSE}")
        response = (CRLF.join(head) + CRLF + CRLF).encode("iso-8859-1")
        if self.command != METHOD_HEAD:
            response += encoded
        try:
            self.wfile.write(response)
        except OSError as exc:
            # A monitoring agent that times out and hangs up mid-response is
            # routine, not exceptional.  Note it on stderr and retire the
            # connection rather than letting a traceback reach the log.
            self.write_failed = True
            self.close_connection = True
            self.log_error("client closed the connection: %r", exc)

    def _respond(self, code, payload, send_allow=False):
        """Serialise a payload and write it as a contract response.

        Kept as the single entry point for the 200, 404 and 405 paths so that the
        body format and the header set cannot drift between them.
        """
        self._write(code, render_payload(payload), send_allow=send_allow)

    def _transport_error(self, status):
        """Write one of the four transport rejections and give up the connection.

        These are the only responses that carry ``Connection: close``: once a
        request line or a header block could not be framed, whatever follows it on
        the connection cannot be framed either, so continuing to read would be
        guessing.
        """
        self._write(status, render_payload(TRANSPORT_ERROR_BODIES[status]), send_close=True)
        self.close_connection = True

    # ----------------------------------------------------------------------- #
    # Reading requests
    # ----------------------------------------------------------------------- #

    def _read_line(self, limit):
        """Read one bounded line without decoding it as anything but bytes.

        Bytes are mapped to characters one for one through ISO-8859-1 rather than
        decoded as UTF-8.  A request line and a header field are ASCII by
        specification and any byte outside that range is rejected rather than
        interpreted, so a lossless byte-preserving mapping is exactly what is
        wanted: no replacement character can silently turn an illegal request into
        a legal one.

        A bare LF terminator is accepted as well as CRLF, matching what the other
        two implementations accept.

        :param limit: the largest number of bytes this line may occupy.
        :returns: ``(text, truncated, end_of_stream)``.  ``truncated`` means the
            cap was reached before any terminator; ``end_of_stream`` means the
            client closed the connection without sending a single byte of it.
        """
        raw = self.rfile.readline(limit + 1)
        if not raw:
            return "", False, True
        if raw.endswith(b"\n"):
            raw = raw[:-1]
            if raw.endswith(b"\r"):
                raw = raw[:-1]
            return raw.decode("iso-8859-1"), False, False
        # No terminator: either the cap was hit, or the client stopped mid-line.
        return raw.decode("iso-8859-1"), len(raw) > limit, False

    def _read_headers(self):
        """Read the header block that follows a request line.

        :returns: ``(header_lines, failure)`` where ``failure`` is ``0`` when the
            whole block arrived, 431 when a size cap was hit, or 400 when the
            client stopped in the middle of its own header block - that request is
            incomplete rather than oversized, and the two must not be conflated.
        """
        headers = []
        consumed = 0
        while True:
            text, truncated, end_of_stream = self._read_line(MAX_HEADER_LINE_BYTES)
            if truncated:
                return headers, 431
            if end_of_stream:
                return headers, 400
            if not text:
                return headers, 0
            consumed += len(text) + len(CRLF)
            if consumed > MAX_HEADER_BLOCK_BYTES or len(headers) >= MAX_HEADER_FIELDS:
                return headers, 431
            headers.append(text)

    def _drain_body(self, content_length, transfer_encoding):
        """Consume a request body so that the connection can be reused.

        See :data:`MAX_REQUEST_DRAIN_BYTES` for why an endpoint that ignores
        bodies must nevertheless read them.

        :returns: ``True`` when the body was consumed in full and the connection
            may carry another request, ``False`` when the connection must be
            retired - an unparseable, negative or oversized length, an encoding
            this listener will not frame, or a client that stopped early.
        """
        if transfer_encoding:
            return contains_token(transfer_encoding, TRANSFER_ENCODING_CHUNKED) and (
                self._drain_chunked()
            )
        if not content_length:
            return True
        try:
            declared = int(content_length.strip())
        except ValueError:
            return False
        if declared < 0 or declared > MAX_REQUEST_DRAIN_BYTES:
            return False
        return self._skip_exactly(declared)

    def _drain_chunked(self):
        """Discard a chunked request body, including its trailer section."""
        total = 0
        while True:
            text, truncated, end_of_stream = self._read_line(MAX_HEADER_LINE_BYTES)
            if truncated or end_of_stream:
                return False
            extension = text.find(";")
            if extension >= 0:
                text = text[:extension]
            try:
                chunk = int(text.strip(), CHUNK_SIZE_RADIX)
            except ValueError:
                return False
            if chunk < 0:
                return False
            if chunk == 0:
                return self._skip_trailer()
            total += chunk
            if total > MAX_CHUNKED_BODY_BYTES or not self._skip_exactly(chunk):
                return False
            terminator, truncated, end_of_stream = self._read_line(MAX_HEADER_LINE_BYTES)
            if terminator or truncated or end_of_stream:
                return False

    def _skip_trailer(self):
        """Discard the trailer section that closes a chunked body."""
        for _ in range(MAX_TRAILER_FIELDS + 1):
            text, truncated, end_of_stream = self._read_line(MAX_HEADER_LINE_BYTES)
            if truncated or end_of_stream:
                return False
            if not text:
                return True
        return False

    def _skip_exactly(self, count):
        """Read and discard an exact number of bytes.

        :returns: ``True`` if every byte arrived, ``False`` if the stream ended
            first.
        """
        remaining = count
        while remaining > 0:
            chunk = self.rfile.read(min(remaining, IO_BUFFER_BYTES))
            if not chunk:
                return False
            remaining -= len(chunk)
        return True

    # ----------------------------------------------------------------------- #
    # Dispatch
    # ----------------------------------------------------------------------- #

    def handle_one_request(self):
        """Read one request, classify it and write exactly one response.

        This replaces the inherited implementation rather than extending it.  See
        the module docstring for why: the inherited ``parse_request`` rewrites the
        request target, answers an unknown verb with an HTML page that echoes the
        verb, and degrades to HTTP/0.9 - no status line, no headers - for a
        request line it cannot parse.  Those are not behaviours an override of a
        ``do_*`` method can reach.

        The classification order is the numbered list on this class and is
        deliberately identical, step for step, to the JavaScript and Java
        implementations.  Nothing about the request - not the target, not the
        method, not an exception message - is ever placed in a response.
        """
        self.close_connection = True
        self.write_failed = False
        self.requestline = ""
        self.request_version = ""
        self.command = ""
        self.path = ""
        try:
            text, truncated, end_of_stream = self._read_line(MAX_REQUEST_LINE_BYTES)
            if end_of_stream:
                return
            if not truncated and not text:
                # RFC 9112 asks a server to tolerate one empty line before a
                # request line.  Exactly one is skipped; a second is a 400.
                text, truncated, end_of_stream = self._read_line(MAX_REQUEST_LINE_BYTES)
                if end_of_stream:
                    return
            if truncated:
                self._transport_error(414)
                return
            self.requestline = text
            request = parse_request_line(text)
            if request.failure:
                self._transport_error(request.failure)
                return
            self.command = request.method
            self.path = request.target
            self.request_version = f"HTTP/{request.major}.{request.minor}"

            headers, failure = self._read_headers()
            if failure:
                self._transport_error(failure)
                return
            if request.persistent and header_value(headers, HEADER_NAME_HOST) is None:
                # RFC 9112 requires exactly this: an HTTP/1.1 request without a
                # Host header is malformed and must be rejected, not guessed at.
                self._transport_error(400)
                return

            if contains_token(header_value(headers, HEADER_NAME_EXPECT), EXPECT_CONTINUE):
                self.wfile.write(CONTINUE_RESPONSE.encode("iso-8859-1"))
            drained = self._drain_body(
                header_value(headers, HEADER_NAME_CONTENT_LENGTH),
                header_value(headers, HEADER_NAME_TRANSFER_ENCODING),
            )
            connection = header_value(headers, HEADER_NAME_CONNECTION)
            wants_close = contains_token(connection, CONNECTION_CLOSE) or (
                not request.persistent and not contains_token(connection, CONNECTION_KEEP_ALIVE)
            )

            if request.method == METHOD_GET:
                self.do_GET()
            else:
                self._method_not_allowed()
            self.close_connection = not (drained and not wants_close and not self.write_failed)
        except TimeoutError:
            # A client that connected and then stopped sending.  Its connection
            # is simply reclaimed; nothing is owed to it and nothing is logged.
            return
        except OSError:
            # A client that vanished.  Normal traffic on a public port, and not
            # worth a line of output.
            return

    def do_GET(self):
        """Answer the health route; anything else is a 404.

        ``self.path`` is the request target exactly as it arrived on the wire,
        placed there by :meth:`handle_one_request` rather than by the inherited
        parser, so the leading-slash rewrite that parser performs cannot make
        ``//health`` reach this route.
        """
        config, route = self._snapshot()
        if normalize_path(self.path) == route:
            self._respond(200, build_payload(config))
        else:
            self._respond(404, NOT_FOUND_BODY)

    def _method_not_allowed(self):
        """Reject a non-GET request, advertising what is allowed.

        Reached for EVERY method that is not exactly ``GET`` - the ones the
        inherited dispatcher knows about, the ones it does not, a wrongly-cased
        ``get``, and ``CONNECT`` - because the dispatch in
        :meth:`handle_one_request` is a single comparison rather than a lookup for
        a ``do_<VERB>`` attribute.  That is what makes the method policy total:
        there is no verb for which a 501 HTML page, or an echo of the verb itself,
        can be produced.
        """
        self._respond(405, METHOD_NOT_ALLOWED_BODY, send_allow=True)

    def finish(self):
        """Retire the connection politely, then let the base class close it.

        The write side is shut down first and whatever the client is still sending
        is drained briefly.  Both halves matter: closing a socket that still has
        unread data queued makes the kernel answer with a reset, which a client
        reports as "no response" for a request that was in fact answered - and
        without the shutdown first, the drain would wait for a client that is
        itself waiting for an answer it has already received.  Together they turn
        a would-be reset into a readable response, which is what lets a 405 be
        delivered for a POST carrying megabytes this endpoint refuses to read.
        """
        try:
            self.connection.shutdown(socket.SHUT_WR)
            self.connection.settimeout(LINGER_TIMEOUT_SECONDS)
            discarded = 0
            while discarded < MAX_LINGER_DRAIN_BYTES:
                chunk = self.connection.recv(
                    min(IO_BUFFER_BYTES, MAX_LINGER_DRAIN_BYTES - discarded)
                )
                if not chunk:
                    break
                discarded += len(chunk)
        except OSError:
            # Already gone, or never established.  The socket is closed either
            # way by the base class below.
            pass
        super().finish()

    def send_error(self, code, message=None, explain=None):
        """Answer with the contract instead of the stdlib HTML error page.

        Nothing in this class routes here - :meth:`handle_one_request` owns every
        response - but the inherited machinery does, and this override is what
        makes the guarantee total rather than dependent on this module being the
        only writer.  The base implementation would emit a ``text/html`` page
        carrying ``message`` and ``explain``, both of which are built from
        request-supplied text, and would emit it through ``send_response``, which
        suppresses the status line and the entire header block whenever the
        request version could not be parsed.

        ``message`` and ``explain`` are accepted for signature compatibility and
        then discarded, deliberately and without being logged: they exist to
        describe a request back to its sender, and this endpoint does not do that.
        """
        status = code if code in TRANSPORT_ERROR_BODIES else 500
        self._write(
            status,
            render_payload(TRANSPORT_ERROR_BODIES.get(status, {"error": REASON_PHRASES[500]})),
            send_close=True,
        )
        self.close_connection = True

    def send_response(self, code, message=None):
        """Emit a status line without the ``Server`` and ``Date`` headers.

        Like :meth:`send_error`, nothing in this class calls this; it is overridden
        so that the interpreter version cannot leak from a path this module does
        not write, and least disclosure holds for every response the process is
        capable of producing.
        """
        self.send_response_only(code, message)

    def log_message(self, fmt, *args):
        """Send every server log line to stderr.

        Standard output is the legacy contract of this program and is hashed by
        the backward-compatibility gate, so nothing the server says may go there.
        """
        sys.stderr.write("[app.py] %s\n" % (fmt % args))


class HealthServer(ThreadingHTTPServer):
    """Threaded HTTP server for the health endpoint.

    Threading is a correctness requirement rather than a throughput choice: the
    in-process probe and any concurrent CI poll must not be able to queue behind
    one another on a single-threaded accept loop, and one client that connects and
    then stops sending must not be able to wedge the listener for everyone else.
    Worker threads are daemons so that an interrupt at the console cannot be held
    open by an in-flight request, and address reuse is enabled so a restart does
    not fail on a socket lingering in TIME_WAIT.

    The configuration snapshot lives here rather than on the handler because a
    handler is constructed per request: resolving configuration once, before the
    socket is even bound, is what makes every response this server produces
    describe the same application.  See :meth:`HealthRequestHandler._snapshot`.
    """

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address, RequestHandlerClass, config=None, bind_and_activate=True):
        """Snapshot configuration, then bind.

        The snapshot is taken and the route derived BEFORE the base constructor
        runs, so both attributes exist before the listening socket does and no
        accepted connection can observe a half-initialised server.  The mapping is
        copied so that a caller mutating the dict it passed in cannot change what
        a running listener reports.

        :param server_address: ``(host, port)`` to bind.
        :param RequestHandlerClass: normally :class:`HealthRequestHandler`.
        :param config: mapping from :func:`load_config`; loaded when omitted.
        :param bind_and_activate: passed through to the base constructor.
        """
        self.health_config = dict(load_config() if config is None else config)
        self.health_route = health_route(self.health_config)
        super().__init__(server_address, RequestHandlerClass, bind_and_activate)


def create_server(host=None, port=None, config=None):
    """Build a bound, ready-to-run :class:`HealthServer`.

    Construction is separated from the blocking run loop on purpose: a test can
    bind port 0, read the assigned port back from ``server.server_address`` and
    drive ``serve_forever`` on its own thread, none of which is possible if the
    only entry point blocks forever.

    :param host: bind address; the configured ``app.host`` when omitted.
    :param port: bind port; the configured ``python.port`` when omitted. 0 binds
        an ephemeral port.
    :param config: mapping from :func:`load_config`; loaded when omitted.
    :raises ValueError: when the resolved port is not a valid port number.
    :raises OSError: when the address cannot be bound.
    """
    resolved = load_config() if config is None else config
    if host is None:
        host = resolved.get("app.host") or DEFAULTS["app.host"]
    if port is None:
        port = resolved.get("python.port") or DEFAULTS["python.port"]
    return HealthServer((host, _as_port(port)), HealthRequestHandler, resolved)


def serve(host=None, port=None, config=None):
    """Serve the health endpoint until the process is interrupted.

    The startup line goes to stderr, never to stdout: stdout carries this
    program's legacy output and is hashed by the backward-compatibility gate.

    A bind failure - almost always a port already in use - is reported as one
    readable line and exits non-zero rather than unwinding as a traceback.  This
    fails closed: an orchestrator that cannot bind must not see a success code.

    Termination convention.  ``SIGINT`` arrives here as ``KeyboardInterrupt``,
    which is treated as a clean shutdown, so the process exits ``0``.
    ``SIGTERM`` keeps CPython's default disposition, so the process is
    terminated by the signal and a shell reports ``143``.  The other two
    implementations report their own runtime's convention for the same two
    signals - ``node index.js --serve`` exits ``0`` for both, and the JVM
    reports ``130`` and ``143`` - so the exit STATUS is the one place these three
    servers deliberately differ.  What matters for an orchestrator is identical
    everywhere: the listener is closed, the port is released, and stdout stays
    empty.  Forcing the statuses into agreement would mean overriding a
    platform convention for no operational gain, so the difference is documented
    instead.

    :returns: 0 after a clean shutdown.
    """
    resolved = load_config() if config is None else config
    try:
        server = create_server(host, port, resolved)
    except (OSError, ValueError) as exc:
        sys.stderr.write(f"[app.py] cannot start the health server: {exc}\n")
        raise SystemExit(1) from None

    # Configured values reach this line, so control characters are stripped from
    # them first: a health path carrying a CR and an LF would otherwise forge an
    # extra startup line in whatever collects this process's stderr.  The route
    # printed is the NORMALISED one the listener actually answers on, not the raw
    # configured string, so the banner cannot promise a route that does not exist.
    bind_host, bind_port = server.server_address[0], server.server_address[1]
    banner_route = sanitize_for_log(server.health_route)
    banner_host = sanitize_for_log(str(bind_host))
    sys.stderr.write(f"Serving {banner_route} on {banner_host}:{bind_port}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        # An operator pressing Ctrl-C is a clean shutdown, not a failure.
        sys.stderr.write("[app.py] interrupted; shutting down\n")
    finally:
        server.shutdown()
        server.server_close()
    return 0


def probe(config=None):
    """Check this application's own health endpoint.

    This is the container HEALTHCHECK, and it is written in-process precisely so
    that the image needs no HTTP client of its own.  It is deliberately strict:
    the verdict is healthy only when the endpoint answers ``200`` AND the parsed
    body reports the expected status.  Every failure mode - connection refused,
    timeout, wrong status code, unparseable body, anything unforeseen - resolves
    to unhealthy, because a probe that cannot prove health must not report it.

    Loopback is substituted for a wildcard bind address, since ``0.0.0.0`` names
    every interface and is not itself a routable destination.

    :param config: mapping from :func:`load_config`; loaded when omitted.
    :returns: 0 when healthy, 1 when not. Suitable for :func:`sys.exit`.
    """
    resolved = load_config() if config is None else config
    host = resolved.get("app.host") or DEFAULTS["app.host"]
    if host in WILDCARD_HOSTS:
        host = LOOPBACK_HOST
    # The port is validated rather than interpolated as text.  A misconfigured
    # value would otherwise produce an unparseable URL and be reported as
    # "unreachable", which sends an operator looking for a network fault instead
    # of at the typo; and the JavaScript and Java probes both fail closed here
    # with the offending value named, so this one does too.
    try:
        port = _as_port(resolved.get("python.port") or DEFAULTS["python.port"])
    except ValueError as exc:
        sys.stderr.write(f"[app.py] probe cannot run: {exc}\n")
        return 1
    url = f"http://{host}:{port}{health_route(resolved)}"

    try:
        with urllib.request.urlopen(url, timeout=PROBE_TIMEOUT_SECONDS) as response:
            if response.status != 200:
                sys.stderr.write(f"[app.py] probe: {url} answered {response.status}\n")
                return 1
            document = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        # HTTPError is itself a response object holding an open socket, and
        # urlopen raises it instead of returning it, so the ``with`` above never
        # sees it.  Close it explicitly rather than leaving the descriptor to the
        # garbage collector.
        exc.close()
        sys.stderr.write(f"[app.py] probe: {url} answered {exc.code}\n")
        return 1
    except (urllib.error.URLError, OSError, ValueError) as exc:
        sys.stderr.write(f"[app.py] probe: {url} unreachable or unreadable: {exc}\n")
        return 1
    except Exception as exc:  # fail closed on anything unforeseen
        sys.stderr.write(f"[app.py] probe: {url} failed: {exc!r}\n")
        return 1

    status = document.get("status") if isinstance(document, dict) else None
    if status != HEALTH_STATUS:
        sys.stderr.write(f"[app.py] probe: status is {status!r}, expected {HEALTH_STATUS!r}\n")
        return 1
    return 0


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--serve" in args:
        serve()
    elif "--probe" in args:
        sys.exit(probe())
    else:
        user = "Lakshya"
        print(greet(user))
