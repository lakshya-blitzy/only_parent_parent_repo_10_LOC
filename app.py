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

The listener is :class:`http.server.ThreadingHTTPServer` driving a
:class:`http.server.BaseHTTPRequestHandler` subclass, which is the whole HTTP
implementation: this module owns routing and the response, and the standard
library owns the wire.  Nothing here parses a request line, bounds a header
block or drains a body, because the endpoint answers exactly three responses and
no more - a 200 on the health route, a 404 on anything else, and a 405 on any
method other than GET.  Every response is written with
:meth:`~http.server.BaseHTTPRequestHandler.send_response_only` rather than
``send_response``, which is what keeps the ``Server`` banner and the ``Date``
header off a network-reachable surface.

Public surface:
    greet, read_properties, config_value, load_config, normalize_path,
    health_route, health_timestamp, build_payload, render_payload,
    HealthRequestHandler, HealthServer, create_server, serve, probe.

The response contract is frozen and identical across all three language
implementations; see the ``HealthRequestHandler`` docstring for its full text.
"""

import datetime
import http.client
import json
import os
import re
import socket
import sys
import threading
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
WILDCARD_HOSTS = frozenset({"0.0.0.0", "::", "[::]", "*", ""})
LOOPBACK_HOST = "127.0.0.1"

#: The IPv6 loopback address and the authority form that carries it in a URL.
#: Brackets are required so the colons cannot be misread as a port separator.
LOOPBACK_HOST_V6 = "::1"
LOOPBACK_AUTHORITY_V6 = "[::1]"

#: Every spelling of IPv6 loopback accepted, because a properties file may write
#: the address either compressed or expanded and both name the same interface.
IPV6_LOOPBACK_FORMS = frozenset({"::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]"})

#: The one host NAME treated as loopback.  RFC 6761 reserves it for exactly that,
#: and it is MAPPED to the numeric address rather than resolved, so a hosts-file
#: entry cannot redirect a self-check off this machine.
LOOPBACK_NAME = "localhost"

#: Every IPv4 address in 127.0.0.0/8 is loopback, so a listener deliberately bound
#: to, say, 127.0.0.2 is still probed at the address it is actually on.
IPV4_LOOPBACK_PREFIX = "127."

#: The shared port grammar.  Plain ASCII decimal with an optional sign, written as
#: an explicit character class rather than ``\d`` because ``\d`` also matches
#: Arabic-Indic and other Unicode digits that :func:`int` would then accept - a
#: value the JavaScript and Java implementations both refuse.
PORT_GRAMMAR = re.compile(r"^[+-]?[0-9]+$")

#: The frozen three-part dotted version contract from the response schema.
VERSION_GRAMMAR = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")

#: The frozen timestamp contract: a fixed-width UTC instant truncated to whole
#: seconds with a ``Z`` designator.  Written as an explicit character class for the
#: same reason :data:`PORT_GRAMMAR` is, and used only to assert the SHAPE of a
#: field - never its value, which is the one non-deterministic part of the payload.
TIMESTAMP_GRAMMAR = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
)

#: The character range a request target may be made of: visible US-ASCII only.
#: Space (0x20) and every control character are therefore excluded, which is what
#: stops a configured route from carrying a CR and an LF - a header-injection
#: primitive the moment such a value reaches a request line or a log line.
TARGET_MIN_CHAR = 0x21
TARGET_MAX_CHAR = 0x7E

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

#: The two request header names the drain consults, written once so the lookup
#: and the documentation cannot disagree.  ``email.message.Message`` - which
#: backs ``self.headers`` - matches names case-insensitively, so the canonical
#: spelling here matches a client that sends any casing.
HEADER_CONTENT_LENGTH = "Content-Length"
HEADER_TRANSFER_ENCODING = "Transfer-Encoding"

#: Error bodies are deliberately constant.  They never echo the requested path,
#: the method or any traceback: this endpoint is network-reachable and must
#: disclose the minimum possible about the deployment behind it.
NOT_FOUND_BODY = {"error": "Not Found"}
METHOD_NOT_ALLOWED_BODY = {"error": "Method Not Allowed"}

#: The HTTP version the listener speaks, used as the handler's
#: ``protocol_version``.  HTTP/1.1 is what makes ``Content-Length`` meaningful to
#: a client that keeps the connection open, and every response this endpoint
#: writes carries an accurate one.
HTTP_VERSION = "HTTP/1.1"

#: How long ``probe`` waits for the endpoint to answer.  Short enough that a
#: container HEALTHCHECK timeout fires on the probe's own verdict rather than on
#: the runtime killing it mid-request.
PROBE_TIMEOUT_SECONDS = 3.0

#: Largest health document ``probe`` will read.  The contract body is 108 bytes, so
#: this is roughly seventy times the largest legitimate answer and exists only to
#: bound memory: a local endpoint that streams without end must be refused rather
#: than accumulated.
MAX_PROBE_BODY_BYTES = 8192

#: Ceiling on the request body this server will read and discard before it
#: answers, and the size of the scratch buffer it discards through.  The Java
#: implementation applies the identical ceiling in ``drainRequestBody``.
#:
#: A refused request still ARRIVES with a body, and ``BaseHTTPRequestHandler``
#: never reads one - it hands the socket to ``do_POST`` with the body still
#: queued.  Left there on a keep-alive connection, those bytes are consumed as
#: the START of the next request line: a three-byte body in front of a following
#: ``GET`` is parsed as the method ``xyzGET``, which answers 501 with an HTML
#: body carrying ``Server`` and ``Date`` headers - three separate departures from
#: the frozen contract - and swallows the legitimate request that followed.
#: Draining first is what keeps the connection honest.
MAX_REQUEST_DRAIN_BYTES = 8 * 1024 * 1024
DRAIN_BUFFER_BYTES = 8192

#: Wall-clock budget for the drain, mirroring the JavaScript listener's
#: ``requestTimeout`` - the sibling budget on the total time to receive one
#: complete request, body included.
#:
#: Draining is a BLOCKING read, so it needs a bound of its own.  A client that
#: promises a hundred body bytes, sends three and then says nothing would
#: otherwise park a handler thread for the process's lifetime; the listener is
#: threaded, so other connections keep being served, but a peer that opens many
#: such connections retains a thread for each.  The bound is applied to the
#: socket around the drain read alone and lifted immediately afterwards, so the
#: idle wait for the next request on a kept-alive connection keeps whatever
#: behaviour it had - a bound there would surface as a ``Request timed out``
#: diagnostic from the inherited request loop on a perfectly ordinary idle
#: connection.
REQUEST_DRAIN_TIMEOUT_SECONDS = 15.0

#: The key set and the key ORDER the probe requires of an answer, and the single
#: wording all three implementations use when an answer does not carry them.  The
#: reason string is pinned as a constant because an operator greps one
#: deployment's logs rather than one language's: a Python ``repr`` of a list would
#: print apostrophes and spaces and Java's ``List.toString`` would drop the quotes
#: entirely, so the byte-exact form is written out here instead of rendered.
PROBE_KEY_ORDER = ("name", "version", "timestamp", "status")
PROBE_KEY_SET_REASON = (
    'body does not carry exactly the keys '
    '["name","version","timestamp","status"] in order'
)

#: The route every configured health path reduces to when it is empty.
ROOT_PATH = "/"

#: Marks an absolute-form request target, as in ``GET http://host/health``.
#:
#: RFC 9112 section 3.2.2 requires a server to accept this form even though almost
#: no client emits it, and the three implementations here reduce it to its path so
#: that the same request reaches the same route in all of them.
SCHEME_SEPARATOR = "://"

#: The characters a URI fragment is introduced by.
#:
#: A real request target never carries one - RFC 9110 section 7.1 has the client
#: strip it before sending - so this is stripped defensively, and because the same
#: function normalises the CONFIGURED health path, where one could be written by
#: hand.
FRAGMENT_MARKER = "#"


# --------------------------------------------------------------------------- #
# Diagnostics
#
# Everything this module says about itself leaves through one function, and it
# leaves on stderr.  Two guarantees follow from that being a single exit rather
# than a convention every call site has to remember: standard output stays
# byte-identical to the legacy contract the backward-compatibility gate hashes,
# and no configured value can forge a log line.
# --------------------------------------------------------------------------- #


def log_warning(message):
    """Write one diagnostic line to stderr, and to stderr only.

    Nothing reaches standard output.  Standard output is the legacy contract of
    this program and is hashed by the backward-compatibility gate, so a stray
    write there is a build failure rather than a cosmetic slip.

    No caller can forge a log line either.  The text is stripped of control
    characters by :func:`sanitize_for_log` before the newline is appended, so a
    configured value carrying a CR and an LF cannot produce a second line in
    whatever collects this process's stderr, and a terminal escape sequence
    cannot rewrite what an operator sees.  Callers pass fixed category text plus,
    at most, an HTTP status code or an exception TYPE name - never a configured
    value, an exception message or response content - so a line discloses nothing
    about the deployment even before this stripping runs.

    :param message: the diagnostic, without a trailing newline.
    :returns: ``None``.
    """
    sys.stderr.write(f"[app.py] {sanitize_for_log(message)}\n")


def sanitize_for_log(text):
    """Strip control characters from text before it reaches a log line.

    The defence in depth behind :func:`log_warning`.  Every call site is required
    to pass fixed category text, so in principle nothing untrusted reaches a log
    line at all; this function is what makes that a guarantee rather than a
    convention, because the inherited request machinery also logs through
    :meth:`HealthRequestHandler.log_message` and its text is request-derived.  A
    value carrying a CR or an LF would forge additional log lines, and one
    carrying a terminal escape sequence would rewrite what an operator sees.
    Every character below ``0x20`` and DEL is therefore dropped rather than
    escaped: this text exists to be read by a human, and a stripped character
    reads better than an escape.

    :param text: the value to make safe for a single log line.
    :returns: the value with every control character removed.
    """
    if not text:
        return ""
    return "".join(
        current for current in text if ord(current) >= 0x20 and ord(current) != 0x7F
    )


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
    to the defaults rather than taking the process down.  What that report does
    NOT carry is the path or the exception text: a filesystem layout is a
    deployment detail, and the exception message embeds the very path the line is
    meant to withhold.  The category is enough to send an operator to the file,
    and the file is one command away from them.

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
    except (OSError, UnicodeDecodeError):
        log_warning("cannot read the configuration file; using defaults")
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


def is_single_line_text(text):
    """Return ``True`` when text is non-empty and carries no control character.

    The rule for the two configured values that are neither a route nor a number:
    a name that appears in the published payload, and a host that appears in a
    diagnostic.  For both, the only real constraint is that they can be printed on
    one line - which is also what stops either of them forging a second one.
    """
    if not text:
        return False
    for current in text:
        point = ord(current)
        if point < 0x20 or point == 0x7F:
            return False
    return True


def is_request_target(candidate):
    """Report whether a target is made only of characters allowed in one.

    Every character must be visible US-ASCII, :data:`TARGET_MIN_CHAR` through
    :data:`TARGET_MAX_CHAR`.  Applied to the CONFIGURED route rather than to an
    inbound request - reading an inbound request is the platform server's job -
    so that a health path carrying a space, a CR or an LF is refused where it is
    configured instead of becoming an injected request line in :func:`probe`.
    """
    if not candidate:
        return False
    for current in candidate:
        if not TARGET_MIN_CHAR <= ord(current) <= TARGET_MAX_CHAR:
            return False
    return True


def validate_config(config):
    """Refuse a configuration this endpoint must not publish.

    Configuration is an input, and every value it carries appears either in the
    public health document or in the route that serves it.  Without this check a
    non-empty but malformed value was accepted verbatim: ``APP_VERSION`` of
    ``not-a-version`` was served inside a ``200`` response whose ``status`` field
    read ``UP``, so the endpoint attested to its own health while describing
    itself in a form no consumer of the frozen contract could parse.  A health
    endpoint that reports success while breaking its own contract is worse than
    one that refuses to start, because nothing downstream can tell.

    Four rules, identical in all three implementations:

    * ``app.name`` is non-empty and carries no control character.  It is a payload
      field, and a control character in it would break the single-line
      diagnostics and the single-line startup banner.
    * ``app.version`` matches :data:`VERSION_GRAMMAR` exactly.
    * ``health.path`` begins with ``/`` and is a valid request target.
    * ``app.host`` is non-empty and carries no control character.

    A key the mapping does not carry at all falls back to its built-in default,
    which is what :func:`load_config` would have supplied; a key that IS present
    is validated as it stands, so an explicitly empty value is a fault rather than
    an invitation to substitute a default.

    No message quotes the offending value.  The key names the setting, which is
    all an operator needs to find it, and withholding the value is what lets
    :func:`probe` print this message verbatim without a configured string
    reaching a log line.  The port is deliberately NOT validated here: it is
    checked by :func:`_as_port` at the point of use, where the failure can be
    reported as the transport fault it is.

    :param config: mapping from :func:`load_config`.
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
    if not isinstance(path, str) or not path.startswith(ROOT_PATH) or not is_request_target(path):
        raise ValueError("invalid health.path: it is not a valid request target")

    host = config.get("app.host", DEFAULTS["app.host"])
    if not is_single_line_text(host):
        raise ValueError(
            "invalid app.host: it must be non-empty text with no control character"
        )


def _as_port(value):
    """Coerce a configured port to a valid TCP port number.

    Configuration arrives as text, so a typo must fail with a message that names
    the offending value instead of surfacing as an opaque error from deep inside
    the socket layer.  Port 0 is permitted: it is how a test binds an ephemeral
    port and then reads the assignment back from the server.

    The grammar is checked before the conversion, and it is deliberately narrower
    than what ``int()`` accepts.  ``int()`` honours PEP 515 digit separators, so
    it reads ``8_001`` as 8001, and it accepts any Unicode decimal digit, so it
    reads the Arabic-Indic ``\u0668\u0660\u0660\u0661`` as 8001 as well.  Both
    were measured: Python bound port 8001 for ``PORT=8_001`` while Node and Java
    refused the same value and exited non-zero.  One deployment, one configured
    value, three different outcomes - and the two that refused were the ones
    behaving correctly.  :data:`PORT_GRAMMAR` is therefore the shared grammar all
    three implementations now apply: an optional sign, then ASCII digits, and
    nothing else.

    :raises ValueError: when the value is not an ASCII decimal integer in
        0-65535.
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

    Two transformations, in this order: the query string is discarded, and at
    most ONE trailing slash is removed.  ``/health``, ``/health/``,
    ``/health?probe=1``, ``/health/?probe=1`` and ``/health?`` therefore all
    resolve to ``/health``, while ``/health//`` deliberately does not - one
    forgiving slash is a convenience, two describe a different path.

    Nothing else is rewritten, and that is the contract rather than an omission:
    no percent-decoding, so ``/health%2f`` is a different path; no dot-segment
    resolution, so ``/health/../health`` answers 404; no collapsing of leading
    slashes.  Each of those would let a caller reach the endpoint by a name that
    is not the configured one, which makes the route ambiguous for anything
    downstream that matches on a path - a proxy rule, an access log, a rate
    limiter.  The JavaScript and Java implementations perform exactly the same
    four reductions on the target they are handed - authority, query, fragment and
    one trailing slash - in exactly this order.

    One platform behaviour reaches this function before it runs, and it is worth
    stating rather than discovering: :meth:`http.server.BaseHTTPRequestHandler
    .parse_request` rewrites a target that begins with ``//`` down to a single
    leading slash - a deliberate CPython change (gh-87389) that protects against
    open redirects, because a client reads ``//path`` as a scheme-less absolute
    URI.  ``//health`` therefore reaches this endpoint under CPython, while the
    Node and Java servers answer it 404.  It is not correctable from here: the
    rewrite happens in the request parser this module is required to delegate
    to, and a target beginning with ``//`` is not one of the cases the frozen
    contract describes.  Each of the three implementations answers it the way its
    platform server does, and each of the three answers every target the contract
    *does* describe identically.

    :param target: the request target as it arrived, e.g. ``/health?probe=1``.
    :returns: the path to match against :func:`health_route`.
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
    """Return ``True`` for A-Z and a-z only, never for a non-ASCII letter.

    :param current: a single character.
    :returns: ``True`` when the character is an unaccented ASCII letter.
    """
    return "a" <= current <= "z" or "A" <= current <= "Z"


def _is_scheme(candidate):
    """Return ``True`` when a string is a URI scheme as RFC 3986 defines one.

    ALPHA followed by any number of ALPHA, DIGIT, ``+``, ``-`` or ``.``.

    :param candidate: the text before ``://`` in a request target.
    :returns: ``True`` when it could be a scheme.
    """
    if not candidate or not _is_ascii_letter(candidate[0]):
        return False
    for current in candidate[1:]:
        if not (_is_ascii_letter(current) or "0" <= current <= "9"
                or current in "+-."):
            return False
    return True


def strip_authority(target):
    """Reduce an absolute-form request target to its path component.

    ``GET http://host:8000/health HTTP/1.1`` is a request shape RFC 9112 section
    3.2.2 requires a server to accept, and it is the only shape in which the
    target carries a scheme and an authority.  Reducing it here is what makes the
    absolute form reach the same route the origin form reaches - and what makes
    this module agree with ``index.js`` and ``User.java``, which perform the
    identical reduction.

    The scheme is VALIDATED before anything is stripped, and that ordering is the
    whole safety of this function: a relative target whose query string happens to
    contain ``://`` - a redirect parameter such as
    ``/health?next=http://elsewhere/`` - has ``/health?next=http`` before the
    separator, which is not a scheme, so it is returned completely untouched.

    :param target: the request target as it arrived.
    :returns: the path component of an absolute-form target, or the target itself.
    """
    separator = target.find(SCHEME_SEPARATOR)
    if separator <= 0 or not _is_scheme(target[:separator]):
        return target
    authority_start = separator + len(SCHEME_SEPARATOR)
    path_start = target.find("/", authority_start)
    return ROOT_PATH if path_start < 0 else target[path_start:]


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
# HTTP surface
# --------------------------------------------------------------------------- #


class HealthRequestHandler(BaseHTTPRequestHandler):
    """Serve the frozen health contract, and nothing else.

    ================  ==========================================================
    Route             the configured health path, default ``/health``; query
                      string stripped, one optional trailing slash accepted
    Method            GET only
    Success           ``200`` with the compact four-key JSON document
    Headers           ``Content-Type: application/json``,
                      ``Cache-Control: no-cache, no-store, must-revalidate``,
                      ``Content-Length`` - exactly these three and no others, so
                      no ``Server`` banner and no ``Date``
    Unknown path      ``404`` with ``{"error":"Not Found"}``
    Other methods     ``405`` with ``{"error":"Method Not Allowed"}`` and
                      ``Allow: GET``
    ================  ==========================================================

    Three responses, and no fourth.  Reading the request and framing the
    connection are :mod:`http.server`'s job; this class only decides which of the
    three to write.  The method policy is written as one explicit ``do_*`` method
    per verb rather than as an alias chain or an attribute lookup, so the set of
    verbs that reach the 405 responder can be read off the class in one glance.

    Three design points are worth stating explicitly.

    A health response must never be served from a cache - a cached "healthy" is
    worse than no answer at all - hence the unconditional no-store directive on
    every response, the error paths included.

    A response to HEAD carries no body (RFC 9110 section 9.3.2), so the body is
    withheld for that verb while ``Content-Length`` still advertises what the
    equivalent GET would return.  The specification permits exactly that, and it
    keeps the header set identical across methods.

    Nothing this class writes reaches standard output.  Standard output is this
    program's legacy contract and is hashed by the backward-compatibility gate,
    so the request log and every diagnostic go to stderr instead.
    """

    #: HTTP/1.1, so that ``Content-Length`` means to a client what this handler
    #: intends it to mean.  Every response written here carries an accurate one.
    protocol_version = HTTP_VERSION

    #: Keeps the interpreter version out of the ``Server`` header that the
    #: inherited protocol-error path would otherwise emit.  The three contract
    #: responses send no ``Server`` header at all - see :meth:`_write` - so this
    #: only narrows disclosure on a rejection this module never writes itself.
    sys_version = ""

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

    def _drain_request_body(self):
        """Read and discard the request body, so the connection stays honest.

        This runs before every response on every path, mirroring the Java
        implementation, which drains at the top of its exchange handler under the
        same :data:`MAX_REQUEST_DRAIN_BYTES` ceiling.  Node needs no counterpart:
        it dumps an unconsumed request itself once the response finishes.

        Three cases cannot be drained, and each retires the connection instead of
        guessing at where the body ends:

        * a chunked body, because decoding one needs a chunked reader this
          server deliberately does not carry;
        * a ``Content-Length`` that is not a plain non-negative decimal;
        * a length above the ceiling, which is refused rather than read - reading
          it to be polite is the unbounded read a limit exists to prevent.

        Retiring the connection is always safe: the response is already complete
        and self-describing, and a client that wanted another exchange opens
        another connection.
        """
        if self.command == METHOD_GET or self.command == METHOD_HEAD:
            # Neither verb carries a body in any request this endpoint serves,
            # and reading zero bytes still costs a syscall on every health poll.
            if HEADER_CONTENT_LENGTH not in self.headers and \
                    HEADER_TRANSFER_ENCODING not in self.headers:
                return
        if HEADER_TRANSFER_ENCODING in self.headers:
            self.close_connection = True
            return
        raw = self.headers.get(HEADER_CONTENT_LENGTH)
        if raw is None:
            # No length and not chunked: HTTP/1.1 says there is no body.
            return
        stated = raw.strip()
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
                    # The client promised more than it sent.  Nothing is left to
                    # misparse, but the framing is broken, so retire the socket.
                    self.close_connection = True
                    return
                remaining -= len(chunk)
        except OSError:
            # A timeout arrives here too, TimeoutError being an OSError.  Either
            # way the body is only partly consumed, so the connection can no
            # longer be trusted to frame a following request: retire it.  No
            # diagnostic is written - the response about to be sent is the
            # answer, and a slow client is not this endpoint's news to report.
            self.close_connection = True
        finally:
            try:
                self.connection.settimeout(restore)
            except OSError:
                self.close_connection = True

    def _write(self, status, body, send_allow=False):
        """Write one complete response: status line, three headers, body.

        ``send_response_only`` is used rather than ``send_response`` - the
        obvious call to reach for - because the convenient one appends a
        ``Server`` header carrying the interpreter version and a ``Date`` header.
        Neither belongs on a network-reachable surface whose header set is
        frozen at three, and both would diverge from the JavaScript
        implementation, which suppresses the same two.

        ``Content-Length`` is the ENCODED byte length rather than the character
        count, so a multi-byte character in a configured value cannot
        desynchronise the advertised length from the body.

        A client that hangs up mid-response - a monitoring agent whose timeout
        fired, most often - is routine rather than exceptional: it is noted on
        stderr and the connection is retired, instead of surfacing as a traceback
        in whatever collects this process's output.

        :param status: 200, 404 or 405.
        :param body: the complete response body, already compact JSON.
        :param send_allow: add ``Allow: GET``, for the 405 path.
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
            self.log_error("client closed the connection: %r", exc)

    def _respond(self, code, payload, send_allow=False):
        """Serialise a payload and write it as a contract response.

        Kept as the single entry point for the 200, 404 and 405 paths so that the
        body format and the header set cannot drift between them.
        """
        self._write(code, render_payload(payload), send_allow=send_allow)

    def do_GET(self):
        """Answer the health route; anything else is a 404."""
        config, route = self._snapshot()
        if normalize_path(self.path) == route:
            self._respond(200, build_payload(config))
        else:
            self._respond(404, NOT_FOUND_BODY)

    def _method_not_allowed(self):
        """Reject a non-GET request, advertising what is allowed.

        The body is constant: it echoes neither the method nor the path, because
        this endpoint is network-reachable and discloses the minimum possible
        about the deployment behind it.
        """
        self._respond(405, METHOD_NOT_ALLOWED_BODY, send_allow=True)

    def do_HEAD(self):
        """HEAD is refused like every other non-GET verb.

        RFC 9110 expects HEAD wherever GET is supported, so this is a deliberate,
        documented deviation rather than an oversight: it keeps the contract at
        one method across all three language implementations, and no consumer
        identified in the requirements issues a HEAD.  The response carries the
        headers and no body, as a HEAD response must.
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
        """OPTIONS is refused, with ``Allow: GET`` stating the policy anyway.

        A 405 carrying ``Allow`` conveys the same fact a 204 with ``Allow``
        would, and keeps every non-GET verb on one code path.
        """
        self._method_not_allowed()

    def log_message(self, fmt, *args):
        """Send every server log line to stderr, through the one safe emitter.

        Standard output is the legacy contract of this program and is hashed by
        the backward-compatibility gate, so nothing the server says may go there.

        The text reaching this method is REQUEST-derived - the inherited machinery
        logs the request line it was sent - which makes it the one log path in this
        module carrying a value an outside caller chose.  It is therefore routed
        through :func:`log_warning` rather than written directly, so a request line
        carrying a CR and an LF cannot forge a second entry in whatever collects
        this process's stderr.
        """
        log_warning(fmt % args)


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

    The configuration is validated BEFORE the socket is bound, so an
    unpublishable document can never be served: a server that bound a port and
    then answered ``200``/``UP`` with a malformed version would have published the
    very thing the validation exists to refuse, and would have looked healthy
    while doing it.  Failing here also means the caller's ``except`` block, rather
    than a monitoring system three layers away, is what learns about the typo.

    :param host: bind address; the configured ``app.host`` when omitted.
    :param port: bind port; the configured ``python.port`` when omitted. 0 binds
        an ephemeral port.
    :param config: mapping from :func:`load_config`; loaded when omitted.
    :raises ValueError: when the configuration cannot be published, or when the
        resolved port is not a valid port number.
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
        # Routed through log_warning rather than written directly, so that the one
        # diagnostic that can carry an exception message is sanitised like every
        # other.  The bytes are unchanged - log_warning writes the same prefix - and
        # the property becomes structural instead of a convention.
        log_warning(f"cannot start the health server: {exc}")
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
        log_warning("interrupted; shutting down")
    finally:
        server.shutdown()
        server.server_close()
    return 0


# --------------------------------------------------------------------------- #
# Self-check
#
# The container HEALTHCHECK, written in-process precisely so that the image needs
# no HTTP client of its own: neither the slim Python image nor the JRE image ships
# curl or wget, and adding one would enlarge the image, widen its attack surface
# and hand a post-exploitation attacker a download-and-run helper.
#
# A probe is a CLIENT, and a client is only as safe as its behaviour against a
# peer that does not cooperate.  Three properties are therefore built in rather
# than assumed: the destination is selected from a loopback allowlist and never
# derived from configuration, the exchange is bounded in time AND in bytes, and
# the verdict comes from parsing the document against the frozen contract rather
# than from looking for a fragment inside it.
# --------------------------------------------------------------------------- #


def _is_digits(candidate):
    """Return ``True`` when every character is an ASCII digit and there is one.

    ASCII only, deliberately: :meth:`str.isdigit` admits Arabic-Indic and other
    Unicode decimal digits, and a near-miss address spelled with one of them must
    not reach :func:`int` and be graded loopback.
    """
    if not candidate:
        return False
    for current in candidate:
        if not "0" <= current <= "9":
            return False
    return True


def _is_ipv4_loopback(candidate):
    """Report whether a string is a dotted-quad IPv4 address in 127.0.0.0/8.

    Written out rather than delegated to :mod:`ipaddress` for the same reason
    :func:`normalize_path` is written out rather than delegated to
    :mod:`urllib.parse`: a general address parser accepts spellings this module
    has no reason to accept - ``127.1``, ``0x7f.0.0.1``, a bare decimal integer -
    and each of them is a different way to write a destination the allowlist would
    then have to reason about.  Four decimal octets or nothing.

    :param candidate: the configured host, already stripped of surrounding space.
    :returns: ``True`` only for ``127.b.c.d`` with four octets in 0-255.
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

    This is an ALLOWLIST, and that is the whole point.  ``app.host`` is an input:
    it comes from a properties file and from ``APP_HOST`` in the environment, both
    of which an operator, an orchestrator or a compromised sidecar can set.
    Rewriting only the wildcard spellings and using everything else verbatim would
    make the container HEALTHCHECK a general-purpose outbound HTTP client aimed
    wherever that input pointed - a probe that reports healthy because some other
    machine is healthy, and an egress request the deployment never asked for.  So
    the destination is not derived from the configured value at all; it is
    SELECTED from a fixed set of loopback forms, and a value outside that set is
    replaced rather than honoured.

    ==========================  ==========================================
    Configured ``app.host``     Probe destination
    ==========================  ==========================================
    unset, empty, ``0.0.0.0``,  ``127.0.0.1`` - a wildcard names every
    ``::``, ``[::]``, ``*``     interface and is not itself a destination
    ``localhost``               ``127.0.0.1`` - mapped, never resolved, so
                                a hosts-file entry cannot redirect a
                                self-check off this machine (RFC 6761)
    ``127.0.0.1`` .. any        itself - a listener deliberately bound to
    address in 127.0.0.0/8      127.0.0.2 is probed where it actually is
    ``::1``, ``[::1]``, the     ``[::1]`` - bracketed so its colons cannot
    expanded IPv6 form          be misread as a port separator
    anything else               ``127.0.0.1``, with one fixed-category
                                warning; the configured value is neither
                                used nor logged
    ==========================  ==========================================

    Replacing rather than refusing is deliberate.  A refusal would report the
    application unhealthy because its BIND address is unusual, which is a
    misdiagnosis: the listener may well be serving perfectly on an interface this
    probe is not allowed to dial.  Probing loopback answers the question the probe
    is actually asking - is the process in this container serving? - and the
    warning is what tells an operator the configured value was not used.  The
    JavaScript and Java probes apply the identical table, so all three dial the
    same destination for any given configuration.

    :param host: the configured bind address; ``None``, the empty string and
        whitespace are all treated as a wildcard bind.
    :returns: ``127.0.0.1``, ``[::1]``, or a 127.0.0.0/8 address as configured.
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
    which silently turns a contradictory document into a plausible one: a body
    carrying ``"status":"DOWN","status":"UP"`` would be graded healthy.  RFC 8259
    calls such an object's behaviour unpredictable, so the probe refuses it rather
    than picking a member for the endpoint.  The Java reader refuses it too, which
    is what keeps the three verdicts identical.

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

    Separated from the transport so that every rule below is reachable by a direct
    call, and so that all three implementations can be held to the same wording:
    an operator greps one deployment's logs, not one language's.

    The rules, in the order they are applied.  The order matters and is part of the
    contract: the CHEAPEST and most fundamental checks come first, and the status
    field is examined before the three descriptive fields so that an endpoint
    reporting itself down is reported as down rather than as whichever of its other
    fields happened also to be wrong.

    #. the body fits inside :data:`MAX_PROBE_BODY_BYTES`;
    #. the response code is exactly 200 - the IETF health-check draft couples a
       passing status to a 2xx code, and this contract narrows that to one code;
    #. the body is JSON, decodes as UTF-8, carries no repeated key and has nothing
       trailing it;
    #. the body is a JSON OBJECT;
    #. it carries exactly :data:`PROBE_KEY_ORDER`, in that order;
    #. ``status`` equals :data:`HEALTH_STATUS`;
    #. ``name`` is a non-empty string, ``version`` matches
       :data:`VERSION_GRAMMAR`, and ``timestamp`` matches
       :data:`TIMESTAMP_GRAMMAR` - the timestamp by FORMAT, never by value.

    A parse is what makes this fail closed.  The defect this replaces tested the
    raw body for the fragment ``"status":"UP"``, so a truncated, unparseable body
    that happened to contain those bytes was graded healthy; every rule here is
    stated against a parsed document instead.

    :param status: the HTTP status code the endpoint answered with.
    :param body: the response body as received, already bounded by the caller.
    :returns: a fixed-category reason string, or ``None`` when the answer is good.
    """
    if len(body) > MAX_PROBE_BODY_BYTES:
        return f"body exceeds the probe limit of {MAX_PROBE_BODY_BYTES} bytes"
    if status != 200:
        return f"the endpoint answered status {int(status)}"
    try:
        document = json.loads(body.decode("utf-8"), object_pairs_hook=_unique_pairs)
    except (UnicodeDecodeError, ValueError):
        # json.JSONDecodeError (a ValueError) covers malformed JSON, trailing
        # content and a repeated key; UnicodeDecodeError covers a body that is not
        # UTF-8 at all.  All of them mean the same thing to a probe.
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


def _probe_answer(host, port, route, target):
    """GET the health answer from a loopback endpoint, bounded twice.

    Two independent limits apply, because either one alone can be defeated:

    * an ABSOLUTE deadline of :data:`PROBE_TIMEOUT_SECONDS`, armed before the
      connection is attempted and covering connect, response headers and response
      body together.  A per-read timeout cannot do this - a peer that sends one
      byte just inside every timeout satisfies all of them and keeps the probe
      alive for as long as it likes - so the deadline is enforced once, across the
      whole exchange, by a timer that shuts the socket down under the read in
      progress;
    * a ceiling of :data:`MAX_PROBE_BODY_BYTES` on the body.  One byte more than
      the ceiling is requested, so an endpoint that streams without end is bounded
      in MEMORY as well as in time and :func:`probe_rejection` can still see that
      the limit was passed.

    :class:`http.client.HTTPConnection` is used rather than
    :func:`urllib.request.urlopen` for two independent reasons.  It exposes the
    socket, and the socket is what the deadline has to act on - ``urlopen`` owns
    its transport privately and offers no way to interrupt a read already under
    way.  And it consults no proxy configuration: ``urlopen``'s default opener
    reads ``HTTP_PROXY`` out of the environment, which was demonstrated to let an
    injected variable answer a self-check on behalf of a process that was not
    running at all.  A loopback self-check must never be proxied.

    Every failure resolves to ``None`` with exactly one line logged, and every
    diagnostic is a fixed category.  The only variable content permitted in a line
    is the probe TARGET, which this process constructed and sanitised, and an
    exception TYPE name - never an exception message, which can carry
    resolver-derived or response-derived text.

    :param host: a loopback authority from :func:`probe_authority`.
    :param port: a validated port number.
    :param route: a validated request target.
    :param target: the sanitised URL, for the one diagnostic that names it.
    :returns: ``(status, body)``, or ``None`` when no answer could be obtained.
    """
    expired = threading.Event()
    connection = http.client.HTTPConnection(host, port, timeout=PROBE_TIMEOUT_SECONDS)

    def expire():
        """Fire the absolute deadline: mark it, then interrupt the transfer."""
        expired.set()
        active = connection.sock
        if active is not None:
            # Shutting the socket down makes the read in progress return or fail
            # immediately.  Closing it here instead would race the owning thread,
            # which is still using the object; shutdown only retires the channel.
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
        return response.status, response.read(MAX_PROBE_BODY_BYTES + 1)
    except http.client.InvalidURL:
        # A route reaching this point carries a character the client layer refuses
        # to place in a request line.  Listed before the catch-all, which it would
        # otherwise fall into, so it is reported as the configuration fault it is.
        log_warning("probe cannot run: the configured health path is not a valid request target")
        return None
    except Exception as exc:  # fail closed on every transport outcome
        if expired.is_set():
            log_warning("probe rejected: no response within the probe deadline")
        else:
            log_warning(f"probe could not reach {target}: {type(exc).__name__}")
        return None
    finally:
        # Cancelling a timer that has already fired is a no-op, so this is correct
        # on every path, and closing the connection releases the descriptor whether
        # the exchange completed, expired or never started.
        watchdog.cancel()
        connection.close()


def probe(config=None):
    """Check this application's own health endpoint.

    Deliberately strict: the verdict is healthy only when the endpoint answers
    ``200`` AND the body is a JSON object satisfying the frozen contract.  Every
    other outcome - refused connection, expired deadline, wrong status code,
    oversized body, unparseable body, a document that merely looks right, anything
    unforeseen - is unhealthy, because a probe that cannot PROVE health must not
    report it.

    The order of the four steps is what makes the diagnostics safe.  The
    configuration is validated first, so a value carrying a CR and an LF is
    refused before it can be interpolated into anything; the destination is then
    selected from the loopback allowlist rather than derived; the port and the
    route are checked; and only then is a request made.

    :param config: mapping from :func:`load_config`; loaded when omitted.
    :returns: 0 when healthy, 1 when not. Suitable for :func:`sys.exit`.
    """
    resolved = load_config() if config is None else config
    # The same validation the server applies, applied here too.  A probe that
    # accepted a configuration the server refuses would report a process healthy
    # that cannot start, which is the most misleading verdict available.  The
    # message names the offending KEY and never quotes its value, so this line is
    # safe to print verbatim.
    try:
        validate_config(resolved)
    except ValueError as exc:
        log_warning(f"probe cannot run: {exc}")
        return 1

    host = probe_authority(resolved.get("app.host") or DEFAULTS["app.host"])
    # The port is validated rather than interpolated as text.  A misconfigured
    # value would otherwise be reported as "unreachable", which sends an operator
    # looking for a network fault instead of at the typo.  The offending value is
    # not named: the category says which setting is wrong, and the setting itself
    # is one command away from the operator reading the line.
    try:
        port = _as_port(resolved.get(PORT_KEY) or DEFAULTS[PORT_KEY])
    except ValueError:
        log_warning("probe cannot run: the configured port is unusable")
        return 1
    # The route is checked against the same predicate validate_config applies, and
    # for the same reason: the value reaching this line has been normalised, so it
    # is not the string validation already saw.
    route = health_route(resolved)
    if not is_request_target(route):
        log_warning("probe cannot run: the configured health path is not a valid request target")
        return 1

    target = sanitize_for_log(f"http://{host}:{port}{route}")
    answer = _probe_answer(host, port, route, target)
    if answer is None:
        return 1
    reason = probe_rejection(answer[0], answer[1])
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
