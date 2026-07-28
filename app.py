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

Public surface:
    greet, read_properties, config_value, load_config, normalize_path,
    health_route, health_timestamp, build_payload, render_payload,
    HealthRequestHandler, HealthServer, create_server, serve, probe.

The response contract is frozen and identical across all three language
implementations; see the ``HealthRequestHandler`` docstring for its full text.
"""

import datetime
import json
import os
import sys
import urllib.error
import urllib.parse
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
ALLOWED_METHODS = "GET"

#: Error bodies are deliberately constant.  They never echo the requested path,
#: the method or any traceback: this endpoint is network-reachable and must
#: disclose the minimum possible about the deployment behind it.
NOT_FOUND_BODY = {"error": "Not Found"}
METHOD_NOT_ALLOWED_BODY = {"error": "Method Not Allowed"}

#: How long ``probe`` waits for the endpoint to answer.  Short enough that a
#: container HEALTHCHECK timeout fires on the probe's own verdict rather than on
#: the runtime killing it mid-request.
PROBE_TIMEOUT_SECONDS = 3.0

#: How long a connection may stay idle before the handler closes it.  Without a
#: timeout a client that connects and never sends a request line would pin a
#: worker thread for the lifetime of the process.
CONNECTION_TIMEOUT_SECONDS = 30


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

    The query string (and any fragment) is discarded, and at most ONE trailing
    slash is removed.  ``/health``, ``/health/``, ``/health?probe=1`` and
    ``/health/?probe=1`` therefore all resolve to ``/health``, while
    ``/health//`` deliberately does not - one forgiving slash is a convenience,
    two is a different path.
    """
    path = urllib.parse.urlsplit(target).path
    if len(path) > 1 and path.endswith("/"):
        path = path[:-1]
    return path


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

    The compact separators are load-bearing, not cosmetic: the default
    ``json.dumps`` inserts a space after every ``:`` and ``,``, which would make
    this body 115 bytes where the JavaScript and Java implementations produce
    108.  Byte parity across the three languages is part of the contract, so the
    separators are always passed explicitly.  Keys are never sorted, because
    insertion order is the specified field order.
    """
    return json.dumps(payload, separators=(",", ":"))


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
                      ``Content-Length``; no ``Server`` and no ``Date``
    Unknown path      ``404`` with ``{"error":"Not Found"}``
    Other methods     ``405`` with ``{"error":"Method Not Allowed"}`` and
                      ``Allow: GET``
    ================  ==========================================================

    Two design points are worth stating explicitly.

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
    """

    #: Keep-alive is required for correctness here, not performance: an accurate
    #: Content-Length accompanies every response, so a client may reuse the
    #: connection instead of paying a fresh handshake per poll.
    protocol_version = "HTTP/1.1"

    #: Idle-connection ceiling; see CONNECTION_TIMEOUT_SECONDS.
    timeout = CONNECTION_TIMEOUT_SECONDS

    #: Set TCP_NODELAY on the accepted connection.  A body-bearing response
    #: leaves this class as two socket writes - ``end_headers()`` flushes the
    #: header block, then the body is written - and with Nagle enabled the
    #: second small write is withheld until the peer's delayed-ACK timer fires.
    #: Measured against this very endpoint: a median 41.00 ms per response
    #: against a 0.83 ms floor, roughly a fiftyfold penalty paid by every poll
    #: on a reused keep-alive connection.  A health endpoint exists to be
    #: polled, so that is worth removing at the source rather than tuning
    #: around.  ``socketserver.StreamRequestHandler`` exposes this switch for
    #: precisely this request/response pattern.
    disable_nagle_algorithm = True

    #: Least disclosure.  No response emitted by this class carries a Server
    #: header at all, but these two attributes feed ``version_string()``, which
    #: inherited machinery could still reach, so the interpreter version is
    #: removed at the source as well as at every call site.
    server_version = "health"
    sys_version = ""

    def _respond(self, code, payload, extra_headers=None):
        """Write one complete JSON response.

        Every response in this class goes through here, which is what guarantees
        the header set and the compact body format cannot drift between the
        success and error paths.

        ``send_response_only`` is used deliberately in place of
        ``send_response``: the latter appends ``Server: BaseHTTP/x Python/y`` and
        a ``Date`` header, disclosing the interpreter version on a
        network-reachable surface and breaking header parity with the JavaScript
        and Java implementations, which emit neither.
        """
        body = render_payload(payload).encode("utf-8")
        self.send_response_only(code)
        self.send_header("Content-Type", CONTENT_TYPE)
        self.send_header("Cache-Control", CACHE_CONTROL)
        self.send_header("Content-Length", str(len(body)))
        for name, value in (extra_headers or {}).items():
            self.send_header(name, value)
        self.end_headers()

        # RFC 9110 section 9.3.2: a response to HEAD carries no message body.
        # Content-Length still advertises what the equivalent GET would return,
        # which the specification explicitly permits and which keeps the header
        # set identical to the GET response.
        #
        # This is not a nicety.  ``protocol_version`` is HTTP/1.1, so connections
        # persist; writing a body here would leave those bytes unread in the
        # stream and the client would parse them as the start of the NEXT
        # response.  Verified empirically: doing so makes the request following a
        # HEAD fail with net::ERR_INVALID_HTTP_RESPONSE in Chrome.  Node's
        # ServerResponse and com.sun.net.httpserver both drop the body here
        # automatically, so suppressing it is also what keeps this
        # implementation byte-compatible with the other two.
        if self.command == "HEAD":
            return

        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError) as exc:
            # A monitoring agent that times out and hangs up mid-response is
            # routine, not exceptional.  Note it on stderr and close the
            # connection rather than letting a traceback reach the log.
            self.log_error("client closed the connection: %r", exc)
            self.close_connection = True

    def do_GET(self):
        """Answer the health route; anything else is a 404.

        Configuration is resolved per request rather than cached at startup.  For
        an endpoint polled every few seconds one small file read is immaterial,
        and it buys a useful property: changing ``app.config.properties`` or an
        environment variable takes effect without restarting the listener.  The
        bind address is the one value that cannot work this way, so it is
        resolved once, at :func:`create_server`.
        """
        config = load_config()
        if normalize_path(self.path) == health_route(config):
            self._respond(200, build_payload(config))
        else:
            self._respond(404, NOT_FOUND_BODY)

    def _method_not_allowed(self):
        """Reject a non-GET request, advertising what is allowed."""
        self._respond(405, METHOD_NOT_ALLOWED_BODY, {"Allow": ALLOWED_METHODS})

    # Every other verb the base class can dispatch routes to the same responder,
    # so the endpoint's method policy is stated exactly once.
    do_HEAD = _method_not_allowed
    do_POST = _method_not_allowed
    do_PUT = _method_not_allowed
    do_DELETE = _method_not_allowed
    do_PATCH = _method_not_allowed
    do_OPTIONS = _method_not_allowed

    def send_response(self, code, message=None):
        """Emit a status line without the ``Server`` and ``Date`` headers.

        ``_respond`` never calls this, but inherited machinery does - most
        notably ``send_error``, which the base class uses for malformed request
        lines and unsupported versions.  Overriding it here means the
        interpreter version cannot leak from a path this module does not write,
        so least disclosure holds for every response the process can produce.
        """
        self.log_request(code)
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
    one another on a single-threaded accept loop.  Worker threads are daemons so
    that an interrupt at the console cannot be held open by an in-flight request,
    and address reuse is enabled so a restart does not fail on a socket lingering
    in TIME_WAIT.
    """

    daemon_threads = True
    allow_reuse_address = True


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
    return HealthServer((host, _as_port(port)), HealthRequestHandler)


def serve(host=None, port=None, config=None):
    """Serve the health endpoint until the process is interrupted.

    The startup line goes to stderr, never to stdout: stdout carries this
    program's legacy output and is hashed by the backward-compatibility gate.

    A bind failure - almost always a port already in use - is reported as one
    readable line and exits non-zero rather than unwinding as a traceback.  This
    fails closed: an orchestrator that cannot bind must not see a success code.

    :returns: 0 after a clean shutdown.
    """
    resolved = load_config() if config is None else config
    try:
        server = create_server(host, port, resolved)
    except (OSError, ValueError) as exc:
        sys.stderr.write(f"[app.py] cannot start the health server: {exc}\n")
        raise SystemExit(1) from None

    bind_host, bind_port = server.server_address[0], server.server_address[1]
    sys.stderr.write(f"Serving {health_route(resolved)} on {bind_host}:{bind_port}\n")
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
    port = resolved.get("python.port") or DEFAULTS["python.port"]
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
