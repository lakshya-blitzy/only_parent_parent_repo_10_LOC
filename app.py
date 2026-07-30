"""only_parent_parent_repo_10_LOC - Python application and /health endpoint.

Three invocation modes, dispatched by the guard at the bottom of the file:

    python3 app.py            legacy default - prints "Hello Lakshya", exits 0
    python3 app.py --serve    binds app.host:python.port, serves GET /health
    python3 app.py --probe    GETs its own /health; exit 0 healthy, 1 otherwise

The listener is opt-in, so no existing invocation reaches any of the new code.
``--probe`` exists because slim and JRE container images ship neither ``curl``
nor ``wget``, so the application checks its own endpoint with the runtime that
is already present.

Configuration comes from ``app.config.properties``, shared with the JavaScript
and Java implementations: an environment variable overrides a file value, which
overrides a built-in default, and the universal ``PORT`` outranks both for the
listener port.  Every value has a working default, so the endpoint still serves
when the file is absent.

The response contract is frozen and identical in all three implementations; see
the :class:`HealthRequestHandler` docstring for its full text.
"""

import datetime
import http.client
import io
import json
import os
import re
import socket
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def greet(name):
    return f"Hello {name}"


# Resolved relative to THIS FILE rather than to the working directory, so the
# endpoint behaves the same however it is launched.
CONFIG_FILENAME = "app.config.properties"

CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), CONFIG_FILENAME)

#: The properties grammar in the exact terms ``java.util.Properties.load`` defines it:
#: whitespace is SPACE, TAB and FORM FEED only, and ``=``, ``:`` or whitespace
#: separates a key from its value.  Deviating makes one shared file mean three things.
PROPERTIES_WHITESPACE = " \t\f"
PROPERTIES_SEPARATORS = "=:"
PROPERTIES_COMMENTS = "#!"

#: A short or non-hexadecimal ``\uXXXX`` escape makes the document malformed
#: rather than the escape literal, as ``Properties.load`` does.
PROPERTIES_HEX_DIGITS = "0123456789abcdefABCDEF"
PROPERTIES_ESCAPE_WIDTH = 4

#: Worded identically in all three implementations.  An ABSENT file emits neither,
#: that being the normal case.
CONFIG_UNREADABLE_WARNING = "cannot read the configuration file; using defaults"
CONFIG_MALFORMED_WARNING = "the configuration file is malformed; using defaults"

#: Used only when neither the environment nor the file supplies a value.
#: ``app.version`` must stay in step with app.config.properties, pyproject.toml
#: and package.json.
DEFAULTS = {
    "app.name": "only_parent_parent_repo_10_LOC",
    "app.version": "1.1.0",
    "health.path": "/health",
    "app.host": "0.0.0.0",
    "python.port": "8000",
}

ENV_OVERRIDES = {
    "app.name": "APP_NAME",
    "app.version": "APP_VERSION",
    "health.path": "HEALTH_PATH",
    "app.host": "APP_HOST",
    "python.port": "PYTHON_PORT",
}

#: The universal variable outranks every other source for the listener port,
#: following the twelve-factor convention for one application per container.
PORT_KEY = "python.port"
UNIVERSAL_PORT_ENV = "PORT"

#: Bind addresses meaning "every interface".  A wildcard-bound listener is not
#: reachable at its bind address, so ``probe`` substitutes loopback.
WILDCARD_HOSTS = frozenset({"0.0.0.0", "::", "[::]", "*", ""})
LOOPBACK_HOST = "127.0.0.1"

#: Brackets are required in a URL authority so the colons cannot be misread as a
#: port separator.  Both the compressed and expanded spellings are accepted.
LOOPBACK_HOST_V6 = "::1"
LOOPBACK_AUTHORITY_V6 = "[::1]"
IPV6_LOOPBACK_FORMS = frozenset({"::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]"})

#: MAPPED to the numeric address rather than resolved, so a hosts-file entry
#: cannot redirect a self-check off this machine (RFC 6761).
LOOPBACK_NAME = "localhost"

#: All of 127.0.0.0/8 is loopback, so a listener on 127.0.0.2 is probed there.
IPV4_LOOPBACK_PREFIX = "127."

#: Explicit character classes rather than ``\d``, because ``\d`` also matches
#: Arabic-Indic and other Unicode digits that :func:`int` would accept and the
#: JavaScript and Java implementations refuse.
PORT_GRAMMAR = re.compile(r"^[+-]?[0-9]+$")
VERSION_GRAMMAR = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")

#: Asserts the SHAPE of the field only, never its value.
TIMESTAMP_GRAMMAR = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
)

#: Visible US-ASCII only: a configured route carrying a CR or an LF is a
#: header-injection primitive once it reaches a request or a log line.  The same bound
#: applies to an INBOUND request line, where RFC 9112 section 3 permits nothing else
#: between the two delimiters; see :meth:`HealthRequestHandler.parse_request`.
TARGET_MIN_CHAR = 0x21
TARGET_MAX_CHAR = 0x7E

#: ``UP`` is the Java and Spring Boot convention and an accepted alias in the IETF
#: health-check draft, so it is a standards-recognised value.
HEALTH_STATUS = "UP"

#: Plain JSON rather than the IETF health-check draft's media type: a stated
#: deviation, because generic tooling treats that type as an opaque download for
#: no gain in meaning.  The other deviation from the draft is the 405 to HEAD.
CONTENT_TYPE = "application/json"

#: A health response served from a cache is worse than no health response.
CACHE_CONTROL = "no-cache, no-store, must-revalidate"

#: GET is the one method served, and its 200 response carries a body.
#: ``ALLOWED_METHODS`` is the ``Allow`` value on a 405, derived from
#: :data:`METHOD_GET` so the header cannot advertise a method the dispatcher
#: rejects.  HEAD is one of the rejected methods: 405 with the same headers as the
#: equivalent GET, but no body (RFC 9110 section 9.3.2).
METHOD_GET = "GET"
METHOD_HEAD = "HEAD"
ALLOWED_METHODS = METHOD_GET

#: ``email.message.Message``, which backs ``self.headers``, matches names
#: case-insensitively, so these canonical spellings match any client casing.
HEADER_CONTENT_LENGTH = "Content-Length"
HEADER_TRANSFER_ENCODING = "Transfer-Encoding"

#: Present only on a transport-level rejection (see :meth:`send_error`); the three
#: contract responses are framed by ``Content-Length`` and stay reusable.
HEADER_CONNECTION = "Connection"
CONNECTION_CLOSE = "close"

#: Prefix the inherited request loop builds a handler name from, as in ``do_GET``;
#: :meth:`HealthRequestHandler.__getattr__` claims exactly this namespace.
METHOD_HANDLER_PREFIX = "do_"

#: Constant bodies: they echo neither the requested path, the method nor a traceback,
#: because this endpoint is network-reachable.
NOT_FOUND_BODY = {"error": "Not Found"}
METHOD_NOT_ALLOWED_BODY = {"error": "Method Not Allowed"}

HTTP_VERSION = "HTTP/1.1"

#: Short enough that a health-check timeout fires on the probe's own verdict rather
#: than on the runtime killing it mid-request.
PROBE_TIMEOUT_SECONDS = 3.0

#: Largest health document ``probe`` will read; the contract body is 108 bytes.
#: A local endpoint that streams without end must be refused, not accumulated.
#:
#: This ceiling is the bound that stops an endless stream, so it is deliberately NOT
#: raised to accommodate a large configuration - and that has an operational
#: consequence worth stating where the number is defined.  The rendered document is
#: ``73 + len(app.name) + len(app.version)`` bytes of UTF-8 (73 being the four keys,
#: the punctuation, the fixed-width instant and the status), so a configuration whose
#: name and version together exceed 8119 bytes makes this application's OWN healthy
#: answer larger than the probe will read.  The probe then fails closed on a healthy
#: process, and a container health check reading its exit status restarts a container
#: that was working.  8192 is generous against a 108-byte default, the direction of
#: failure is the safe one, and the budget is documented in app.config.properties and
#: .env.example, where an operator actually sets the value.  All three
#: implementations apply the identical ceiling and report it with the identical
#: wording.
MAX_PROBE_BODY_BYTES = 8192

#: Ceiling on the request body read and discarded before answering, and the
#: scratch buffer it goes through; Java applies the identical ceiling.
#: ``BaseHTTPRequestHandler`` never reads a body, so an undrained one is consumed
#: as the next request line on a keep-alive connection, corrupting that request.
MAX_REQUEST_DRAIN_BYTES = 8 * 1024 * 1024
DRAIN_BUFFER_BYTES = 8192

#: Deadline on every blocking socket operation performed BEFORE a request is parsed,
#: applied as :attr:`HealthRequestHandler.timeout`.  Without it a peer that completes
#: the handshake and then sends nothing - or half a header block - holds a handler
#: thread for the life of the process.  A timed-out connection is retired with NO
#: response.  Mirrors the JavaScript listener's ``headersTimeout``.
REQUEST_HEADER_TIMEOUT_SECONDS = 10.0

#: Budget for the drain, mirroring the JavaScript ``requestTimeout`` and the Java
#: ``maxReqTime``.  Larger than the pre-parse bound because a client sending a
#: legitimate body slowly must not be held to the header budget; applied around the
#: drain read alone, after which the pre-parse deadline is restored.
REQUEST_DRAIN_TIMEOUT_SECONDS = 15.0

#: The key set and ORDER the probe requires, plus the one wording all three use
#: when an answer lacks them.  Pinned as a literal because a rendered list differs
#: per language and an operator greps one deployment's logs, not one language's.
PROBE_KEY_ORDER = ("name", "version", "timestamp", "status")
PROBE_KEY_SET_REASON = (
    'body does not carry exactly the keys '
    '["name","version","timestamp","status"] in order'
)

ROOT_PATH = "/"

#: RFC 3986 section 4.2 reads ``//health`` as an authority, not a path.  All three
#: refuse it as a CONFIGURED value because no platform server can route it - CPython
#: folds inbound ``//health`` to ``/health``, the JDK resolves it to an empty path -
#: and a route nothing can reach would report itself up while serving nothing.
NETWORK_PATH_PREFIX = "//"

#: Absolute-form target, as in ``GET http://host/health``: RFC 9112 section 3.2.2
#: requires a server to accept it, and all three reduce it to its path.
SCHEME_SEPARATOR = "://"

#: A real request target never carries a fragment (RFC 9110 section 7.1), but the same
#: function normalises the CONFIGURED path, where one could be hand-written.
FRAGMENT_MARKER = "#"

#: RFC 9112 section 3: ``method SP request-target SP HTTP-version CRLF`` - exactly two
#: single spaces and a CR LF, nothing else.  ``BaseHTTPRequestHandler`` splits the line
#: on ANY run of whitespace and strips either terminator, so it reads a TAB, VERTICAL
#: TAB or FORM FEED as a delimiter and a bare LF as a terminator; the JavaScript and
#: Java listeners refuse all of those.  Validating the RAW line is what makes the three
#: agree, and it is what keeps the ORIGINAL target - the one the caller sent - available
#: for routing.
REQUEST_LINE_TERMINATOR = b"\r\n"
REQUEST_LINE_SEPARATOR = b" "
REQUEST_LINE_TOKENS = 3
HTTP_VERSION_PATTERN = re.compile(rb"\AHTTP/[0-9]+\.[0-9]+\Z")

#: An empty line, in both spellings the transport accepts as one.  It terminates a header
#: block, and RFC 9112 section 2.2 also permits a run of them BEFORE a request line.
EMPTY_LINES = (b"\r\n", b"\n")

#: RFC 9112 section 2.2: a server SHOULD ignore at least one empty line received before
#: a request line, and MUST NOT treat it as a request.  ``parse_request`` returns false
#: for such a line without sending anything, so the caller receives NO response at all;
#: skipping a bounded run answers the request that follows instead.  Bounded because an
#: unbounded run of empty lines is a way to hold a handler thread on nothing.
MAX_LEADING_EMPTY_LINES = 64

#: The transport's own header-block limits, restated so the pre-parse validation applies
#: exactly the ceilings ``http.client`` applies - ``_MAXLINE`` and ``_MAXHEADERS`` - and
#: therefore reports the identical 431 for an over-long line and for too many lines, at
#: the identical byte and line counts.  Deviating either way would move a status code
#: that is part of the refusal contract.
MAX_HEADER_LINE_BYTES = 65536
MAX_HEADER_LINES = 100

#: A field line is ``field-name ":" OWS field-value`` (RFC 9112 section 5), and a
#: field-name is a token: visible US-ASCII with no space, no TAB and no colon.  Section
#: 5.1 requires a server to REJECT a line with whitespace between the name and the colon.
#: The inherited parser instead ENDS the header block at any line whose name is not a
#: token - ``email.feedparser`` records a defect and unreads the line - so every field
#: AFTER it silently disappears, ``Content-Length`` among them, and the body is left on
#: the connection to be read as the next request line (CWE-444).  ``OBS_FOLD_PREFIXES``
#: is the deprecated line-folding continuation, legal after a field line and never as the
#: first line of a block.
HEADER_FIELD_SEPARATOR = b":"
OBS_FOLD_PREFIXES = b" \t"

#: Upper bound on one diagnostic line.  Every value that reaches the emitter is fixed
#: category text today, but a bound means a future caller cannot turn an operator's log
#: into an unbounded write, and it is cheaper than auditing every call site.
MAX_LOG_MESSAGE_CHARS = 512


def log_warning(message):
    """Write one diagnostic line to stderr, and to stderr only.

    Standard output is the legacy contract this program is hashed on, so nothing
    diagnostic may reach it.  What callers may pass depends on who supplied the text,
    and the three cases are different on purpose:

    * A REQUEST-derived value never reaches this function.  Everything an outside
      caller controls - the request line, the target, the method token, a field value -
      is dropped by :meth:`HealthRequestHandler.send_error`, which logs fixed category
      text and a status code and nothing else.  The one exception is generated by the
      runtime rather than by the caller: the inherited pre-parse deadline logs the
      repr of its own :exc:`TimeoutError`, which quotes no part of the request.
    * An OPERATOR-supplied configuration value MAY appear in a start-up diagnostic, as
      it does in all three implementations: the person who typed a bad port is the only
      reader of the message that rejects it, and the listener does not exist yet, so no
      network caller can reach that path.
    * The PROBE path names no value at all, not even a configured one, because a
      container health check's stderr is collected by machinery rather than read by the
      person who typed it.

    Whatever is passed, :func:`sanitize_for_log` bounds it and strips the control
    characters that would forge a second entry.
    """
    sys.stderr.write(f"[app.py] {sanitize_for_log(message)}\n")


def sanitize_for_log(text):
    """Strip control characters from text before it reaches a log line, and bound it.

    Nothing request-derived reaches the emitter, so this is the second line of defence
    rather than the first: a control character would forge extra log entries and an
    unbounded value would flood the log, so both are removed here regardless of what a
    caller passes.  Dropped rather than escaped, because a human reads this.
    """
    if not text:
        return ""
    cleaned = "".join(
        current for current in text if ord(current) >= 0x20 and ord(current) != 0x7F
    )
    return cleaned[:MAX_LOG_MESSAGE_CHARS]


class PropertiesFormatError(ValueError):
    """Raised on a malformed escape - the one thing ``Properties.load`` rejects."""


def _split_natural_lines(text):
    """Split properties text into natural lines on ``\\r\\n``, ``\\n`` or ``\\r``.

    NOT :meth:`str.splitlines`, which also breaks on VT, FF, NEL, LS and PS.
    ``java.util.Properties`` recognises only the three ASCII terminators and treats
    FORM FEED as whitespace INSIDE a line, so splitting there would truncate a value
    the Java loader reads whole.
    """
    lines = []
    current = []
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if char == "\r":
            lines.append("".join(current))
            current = []
            index += 2 if text[index + 1:index + 2] == "\n" else 1
            continue
        if char == "\n":
            lines.append("".join(current))
            current = []
            index += 1
            continue
        current.append(char)
        index += 1
    lines.append("".join(current))
    return lines


def _trailing_backslashes(text):
    """Count the backslashes ending a natural line: ODD continues it, EVEN ends it."""
    count = 0
    at = len(text) - 1
    while at >= 0 and text[at] == "\\":
        count += 1
        at -= 1
    return count


def _skip_properties_whitespace(text, start):
    """Return the first index at or after ``start`` that is not properties whitespace."""
    at = start
    while at < len(text) and text[at] in PROPERTIES_WHITESPACE:
        at += 1
    return at


def _unescape_properties(raw):
    """Resolve the escape sequences ``java.util.Properties.load`` resolves.

    ``\\t``, ``\\n``, ``\\r``, ``\\f`` and ``\\uXXXX`` are special; every OTHER
    escaped character becomes itself, which is how ``\\ ``, ``\\=``, ``\\:``,
    ``\\#`` and ``\\\\`` carry a separator or comment marker into a key or value.
    A capital ``\\U`` yields ``U``, and a trailing lone backslash is dropped.

    :raises PropertiesFormatError: on a ``\\uXXXX`` escape that is not four
        hexadecimal digits.
    """
    out = []
    index = 0
    length = len(raw)
    while index < length:
        char = raw[index]
        index += 1
        if char != "\\":
            out.append(char)
            continue
        if index >= length:
            break
        escape = raw[index]
        index += 1
        if escape == "u":
            digits = raw[index:index + PROPERTIES_ESCAPE_WIDTH]
            if len(digits) != PROPERTIES_ESCAPE_WIDTH or any(
                digit not in PROPERTIES_HEX_DIGITS for digit in digits
            ):
                raise PropertiesFormatError("malformed \\uxxxx encoding")
            out.append(chr(int(digits, 16)))
            index += PROPERTIES_ESCAPE_WIDTH
        elif escape == "t":
            out.append("\t")
        elif escape == "n":
            out.append("\n")
        elif escape == "r":
            out.append("\r")
        elif escape == "f":
            out.append("\f")
        else:
            out.append(escape)
    return "".join(out)


def parse_properties(text):
    """Parse properties text exactly as ``java.util.Properties.load`` parses it.

    Java reads the shared file with ``Properties.load``, so the other two must read
    it identically or one file means two things.  A "split on the first ``=`` and
    strip both halves" parser gets four cases wrong, and each changes what the
    endpoint publishes:

    * Natural lines break on ``\\r\\n``, ``\\n`` or ``\\r``.  Leading whitespace is
      skipped; a blank line is skipped; a leading ``#`` or ``!`` is a comment, but
      only on the FIRST natural line of a logical line.
    * A line ending in an ODD number of backslashes continues onto the next, whose
      own leading whitespace is stripped.
    * The key ends at the first UNESCAPED ``=``, ``:``, SPACE, TAB or FORM FEED;
      surrounding whitespace and one optional separator are consumed.  The value
      keeps its TRAILING whitespace.
    * Both halves are then unescaped, and the last of a repeated key wins.

    A byte-order mark is NOT stripped, because ``Properties.load`` keeps it.

    :raises PropertiesFormatError: on a malformed ``\\uXXXX`` escape.
    """
    properties = {}
    lines = _split_natural_lines(text)
    index = 0
    total = len(lines)
    while index < total:
        line = lines[index]
        index += 1
        start = _skip_properties_whitespace(line, 0)
        if start >= len(line):
            continue
        if line[start] in PROPERTIES_COMMENTS:
            continue
        logical = line[start:]
        while _trailing_backslashes(logical) % 2 == 1:
            logical = logical[:-1]
            if index >= total:
                break
            follow = lines[index]
            index += 1
            logical += follow[_skip_properties_whitespace(follow, 0):]
        cursor = 0
        limit = len(logical)
        while cursor < limit:
            char = logical[cursor]
            if char == "\\":
                cursor += 2
                continue
            if char in PROPERTIES_SEPARATORS or char in PROPERTIES_WHITESPACE:
                break
            cursor += 1
        key_end = min(cursor, limit)
        after = _skip_properties_whitespace(logical, key_end)
        if after < limit and logical[after] in PROPERTIES_SEPARATORS:
            after = _skip_properties_whitespace(logical, after + 1)
        properties[_unescape_properties(logical[:key_end])] = _unescape_properties(
            logical[after:]
        )
    return properties


def read_properties(path=None):
    """Read the shared properties file, parsed by :func:`parse_properties`.

    Read as BYTES and decoded as strict UTF-8, matching
    ``Files.newBufferedReader(location, UTF_8)`` in ``User.java`` and the fatal
    ``TextDecoder`` in ``index.js``, so invalid UTF-8 is a read failure in all three
    rather than replacement characters in some.

    Three outcomes, identical in all three implementations: an ABSENT file is silent
    and the defaults cover every key; a file that cannot be read or is not UTF-8
    emits one warning; a malformed escape emits one warning.  Neither warning carries
    the path or the exception text - a filesystem layout is a deployment detail.
    """
    resolved = CONFIG_PATH if path is None else path
    try:
        with open(resolved, "rb") as handle:
            text = handle.read().decode("utf-8")
    except FileNotFoundError:
        return {}
    except (OSError, UnicodeDecodeError):
        log_warning(CONFIG_UNREADABLE_WARNING)
        return {}
    try:
        return parse_properties(text)
    except PropertiesFormatError:
        log_warning(CONFIG_MALFORMED_WARNING)
        return {}


def config_value(key, env_name=None, default=None, props=None, env=None):
    """Resolve one configuration key.

    Precedence is fixed: environment variable, then the properties file, then the
    built-in default - and for the listener port the universal ``PORT`` first of all.
    An empty value is treated as absent at every level, so a caller that exports
    ``APP_NAME=""`` gets the configured name rather than an empty ``name`` field,
    which the response contract forbids.

    ``env`` and ``props`` are injectable so tests can assert the precedence order
    without mutating :data:`os.environ` for the whole interpreter.
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
    """Resolve every configuration key, reading the properties file exactly once."""
    environ = os.environ if env is None else env
    properties = read_properties(path)
    return {
        key: config_value(key, ENV_OVERRIDES.get(key), default, props=properties, env=environ)
        for key, default in DEFAULTS.items()
    }


def is_single_line_text(text):
    """Return ``True`` when text is non-empty and carries no control character.

    The rule for the two configured values that are neither a route nor a number: a name
    that reaches the published payload and a host that reaches a diagnostic.  Printable
    on one line is the constraint, which is what stops either forging a second line.
    """
    if not text:
        return False
    for current in text:
        point = ord(current)
        if point < 0x20 or point == 0x7F:
            return False
    return True


def is_request_target(candidate):
    """Report whether a target is made only of visible US-ASCII.

    Applied to the CONFIGURED route rather than to an inbound request - reading a
    request is the platform server's job - so a health path carrying a space, a CR or
    an LF is refused where it is configured instead of becoming an injected request
    line in :func:`probe`.
    """
    if not candidate:
        return False
    for current in candidate:
        if not TARGET_MIN_CHAR <= ord(current) <= TARGET_MAX_CHAR:
            return False
    return True


def validate_config(config):
    """Refuse a configuration this endpoint must not publish.

    Every configured value reaches either the public document or the route serving it, so
    an unchecked one lets the endpoint attest to its own health while describing itself in
    a form no consumer of the frozen contract can parse.

    Four rules, identical in all three implementations: ``app.name`` and ``app.host`` are
    non-empty single-line text; ``app.version`` matches :data:`VERSION_GRAMMAR`; and the
    ROUTE that ``health.path`` reduces to through :func:`config_route` is a valid request
    target and not a :data:`NETWORK_PATH_PREFIX` reference.  Grading the route rather than
    the raw value means what is validated is what will be served.  A key that is PRESENT
    is validated as it stands, so an explicitly empty value is a fault rather than an
    invitation to substitute.  No message quotes the offending value, which is what lets
    :func:`probe` print it verbatim.

    :raises ValueError: on the first rule that fails.
    """
    name = config.get("app.name", DEFAULTS["app.name"])
    if not is_single_line_text(name):
        raise ValueError(
            "invalid app.name: it must be non-empty text with no control character"
        )

    version = config.get("app.version", DEFAULTS["app.version"])
    if not isinstance(version, str) or not VERSION_GRAMMAR.match(version):
        raise ValueError(
            "invalid app.version: it must be a three-part dotted numeric version"
        )

    path = config.get("health.path", DEFAULTS["health.path"])
    if not isinstance(path, str) or not path:
        raise ValueError("invalid health.path: it is not a valid request target")
    route = config_route(path)
    if route.startswith(NETWORK_PATH_PREFIX) or not is_request_target(route):
        raise ValueError("invalid health.path: it is not a valid request target")

    host = config.get("app.host", DEFAULTS["app.host"])
    if not is_single_line_text(host):
        raise ValueError(
            "invalid app.host: it must be non-empty text with no control character"
        )


def _as_port(value):
    """Coerce a configured port to a valid TCP port number.

    Port 0 is permitted: it is how a test binds an ephemeral port and reads the
    assignment back.  :data:`PORT_GRAMMAR` is checked BEFORE the conversion because it
    is deliberately narrower than ``int()``, which honours PEP 515 separators
    (``8_001``) and any Unicode decimal digit - both refused by Node and Java, so
    accepting them would give one configured value three different outcomes.
    """
    text = str(value).strip()
    if not PORT_GRAMMAR.match(text):
        raise ValueError(f"invalid port {value!r}: expected an ASCII decimal integer")
    port = int(text)
    if not 0 <= port <= 65535:
        raise ValueError(f"invalid port {port}: outside the range 0-65535")
    return port


def normalize_path(target):
    """Reduce a request target to the path this endpoint routes on.

    Four reductions, in this order and matching the other two implementations exactly:
    authority, query, fragment, and at most ONE trailing slash.  ``/health//``
    deliberately does not reduce, because two slashes describe a different path.
    Nothing else is rewritten - no percent-decoding, no dot-segment resolution, no
    collapsing of leading slashes - because each would let a caller reach the endpoint
    by a name that is not the configured one.

    ``parse_request`` folds an inbound target beginning with ``//`` to one slash
    (CPython gh-87389), so ``self.path`` would reach this function already rewritten and
    ``//health`` would route as ``/health`` - an alias the other two implementations
    refuse.  This function is not where that is corrected: the ORIGINAL target is kept as
    ``HealthRequestHandler.raw_target`` and is what :meth:`HealthRequestHandler.do_GET`
    passes here, so the fold cannot manufacture a route.  The other half is
    :func:`validate_config` refusing such a CONFIGURED route in all three.
    """
    if not target:
        return ROOT_PATH
    path = strip_authority(target)
    path = path.split("?", 1)[0]
    path = path.split(FRAGMENT_MARKER, 1)[0]
    if len(path) > 1 and path.endswith(ROOT_PATH):
        path = path[:-1]
    return path or ROOT_PATH


def _is_ascii_letter(current):
    """Return ``True`` for A-Z and a-z only, never for a non-ASCII letter."""
    return "a" <= current <= "z" or "A" <= current <= "Z"


def _is_scheme(candidate):
    """Return ``True`` for an RFC 3986 scheme: ALPHA then ALPHA / DIGIT / ``+-.``."""
    if not candidate or not _is_ascii_letter(candidate[0]):
        return False
    for current in candidate[1:]:
        if not (_is_ascii_letter(current) or "0" <= current <= "9"
                or current in "+-."):
            return False
    return True


def strip_authority(target):
    """Reduce an absolute-form request target to its path component.

    RFC 9112 section 3.2.2 requires a server to accept ``GET http://host/health``, and
    all three reduce it to the origin form.  The scheme is VALIDATED before anything is
    stripped, which is the whole safety of this function: in
    ``/health?next=http://elsewhere/`` the text before the separator is not a scheme,
    so the target is returned untouched.

    The authority is DISCARDED, not inspected, and that is deliberate rather than an
    omission.  A FOREIGN authority - ``GET http://evil.example/health``, or one
    carrying userinfo, or a port that is not the one bound - therefore reaches exactly
    the route its path names: measured on the wire, all three implementations answer
    the frozen 200 for such a target and the frozen 404 for
    ``http://evil.example/nope``.  Nothing here depends on the authority, so honouring
    it would only create a way to make one deployment answer differently from another;
    RFC 9112 section 3.2.2 does require the target to be accepted, and section 7.2
    puts host-based dispatch in ``Host``, which a single-route endpoint has no use for.
    """
    separator = target.find(SCHEME_SEPARATOR)
    if separator <= 0 or not _is_scheme(target[:separator]):
        return target
    authority_start = separator + len(SCHEME_SEPARATOR)
    path_start = target.find("/", authority_start)
    return ROOT_PATH if path_start < 0 else target[path_start:]


def config_route(value):
    """Reduce a CONFIGURED health path to the route the endpoint will answer on.

    A missing leading slash is supplied, then :func:`normalize_path` reduces the rest.
    Validator and router both go through here, which is what makes the route VALIDATED
    and the route SERVED the same string by construction, in all three.
    """
    path = value if value.startswith(ROOT_PATH) else ROOT_PATH + value
    return normalize_path(path)


def health_route(config=None):
    """Return the route the endpoint answers on, and that :func:`probe` dials."""
    resolved = load_config() if config is None else config
    path = resolved.get("health.path") or DEFAULTS["health.path"]
    return config_route(path)


def health_timestamp():
    """Return the current UTC instant, truncated to whole seconds, ``...Z``.

    RFC 3339 with the zone as ``Z`` rather than ``+00:00``, so all three emit an
    identically shaped field.  The only non-deterministic value in the payload, hence
    the only one asserted by FORMAT and never by value.
    """
    return (
        datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def build_payload(config=None):
    """Build the frozen four-key health document.

    Insertion order IS the wire order - ``dict`` preserves it and the renderer does not
    sort.  Nothing beyond the four keys is included: every extra field is disclosure.
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

    Both non-default arguments hold byte parity with the other two implementations,
    and a ``Content-Length`` that differs between two implementations of one endpoint
    means they are not the same endpoint.  ``separators`` removes the default space
    after ``:`` and ``,`` (115 bytes against their 108); ``ensure_ascii=False`` stops
    non-ASCII being escaped as ``\\uXXXX`` (119 against 102 for a name of
    ``h\u00e9llo``) and is still valid JSON, RFC 8259 defining JSON text as UTF-8.
    Keys are never sorted: insertion order is the specified field order.
    """
    return json.dumps(payload, separators=(",", ":"), ensure_ascii=False)


class HealthRequestHandler(BaseHTTPRequestHandler):
    """Serve the frozen health contract, and nothing else.

    GET on the configured health path (query string stripped, one optional trailing
    slash accepted) answers ``200`` with the compact four-key document; any other path
    answers ``404 {"error":"Not Found"}``; any other method answers
    ``405 {"error":"Method Not Allowed"}`` with ``Allow: GET``.  All three carry exactly
    ``Content-Type``, ``Cache-Control`` and ``Content-Length`` - and therefore no
    ``Server`` banner and no ``Date``.  A response to HEAD carries no body (RFC 9110
    section 9.3.2) while ``Content-Length`` still advertises what a GET would return.

    Three responses, and no fourth.  Deciding which of the three to write is most of what
    this class does; the rest is :meth:`parse_request`, which holds the inherited parser
    to the request grammar it is lenient about, because the leniency is what let a caller
    reach the endpoint under a name that is not the configured route.  The common verbs
    get one explicit ``do_*`` method each so the policy can be read off the class, and
    :meth:`__getattr__` makes dispatch TOTAL: every method token a request line can carry
    reaches the 405 responder.  The one response this class does not compose - a
    transport-level rejection of a request too malformed to have a method - is narrowed to
    a status line by :meth:`send_error`.
    """

    protocol_version = HTTP_VERSION

    #: Every response path builds its own header set, so this matters only if an
    #: inherited one returns.
    sys_version = ""

    #: Deadline on every socket operation before a request has been parsed, which is
    #: what closes the slowloris hole; ``StreamRequestHandler.setup`` applies it to the
    #: accepted socket.  See :data:`REQUEST_HEADER_TIMEOUT_SECONDS`.
    timeout = REQUEST_HEADER_TIMEOUT_SECONDS

    #: The request target exactly as the caller sent it, which is what :meth:`do_GET`
    #: routes on: ``self.path`` has been rewritten by then (CPython gh-87389 folds a
    #: leading ``//`` to one slash), and routing on a rewritten target answers ``200``
    #: on a name the JavaScript and Java listeners answer ``404`` on.  Class-level so a
    #: handler constructed without a request - as a test may do - reads as "no target".
    raw_target = ""

    def parse_request(self):
        """Hold the request to its grammar on the RAW bytes, then parse it as usual.

        The inherited parser is lenient in five ways that each let a caller reach this
        endpoint by a route or a framing at least one of the other two implementations
        refuses, and every one of them is decided before any ``do_*`` method runs:

        * it splits the request line on ANY whitespace run, so ``GET\\t/health\\tHTTP/1.1``
          parses, and it strips either line terminator, so a bare LF parses;
        * a request line of zero words returns false WITHOUT sending anything, so a single
          empty line before the request - which RFC 9112 section 2.2 says to ignore -
          leaves the caller with no response at all;
        * a two-word line is HTTP/0.9, for which the inherited writer emits no status line
          and no headers, so the reply is a bare body that is not an HTTP response;
        * ``email.feedparser`` ends the header block at the first line whose field-name is
          not a token, discarding every field after it - ``Content-Length`` among them -
          which leaves the body to be read as the next request line (CWE-444);
        * end of stream inside the header block reads as the end of the headers, so a
          request that was never finished is served.

        So the request line and the header block are validated as the bytes they arrived
        as, and only then handed to the inherited parser - through a buffer holding the
        validated block, with the socket restored afterwards, positioned at the first BODY
        byte.  That last detail is what keeps :meth:`_drain_request_body` correct: it
        reads a body, never a following request.  Delegating rather than reimplementing
        keeps the ``Connection`` and ``Expect`` handling, the 505 for an unsupported major
        version, and the request-line ceiling exactly as the transport defines them.
        """
        line = self._request_line()
        if line is None:
            return False
        if not self._accept_request_line(line):
            return False
        block = self._header_block()
        if block is None:
            return False
        socket_reader = self.rfile
        self.rfile = io.BytesIO(block)
        try:
            return super().parse_request()
        finally:
            self.rfile = socket_reader

    def _read_raw_line(self, oversize_status):
        """Read one line under the transport's own length ceiling.

        Returns the raw bytes, ``b""`` at end of stream, or ``None`` once a refusal has
        been written.  The ceiling and the one-byte overshoot that detects it are
        ``http.client``'s own, so an over-long line is refused at the same byte count the
        inherited parser refuses it at - with the status its context calls for, ``414``
        for a request line and ``431`` for a field line.
        """
        line = self.rfile.readline(MAX_HEADER_LINE_BYTES + 1)
        if len(line) > MAX_HEADER_LINE_BYTES:
            self.send_error(oversize_status)
            return None
        return line

    def _request_line(self):
        """Return the request line, skipping a bounded run of empty lines before it.

        RFC 9112 section 2.2 says a server SHOULD ignore at least one empty line received
        where a request line is expected.  The inherited loop reads that line, hands it to
        this method, and the inherited parser returns false for it without sending
        anything - so the connection closes in silence and a client that ends a previous
        message with a stray CRLF is never answered.  Ignoring a bounded run answers the
        request that follows; exceeding the bound is refused rather than absorbed, because
        an endless run of empty lines is a way to hold a handler thread on no request.
        """
        line = self.raw_requestline
        ignored = 0
        while line in EMPTY_LINES:
            if ignored >= MAX_LEADING_EMPTY_LINES:
                self.send_error(400)
                return None
            ignored += 1
            line = self._read_raw_line(414)
            if line is None:
                return None
            if not line:
                # Empty lines and then a hang-up: there is no request to answer, and
                # nothing to refuse either.  This is how a client closes an idle
                # keep-alive connection.
                self.close_connection = True
                return None
        self.raw_requestline = line
        return line

    def _accept_request_line(self, line):
        """Refuse anything that is not ``method SP request-target SP HTTP-version CRLF``.

        RFC 9112 section 3 admits exactly two single spaces, exactly three fields and one
        CR LF, and permits nothing but visible US-ASCII inside the method and the target.
        Each clause below is one of those, so a TAB, VERTICAL TAB or FORM FEED delimiter,
        a bare LF terminator, a stray fourth field, an embedded CR, and the two-field
        HTTP/0.9 form are all refused here - the same refusals the JavaScript and Java
        listeners make one layer lower.  The version is checked for SHAPE only: the
        inherited parser owns what a shaped version MEANS, which is what keeps ``HTTP/9.9``
        a 505 rather than turning it into a 400.

        The accepted target is kept as :attr:`raw_target`, before any rewriting.
        """
        if not line.endswith(REQUEST_LINE_TERMINATOR):
            self.send_error(400)
            return False
        fields = line[: -len(REQUEST_LINE_TERMINATOR)].split(REQUEST_LINE_SEPARATOR)
        if len(fields) != REQUEST_LINE_TOKENS or not all(fields):
            self.send_error(400)
            return False
        method, target, version = fields
        if not HTTP_VERSION_PATTERN.match(version):
            self.send_error(400)
            return False
        for field in (method, target):
            for octet in field:
                if not TARGET_MIN_CHAR <= octet <= TARGET_MAX_CHAR:
                    self.send_error(400)
                    return False
        # Latin-1 is what the inherited parser decodes the line with, so the two agree on
        # every byte and no target can mean one thing here and another there.
        self.raw_target = target.decode("latin-1")
        return True

    def _accept_field_line(self, line, first):
        """Refuse a field line the inherited parser would read as the END of the block.

        A field-name is a token - visible US-ASCII, no space, no TAB, no colon - so
        ``X-A : 1`` and ``X A: 1`` are not field lines, and RFC 9112 section 5.1 requires
        a recipient to reject them.  ``email.feedparser`` instead records a defect and
        stops reading headers there, which is what silently drops the ``Content-Length``
        that :meth:`_drain_request_body` needs.  A continuation line is accepted after a
        field line, matching ``User.java``, and refused as the first line of a block, where
        it continues nothing - a position all three refuse.

        Accepting the continuation is the majority reading and the conservative one:
        measured on the wire, ``User.java`` answers such a request 200 and ``index.js``
        refuses it 400 in its parser, which RFC 9112 section 5.2 also permits.  Refusing
        outright is a behaviour change no finding asked for, so the divergence is recorded
        here and in ``index.js`` rather than resolved by tightening this file.
        """
        if line[0] in OBS_FOLD_PREFIXES:
            if first:
                self.send_error(400)
                return False
            return True
        separator = line.find(HEADER_FIELD_SEPARATOR)
        if separator <= 0:
            self.send_error(400)
            return False
        for octet in line[:separator]:
            if not TARGET_MIN_CHAR <= octet <= TARGET_MAX_CHAR:
                self.send_error(400)
                return False
        return True

    def _header_block(self):
        """Read and validate the whole header block, or refuse and return ``None``.

        The block is returned as the bytes it arrived as, terminator included, so the
        delegated parse sees exactly what the peer sent and the socket is left positioned
        at the first body byte.  Lines are counted the way ``http.client`` counts them -
        the terminating empty line included - so the 431 for too many fields lands on the
        same line number as before.  End of stream in place of the terminator is refused:
        the inherited parser treats it as the end of the headers and serves the request,
        which is not a message the peer ever finished sending.

        ``index.js`` refuses it too, with the minimal 400 its parser writes.  ``User.java``
        does NOT - measured on the wire, the JDK server answers such a request 200, and
        that behaviour sits below anything application code on that side can reach, so it
        is recorded as a residual divergence rather than claimed as parity.
        """
        lines = []
        while True:
            line = self._read_raw_line(431)
            if line is None:
                return None
            if not line:
                self.send_error(400)
                return None
            lines.append(line)
            if len(lines) > MAX_HEADER_LINES:
                self.send_error(431)
                return None
            if line in EMPTY_LINES:
                return b"".join(lines)
            if not self._accept_field_line(line, len(lines) == 1):
                return None

    def __getattr__(self, name):
        """Route every unimplemented HTTP method to the 405 responder.

        The inherited request loop dispatches on ``hasattr(self, "do_" + command)`` and
        otherwise falls back to a 501 carrying an HTML body, a ``Server`` banner naming
        the interpreter, a ``Date`` header and a REFLECTION of the caller's method token -
        four departures from the contract.  Claiming the ``do_`` namespace makes the
        policy total instead: the same constant 405 with the same headers plus ``Allow``,
        whatever the caller called it - a decision on "not GET" rather than on a list of
        known verbs.  ``User.java`` decides the same way, for any token at all.
        ``index.js`` decides that way only for a token its parser recognises: measured on
        the wire, an unrecognised or lower-cased token is refused 400 by llhttp before its
        handler runs, which is recorded there.

        Only ``do_`` names are claimed; everything else raises :exc:`AttributeError` as it
        must, so :func:`hasattr` keeps telling the truth and the copy, pickle and
        introspection protocols keep working.
        """
        if name.startswith(METHOD_HANDLER_PREFIX) and len(name) > len(METHOD_HANDLER_PREFIX):
            return self._method_not_allowed
        raise AttributeError(name)

    def send_error(self, code, message=None, explain=None):
        """Refuse a request too malformed to have a method, in one status line.

        Reached only for a request the transport itself rejects - an over-long or
        unparsable request line, an unsupported HTTP version, an over-long or malformed
        header block.  Such requests never reach a ``do_*`` method, so they are the one
        class of response this handler cannot compose from the frozen contract.

        The inherited version emits an HTML document, a ``Server`` banner, a ``Date``
        header and the caller's message - which embeds the offending request line - into
        both the status line and the body; and when the request line did not parse it
        classifies the request as HTTP/0.9 and writes no status line at all, producing a
        reply that is not a valid HTTP response.  Written instead, straight to the socket
        so the HTTP/0.9 classification cannot suppress it: a status line with the
        canonical reason phrase, ``Content-Length: 0`` and ``Connection: close`` - the
        shape the JavaScript listener sends.

        ``message`` and ``explain`` are accepted because the inherited parser passes them,
        and they are DISCARDED: the inherited wordings quote the offending request line
        (``Bad request syntax ('GET /health with space HTTP/1.1')``), so logging them would
        put caller-chosen text into an operator's log through the one path an outside
        caller can reach - the exact thing :func:`log_warning` documents it never carries.
        The status code says everything an operator can act on, and the JavaScript and Java
        listeners log no more than that either.
        """
        status = int(code)
        phrase = self.responses[code][0] if code in self.responses else ""
        self.log_error("refusing a malformed request with %d", status)
        # Retired unconditionally: the framing of a request this server could not parse
        # is unknowable, so no following request could be trusted to start in place.
        self.close_connection = True
        try:
            self.wfile.write(
                (
                    f"{self.protocol_version} {status} {phrase}\r\n"
                    f"{HEADER_CONTENT_LENGTH}: 0\r\n"
                    f"{HEADER_CONNECTION}: {CONNECTION_CLOSE}\r\n"
                    "\r\n"
                ).encode("latin-1")
            )
        except OSError as exc:
            self.log_error("client closed the connection: %s", type(exc).__name__)

    def _snapshot(self):
        """Return the configuration snapshot and route this server was built on.

        Configuration is resolved ONCE, when the server is constructed, as in the other
        two implementations: re-reading per request would answer two concurrent polls with
        two different documents while a deployment was mid-write.  The fallback covers a
        handler mounted on a plain :class:`http.server.HTTPServer`, legitimate in a test.
        """
        config = getattr(self.server, "health_config", None)
        if config is None:
            config = load_config()
        route = getattr(self.server, "health_route", None)
        if route is None:
            route = health_route(config)
        return config, route

    def _drain_request_body(self):
        """Read and discard the request body, so the connection stays honest.

        Runs before every response on every path, mirroring the Java implementation under
        the same :data:`MAX_REQUEST_DRAIN_BYTES` ceiling.  Node needs no counterpart: it
        dumps an unconsumed request itself.

        Four cases cannot be drained and each retires the connection rather than guess
        where the body ends: a chunked body, whose decoding needs a reader this server
        deliberately does not carry; a REPEATED ``Content-Length``, which leaves the
        message with no single framing this server is entitled to choose; a
        ``Content-Length`` that is not plain non-negative decimal; and a length above the
        ceiling.  Retiring is always safe - the response is already complete and
        self-describing.

        Retiring, rather than draining a guess, is what closes CWE-444: a front end that
        resolved an ambiguous length differently from this server would treat bytes as
        body that this server would read as the next request line.  Undrained bytes on a
        RETIRED connection are never parsed, so the two cannot disagree.  The property that
        closes it is ONE response and no second parse, not a particular status: measured on
        the wire, this server answers each of the four exactly once and never answers a
        request that followed the undrained bytes.

        Node reaches the same outcome one layer lower in llhttp, refusing a repeated or
        non-decimal length 400 and answering an over-ceiling length once.  Java does not,
        on two of the four, and it is recorded rather than assumed: it ACCEPTS a signed
        ``+5``, drains five bytes and then answers the request behind them, and for a length
        above what it will wait for it writes NO response at all.  Both sit below
        application code there.
        """
        if self.command == METHOD_GET or self.command == METHOD_HEAD:
            # Neither verb carries a body here, and reading zero bytes still costs a
            # syscall on every health poll.
            if HEADER_CONTENT_LENGTH not in self.headers and \
                    HEADER_TRANSFER_ENCODING not in self.headers:
                return
        if HEADER_TRANSFER_ENCODING in self.headers:
            self.close_connection = True
            return
        lengths = self.headers.get_all(HEADER_CONTENT_LENGTH)
        if lengths is None:
            # No length and not chunked: HTTP/1.1 says there is no body.
            return
        if len(lengths) > 1:
            # RFC 9112 section 6.3 permits a recipient to fold repeated values that
            # AGREE into one, and to reject them; llhttp and the JDK server both
            # reject, so rejecting is what keeps the three answers uniform.
            self.close_connection = True
            return
        stated = lengths[0].strip()
        if not stated.isascii() or not stated.isdigit():
            self.close_connection = True
            return
        remaining = int(stated)
        if remaining > MAX_REQUEST_DRAIN_BYTES:
            self.close_connection = True
            return
        restore = self.connection.gettimeout()
        self.connection.settimeout(REQUEST_DRAIN_TIMEOUT_SECONDS)
        try:
            while remaining > 0:
                chunk = self.rfile.read(min(remaining, DRAIN_BUFFER_BYTES))
                if not chunk:
                    # Promised more than it sent: nothing left to misparse, but the
                    # framing is broken.
                    self.close_connection = True
                    return
                remaining -= len(chunk)
        except OSError:
            # A timeout arrives here too, TimeoutError being an OSError.  Either way the
            # body is only partly consumed, so the connection can no longer be trusted
            # to frame a following request.  No diagnostic: the response is the answer.
            self.close_connection = True
        finally:
            try:
                self.connection.settimeout(restore)
            except OSError:
                self.close_connection = True

    def _write(self, status, body, send_allow=False):
        """Write one complete response: status line, three headers, body.

        ``send_response_only`` rather than the obvious ``send_response``, because the
        convenient one appends a ``Server`` header carrying the interpreter version
        and a ``Date`` header - neither of which belongs on a surface whose header set
        is frozen at three, and both of which the JavaScript implementation also
        suppresses.  ``Content-Length`` is the ENCODED byte length, so a multi-byte
        character in a configured value cannot desynchronise it from the body.  A
        client that hangs up mid-response is routine, so it is noted on stderr and the
        connection retired rather than surfacing as a traceback.
        """
        encoded = body.encode("utf-8")
        self._drain_request_body()
        try:
            self.send_response_only(status)
            self.send_header("Content-Type", CONTENT_TYPE)
            self.send_header("Cache-Control", CACHE_CONTROL)
            self.send_header("Content-Length", str(len(encoded)))
            if send_allow:
                self.send_header("Allow", ALLOWED_METHODS)
            self.end_headers()
            if self.command != METHOD_HEAD:
                self.wfile.write(encoded)
        except OSError as exc:
            self.close_connection = True
            self.log_error("client closed the connection: %s", type(exc).__name__)

    def _respond(self, code, payload, send_allow=False):
        """Serialise a payload and write it as a contract response.

        The one entry point for 200, 404 and 405, so the body format and the header set
        cannot drift between them.
        """
        self._write(code, render_payload(payload), send_allow=send_allow)

    def do_GET(self):
        """Answer the health route; anything else is a 404.

        Routed on :attr:`raw_target` - the target as the caller sent it - and not on
        ``self.path``, which the inherited parser rewrites: it folds a leading ``//`` to
        one slash (CPython gh-87389), so ``//health`` and ``///health`` would arrive here
        as ``/health`` and this endpoint would answer ``200`` on a name the JavaScript and
        Java listeners answer ``404`` on.  The fallback covers a handler driven without a
        parsed request, which only a test does.
        """
        config, route = self._snapshot()
        if normalize_path(self.raw_target or self.path) == route:
            self._respond(200, build_payload(config))
        else:
            self._respond(404, NOT_FOUND_BODY)

    def _method_not_allowed(self):
        """Reject a non-GET request, advertising what is allowed.

        The body is constant: it echoes neither the method nor the path, because this
        endpoint is network-reachable and discloses the minimum about what is behind it.
        """
        self._respond(405, METHOD_NOT_ALLOWED_BODY, send_allow=True)

    def do_HEAD(self):
        """HEAD is refused like every other non-GET verb.

        RFC 9110 expects HEAD wherever GET is supported, so this is a stated deviation
        rather than an oversight: it keeps the contract at one method across all three
        implementations.  The response carries the headers and no body, as a HEAD
        response must.
        """
        self._method_not_allowed()

    def do_POST(self):
        """POST is refused: this endpoint reads nothing and changes nothing."""
        self._method_not_allowed()

    def do_PUT(self):
        """PUT is refused: the health document is computed, never stored."""
        self._method_not_allowed()

    def do_DELETE(self):
        """DELETE is refused: there is no resource here to remove."""
        self._method_not_allowed()

    def do_PATCH(self):
        """PATCH is refused: the payload is derived from configuration."""
        self._method_not_allowed()

    def do_OPTIONS(self):
        """OPTIONS is refused; a 405 with ``Allow`` states the same policy a 204 would."""
        self._method_not_allowed()

    def log_message(self, fmt, *args):
        """Send every server log line to stderr, through the one safe emitter.

        The inherited default writes to stderr directly, with a timestamp and the client
        address; routing it through :func:`log_warning` gives every line of this process
        one prefix, one sanitising pass and one length bound.

        Nothing an outside caller supplies arrives here.  The inherited machinery would
        log the request line - :meth:`send_error` is where it would enter, and that
        override discards it - and the inherited access log is unreachable because every
        response is written with ``send_response_only``, which does not call
        ``log_request``.  What remains is this server's own fixed category text, a status
        code, an exception TYPE name, and the runtime's own deadline repr.
        """
        log_warning(fmt % args)


class HealthServer(ThreadingHTTPServer):
    """Threaded HTTP server for the health endpoint.

    Threading is a correctness requirement rather than a throughput choice: a client
    that connects and then stops sending must not wedge the listener for everyone else.
    Worker threads are daemons so an interrupt cannot be held open by an in-flight
    request, and address reuse avoids a restart failing on TIME_WAIT.  The
    configuration snapshot lives here because a handler is constructed per request.
    """

    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address, RequestHandlerClass, config=None, bind_and_activate=True):
        """Snapshot configuration, then bind.

        Snapshot and route are derived BEFORE the base constructor runs, so both exist
        before the listening socket does and no accepted connection can observe a
        half-initialised server.  The mapping is copied, so a caller mutating the dict
        it passed in cannot change what a running listener reports.
        """
        self.health_config = dict(load_config() if config is None else config)
        self.health_route = health_route(self.health_config)
        super().__init__(server_address, RequestHandlerClass, bind_and_activate)


def create_server(host=None, port=None, config=None):
    """Build a bound, ready-to-run :class:`HealthServer`.

    Separated from the blocking run loop so a test can bind port 0, read the assigned
    port back and drive ``serve_forever`` on its own thread.

    The configuration is validated BEFORE the socket is bound, so an unpublishable
    document can never be served: a server that bound and then answered ``200``/``UP``
    with a malformed version would look healthy while publishing the very thing
    validation exists to refuse.

    :raises ValueError: on an unpublishable configuration or an unusable port.
    :raises OSError: when the address cannot be bound.
    """
    resolved = load_config() if config is None else config
    validate_config(resolved)
    if host is None:
        host = resolved.get("app.host") or DEFAULTS["app.host"]
    if port is None:
        port = resolved.get("python.port") or DEFAULTS["python.port"]
    return HealthServer((host, _as_port(port)), HealthRequestHandler, resolved)


def serve(host=None, port=None, config=None):
    """Serve the health endpoint until the process is interrupted.

    The startup line goes to stderr, never to stdout, which carries this program's
    legacy output.  A bind failure is one readable line and a non-zero exit rather than
    a traceback, because an orchestrator that cannot bind must not see a success code.

    ``SIGINT`` is a clean shutdown, so the process exits ``0``; ``SIGTERM`` keeps
    CPython's default disposition, so a shell reports ``143``.  Each implementation
    reports its own runtime's convention, so exit STATUS is the one place the three
    servers deliberately differ; what an orchestrator depends on is identical - the
    listener closes, the port is released, and stdout stays empty.
    """
    resolved = load_config() if config is None else config
    try:
        server = create_server(host, port, resolved)
    except (OSError, ValueError) as exc:
        # Routed through log_warning so the one diagnostic that can carry an exception
        # message is sanitised like every other.
        log_warning(f"cannot start the health server: {exc}")
        raise SystemExit(1) from None

    # Control characters are stripped first: a health path carrying a CR and an LF would
    # otherwise forge an extra startup line.  The route printed is the NORMALISED one the
    # listener answers on, so the line cannot promise a route that does not exist.
    bind_host, bind_port = server.server_address[0], server.server_address[1]
    banner_route = sanitize_for_log(server.health_route)
    banner_host = sanitize_for_log(str(bind_host))
    sys.stderr.write(f"Serving {banner_route} on {banner_host}:{bind_port}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log_warning("interrupted; shutting down")
    finally:
        server.shutdown()
        server.server_close()
    return 0


def _is_digits(candidate):
    """Return ``True`` when every character is an ASCII digit and there is one.

    ASCII only, deliberately: :meth:`str.isdigit` admits Arabic-Indic and other Unicode
    decimal digits, and a near-miss address spelled with one must not reach :func:`int`
    and be graded loopback.
    """
    if not candidate:
        return False
    for current in candidate:
        if not "0" <= current <= "9":
            return False
    return True


def _is_ipv4_loopback(candidate):
    """Report whether a string is ``127.b.c.d`` with four octets in 0-255.

    Written out rather than delegated to :mod:`ipaddress` because a general address
    parser accepts spellings this module has no reason to accept - ``127.1``,
    ``0x7f.0.0.1``, a bare decimal integer - each of which is another destination
    the allowlist would then have to reason about.  Four decimal octets or nothing.
    """
    if not candidate.startswith(IPV4_LOOPBACK_PREFIX):
        return False
    octets = candidate.split(".")
    if len(octets) != 4:
        return False
    for octet in octets:
        if not _is_digits(octet) or len(octet) > 3 or int(octet) > 255:
            return False
    return True


def probe_authority(host=None):
    """Return the loopback authority :func:`probe` is permitted to connect to.

    An ALLOWLIST, and that is the whole point.  ``app.host`` is an input, so honouring it
    verbatim would turn a self-check into a general-purpose outbound client aimed
    wherever that input pointed, reporting healthy because some other machine is.  The
    destination is therefore SELECTED from a fixed set of loopback forms: a wildcard or
    ``localhost`` becomes ``127.0.0.1``, an IPv6 loopback becomes bracketed ``[::1]``, an
    address already in 127.0.0.0/8 is used as given, and anything else is replaced with
    ``127.0.0.1`` and one fixed-category warning.  ``localhost`` is MAPPED and never
    resolved, so a hosts-file entry cannot redirect a self-check off this machine
    (RFC 6761).

    Replacing rather than refusing is deliberate: refusing would report the application
    unhealthy because its BIND address is unusual, when the listener may be serving
    perfectly on an interface this probe may not dial.  All three probes select alike.
    """
    candidate = (host or "").strip()
    lowered = candidate.lower()
    if lowered in WILDCARD_HOSTS or lowered == LOOPBACK_NAME:
        return LOOPBACK_HOST
    if lowered in IPV6_LOOPBACK_FORMS:
        return LOOPBACK_AUTHORITY_V6
    if _is_ipv4_loopback(candidate):
        return candidate
    log_warning("probe target is not loopback; probing loopback instead")
    return LOOPBACK_HOST


def _unique_pairs(pairs):
    """Build an object from JSON member pairs, refusing a repeated key.

    :func:`json.loads` keeps the LAST value for a duplicated key and says nothing,
    turning a contradictory document into a plausible one: a body carrying
    ``"status":"DOWN","status":"UP"`` would be graded healthy.  RFC 8259 calls such an
    object's behaviour unpredictable, so the probe refuses it rather than picking a
    member on the endpoint's behalf, as the Node and Java readers also do.

    :raises ValueError: when any key appears more than once.
    """
    document = {}
    for key, value in pairs:
        if key in document:
            raise ValueError("the body repeats a key")
        document[key] = value
    return document


def probe_rejection(status, body):
    """Return why an answer fails to prove health, or ``None`` when it proves it.

    Separated from the transport so every rule is reachable by a direct call and all
    three implementations can be held to the same wording: an operator greps one
    deployment's logs, not one language's.

    The rule ORDER below is part of the contract: the cheapest checks come first, and
    ``status`` is examined before the three descriptive fields so an endpoint reporting
    itself down is reported as down rather than as whichever other field also happened
    to be wrong.  ``timestamp`` and ``version`` are matched by FORMAT, never by value.

    Every rule is stated against a PARSED document, which is what makes the verdict
    fail closed: a truncated body that happens to contain the bytes ``"status":"UP"``
    proves nothing and is refused.
    """
    if len(body) > MAX_PROBE_BODY_BYTES:
        return f"body exceeds the probe limit of {MAX_PROBE_BODY_BYTES} bytes"
    if status != 200:
        return f"the endpoint answered status {int(status)}"
    try:
        document = json.loads(body.decode("utf-8"), object_pairs_hook=_unique_pairs)
    except (UnicodeDecodeError, ValueError):
        # JSONDecodeError covers malformed JSON, trailing content and a repeated key;
        # UnicodeDecodeError a body that is not UTF-8.  All mean the same to a probe.
        return "body is not the expected JSON document"
    if not isinstance(document, dict):
        return "body is not a JSON object and carries no status field"
    if list(document) != list(PROBE_KEY_ORDER):
        return PROBE_KEY_SET_REASON
    if document["status"] != HEALTH_STATUS:
        return "the status field is not the expected value"
    name = document["name"]
    if not isinstance(name, str) or not name:
        return "the name field is not a non-empty string"
    version = document["version"]
    if not isinstance(version, str) or not VERSION_GRAMMAR.match(version):
        return "the version field is not a three-part dotted numeric version"
    timestamp = document["timestamp"]
    if not isinstance(timestamp, str) or not TIMESTAMP_GRAMMAR.match(timestamp):
        return "the timestamp field is not a whole-second UTC instant"
    return None


def sole_media_type(content_types):
    """Reduce an answer's ``Content-Type`` values to the ONE media type they name.

    Returns ``""`` when they name none unambiguously, which covers two answers that
    are indistinguishable to a probe: no such field at all, and more than one of them.

    Requiring EXACTLY one is what keeps the three implementations in step, because
    their clients disagree about what a repeated ``Content-Type`` means - measured on
    the wire, :mod:`http.client` joins the values with ``", "``, Node keeps the first
    and discards the rest, and the JDK client exposes every one - so grading whichever
    value a client happened to surface would let one implementation accept a
    duplicated header the other two refused.

    Parameters are stripped and the result folded and trimmed: RFC 9110 section 8.3.1
    makes ``application/json; charset=utf-8`` the same media type as
    ``application/json``, section 5.6.2 permits whitespace around a field value, and
    section 8.3 defines the type and subtype as case-insensitive tokens.  The same
    reduction is what ``scripts/verify-health.sh`` applies to the served header.
    """
    values = list(content_types or ())
    if len(values) != 1 or not isinstance(values[0], str):
        return ""
    return values[0].split(";", 1)[0].strip().lower()


def identity_rejection(content_types, body, expected_name, expected_version):
    """Return why an answer is not THIS application's, or ``None`` when it is.

    Runs after :func:`probe_rejection`, never instead of it, and answers the question
    that grader cannot: :func:`probe_rejection` proves an answer satisfies the frozen
    contract, which ANY application implementing the contract would satisfy.  On its
    own it therefore grades a different process that happens to hold this loopback
    port healthy, and reports this application up while it is down.  ``--probe`` is
    the container health check, so that verdict keeps a dead container in service -
    which is the one outcome a health check exists to prevent.

    Three rules, in this order:

      1. the answer is served as :data:`CONTENT_TYPE`, unambiguously - a well-formed
         health document delivered as ``text/html`` did not come from this contract;
      2. ``name`` is exactly the configured application name;
      3. ``version`` is exactly the configured application version.

    Media type first because it is settled by the FRAMING rather than by the document,
    and the identity in a document is not worth grading when the framing around it
    already says the answer is something else.

    No rule names an observed value.  A response body is an input, and an input
    reaching a log line verbatim is how a forged log entry gets written, so the
    reasons state only the expectation the configuration already published.

    The body is parsed here as well as in :func:`probe_rejection`, deliberately: this
    function must be total for a direct call, so it cannot depend on a caller having
    parsed first.  The parse is PLAIN - the strict rules (a repeated key, a trailing
    byte, a sequence that is not UTF-8) belong to :func:`probe_rejection` alone,
    because restating them here would change which of two simultaneous faults is
    reported.
    """
    if sole_media_type(content_types) != CONTENT_TYPE:
        return f"the answer is not served as {CONTENT_TYPE}"
    try:
        document = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return "body is not the expected JSON document"
    if not isinstance(document, dict):
        return "body is not a JSON object and carries no status field"
    if document.get("name") != expected_name:
        return "the name field is not this application's name"
    if document.get("version") != expected_version:
        return "the version field is not this application's version"
    return None


def _probe_answer(host, port, route, target):
    """GET the health answer from a loopback endpoint, bounded twice.

    Two independent limits apply, because either alone can be defeated: an ABSOLUTE
    deadline covering connect, headers and body together, and a ceiling of
    :data:`MAX_PROBE_BODY_BYTES` on the body.  A per-read timeout cannot do the first -
    a peer sending one byte just inside every timeout satisfies all of them and keeps
    the probe alive indefinitely - so the deadline is enforced once, by a timer that
    shuts the socket down under the read in progress.  The ceiling is requested plus one
    byte, so an endpoint that streams without end is bounded in MEMORY as well as in
    time and :func:`probe_rejection` can still see the limit was passed.

    :class:`http.client.HTTPConnection` rather than :func:`urllib.request.urlopen` for
    two reasons: it exposes the socket, which is what the deadline has to act on, and it
    consults no proxy configuration.  ``urlopen``'s default opener reads ``HTTP_PROXY``
    from the environment, which was demonstrated to let an injected variable answer a
    self-check for a process that was not running.

    Every failure resolves to ``None`` with one fixed-category line logged, carrying at
    most the sanitised probe TARGET and an exception TYPE name - never an exception
    message, which can carry resolver-derived or response-derived text.

    :returns: ``(status, body, content_types)`` on any answer at all, where the third
        element is EVERY ``Content-Type`` field value the answer carried, in order -
        the values rather than a joined string, because :func:`sole_media_type` grades
        a repeated field and cannot see the repetition once it has been joined.
    """
    expired = threading.Event()
    connection = http.client.HTTPConnection(host, port, timeout=PROBE_TIMEOUT_SECONDS)

    def expire():
        """Fire the absolute deadline: mark it, then interrupt the transfer."""
        expired.set()
        active = connection.sock
        if active is not None:
            # Shutdown makes the read in progress return immediately; closing here
            # instead would race the owning thread, which still uses the object.
            try:
                active.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    watchdog = threading.Timer(PROBE_TIMEOUT_SECONDS, expire)
    watchdog.daemon = True
    watchdog.start()
    try:
        connection.request(METHOD_GET, route, headers={"Accept": CONTENT_TYPE})
        response = connection.getresponse()
        # get_all rather than getheader: getheader joins repeated values with ", ",
        # which makes a duplicated Content-Type indistinguishable from a single
        # parameterised one, and that distinction is a rule identity_rejection applies.
        return (
            response.status,
            response.read(MAX_PROBE_BODY_BYTES + 1),
            tuple(response.headers.get_all("Content-Type") or ()),
        )
    except http.client.InvalidURL:
        # The client layer refuses to place some characters in a request line.  Listed
        # before the catch-all so it is reported as the configuration fault it is.
        log_warning("probe cannot run: the configured health path is not a valid request target")
        return None
    except Exception as exc:  # fail closed on every transport outcome
        if expired.is_set():
            log_warning("probe rejected: no response within the probe deadline")
        else:
            log_warning(f"probe could not reach {target}: {type(exc).__name__}")
        return None
    finally:
        # Cancelling a fired timer is a no-op, so this is correct on every path, and
        # closing releases the descriptor however the exchange ended.
        watchdog.cancel()
        connection.close()


def probe(config=None):
    """Check this application's own health endpoint.

    Deliberately strict: healthy only when the endpoint answers ``200``, the body is a
    JSON object satisfying the frozen contract, AND the answer identifies itself as
    this application - :func:`probe_rejection` followed by :func:`identity_rejection`.
    Every other outcome - refused connection, expired deadline, wrong status, oversized
    or unparseable body, a document that merely looks right, a well-formed document
    from something else on this port, anything unforeseen - is unhealthy, because a
    probe that cannot PROVE health must not report it.

    The identity step exists because the contract grader cannot supply it: a document
    satisfying the contract is what any conforming implementation serves, so without
    it a different process holding this loopback port would vouch for this one.  The
    expectation is taken from :func:`build_payload`, not restated, so the two can never
    disagree about what this application publishes.

    The body ceiling has an operational edge worth knowing here: see
    :data:`MAX_PROBE_BODY_BYTES` for the ``app.name`` budget past which this
    application's own healthy answer is refused for being too large.

    Step order is what makes the diagnostics safe: the configuration is validated first,
    so a value carrying a CR and an LF is refused before it can be interpolated
    anywhere; the destination is then selected from the loopback allowlist rather than
    derived; the port and route are checked; and only then is a request made.

    :returns: 0 when healthy, 1 when not. Suitable for :func:`sys.exit`.
    """
    resolved = load_config() if config is None else config
    # The same validation the server applies: a probe that accepted a configuration the
    # server refuses would report a process healthy that cannot start.  The message
    # names the offending KEY and never its value, so it is safe to print verbatim.
    try:
        validate_config(resolved)
    except ValueError as exc:
        log_warning(f"probe cannot run: {exc}")
        return 1

    host = probe_authority(resolved.get("app.host") or DEFAULTS["app.host"])
    # Validated rather than interpolated as text: a misconfigured port reported as
    # "unreachable" sends an operator looking for a network fault instead of a typo.
    try:
        port = _as_port(resolved.get(PORT_KEY) or DEFAULTS[PORT_KEY])
    except ValueError:
        log_warning("probe cannot run: the configured port is unusable")
        return 1
    # Re-checked because the value reaching this line has been NORMALISED.
    route = health_route(resolved)
    if not is_request_target(route):
        log_warning("probe cannot run: the configured health path is not a valid request target")
        return 1

    target = sanitize_for_log(f"http://{host}:{port}{route}")
    answer = _probe_answer(host, port, route, target)
    if answer is None:
        return 1
    reason = probe_rejection(answer[0], answer[1])
    if reason is None:
        # The frozen contract holds.  Now prove the answer came from THIS application
        # rather than from whatever else could be holding this loopback port: the
        # expectation is what build_payload would publish, so a server built from this
        # same configuration always matches and nothing else is assumed to.
        published = build_payload(resolved)
        reason = identity_rejection(
            answer[2], answer[1], published["name"], published["version"]
        )
    if reason is not None:
        log_warning(f"probe rejected: {reason}")
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
