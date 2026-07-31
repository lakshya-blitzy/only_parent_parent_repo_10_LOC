import json
import os
import signal
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Identity reported by the health endpoint. A name and a version describe the
# artifact rather than the host it runs on, so neither is environment-overridable.
APP_NAME = "greeter-app"
APP_VERSION = "1.0.0"

# Bind defaults, overridable through the HEALTH_HOST and HEALTH_PORT environment
# variables so that the endpoint needs no configuration file. Loopback only: this
# program has no deployment target, so keeping the listener off external
# interfaces is the correct default.
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8000

# The only route served, and the only methods answered on it.
HEALTH_PATH = "/health"
HEALTH_METHODS = "GET, HEAD"


def greet(name):
    return f"Hello {name}"


def health_timestamp():
    # RFC 3339 / ISO 8601 UTC with millisecond precision and a trailing "Z", for
    # example 2026-07-31T08:43:36.492Z. timespec="milliseconds" is required:
    # CPython renders microseconds by default, which would not match the Node and
    # Java implementations of this same contract. Built per call, never cached.
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def health_payload():
    # Exactly four string members and nothing else: no hostname, no filesystem
    # path, no environment data, no diagnostics. Dictionaries preserve insertion
    # order, so the contract order name, version, timestamp, status is guaranteed
    # by construction.
    return {
        "name": APP_NAME,
        "version": APP_VERSION,
        "timestamp": health_timestamp(),
        "status": "UP",
    }


class HealthRequestHandler(BaseHTTPRequestHandler):
    # HTTP/1.1 keeps the connection alive between requests, so every response
    # below declares an explicit Content-Length.
    protocol_version = "HTTP/1.1"

    # A bounded read timeout keeps an idle or half-open client from holding a
    # worker thread for the lifetime of the process.
    timeout = 10

    # BaseHTTPRequestHandler answers an unsupported verb or a malformed request
    # line with an HTML page that quotes what the client sent. Re-point that
    # template at a compact JSON document so that every response this program can
    # emit is JSON and no request content reaches any response body. The status
    # line is the one place the standard library still names the offending token,
    # which it renders with %r: a request-line token cannot contain a carriage
    # return, a line feed, a space or a tab, and control characters come back
    # escaped, so that reason phrase can neither split a response nor inject a
    # header, and it discloses nothing except the client's own input.
    error_content_type = "application/json"
    error_message_format = '{"status":"ERROR","code":%(code)d}'

    def handle(self):
        # A probe that hangs up part way through is routine for a health endpoint,
        # and it can surface while the request line is read, while the headers are
        # flushed or while the body is written. Left alone, the socket layer answers
        # every one of those with a full traceback that also discloses interpreter
        # and source paths. Report the peer going away on one line instead, and let
        # every other exception propagate so real faults stay visible.
        try:
            super().handle()
        except ConnectionError as error:
            self.close_connection = True
            self.log_message("client disconnected: %s", error)

    def do_GET(self):
        self.dispatch(with_body=True)

    def do_HEAD(self):
        # A response to HEAD carries the headers of the equivalent GET, no body.
        self.dispatch(with_body=False)

    def do_POST(self):
        self.reject_method()

    # Every other method a client may send is answered exactly like POST.
    do_PUT = do_POST
    do_PATCH = do_POST
    do_DELETE = do_POST
    do_OPTIONS = do_POST
    do_TRACE = do_POST

    def dispatch(self, with_body):
        if self.request_path() == HEALTH_PATH:
            self.send_json(200, health_payload(), with_body=with_body)
        else:
            self.send_json(404, {"status": "NOT_FOUND"}, with_body=with_body)

    def reject_method(self):
        # Closing after a rejected request means an unread request payload can
        # never be misread as the start of the next request on this connection.
        if self.request_path() == HEALTH_PATH:
            self.send_json(405, {"status": "METHOD_NOT_ALLOWED"}, allow=HEALTH_METHODS, close=True)
        else:
            self.send_json(404, {"status": "NOT_FOUND"}, close=True)

    def request_path(self):
        # Route on the path alone, so /health?probe=1 still matches while /health/
        # and every other path do not.
        return self.path.split("?", 1)[0].split("#", 1)[0]

    def send_json(self, status, payload, with_body=True, allow=None, close=False):
        # Compact separators are part of the wire contract: the default ", " and
        # ": " separators pad the document with whitespace and would make this
        # response structurally different from the Node and Java responses.
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        # The length a GET would return, sent for HEAD as well so that both
        # methods answer with identical headers.
        self.send_header("Content-Length", str(len(body)))
        # The timestamp is generated per request, so a cached copy is worthless.
        self.send_header("Cache-Control", "no-store")
        if allow is not None:
            self.send_header("Allow", allow)
        if close:
            self.send_header("Connection", "close")
        self.end_headers()
        if with_body:
            self.wfile.write(body)

    def log_message(self, format, *args):
        # Access lines and handler diagnostics belong on file descriptor 2 so that
        # descriptor 1 carries only this program's original output.
        stamp = self.log_date_time_string()
        client = self.address_string()
        sys.stderr.write(f"{APP_NAME}: [{stamp}] {client} {format % args}\n")
        sys.stderr.flush()


class InvalidHealthConfig(Exception):
    # Raised when an environment override is present but unusable. It carries the
    # operator-facing sentence, so the caller can report the fault on one line and
    # exit non-zero without a traceback ever reaching the operator.
    pass


def health_port():
    port = os.environ.get("HEALTH_PORT", "").strip()
    if not port:
        # Unset, empty or whitespace-only means "not overridden", which is not a
        # fault: the documented default applies, silently.
        return DEFAULT_PORT
    try:
        number = int(port)
    except ValueError:
        # Rejected by the check below, which reports every unusable value alike.
        number = None
    if number is None or not 0 < number < 65536:
        # An override that is present but cannot be honoured is a configuration
        # fault, so startup stops here. Falling back to the default would answer a
        # typo by silently moving the endpoint off the port that was asked for: the
        # process would report itself UP while the probe watching the intended port
        # saw nothing at all. Failing fast makes that mistake impossible to miss,
        # and !r keeps a value carrying spaces or control characters both visible
        # and confined to a single line.
        raise InvalidHealthConfig(
            f"invalid HEALTH_PORT {port!r}: expected an integer from 1 to 65535")
    return number


def stop_on_signal(signum, frame):
    # Signal handlers run on the main thread, so raising from here unwinds
    # serve_forever and reaches the shutdown path below. Calling server.shutdown()
    # from a handler would deadlock instead, because it waits for the very loop the
    # handler interrupted.
    raise KeyboardInterrupt


def serve_health():
    host = os.environ.get("HEALTH_HOST", "").strip() or DEFAULT_HOST
    try:
        port = health_port()
    except InvalidHealthConfig as error:
        # Reported in the same shape as the bind failure below, one readable line
        # and a non-zero status, so that no listener is ever started on an address
        # nobody asked for.
        sys.stderr.write(f"{APP_NAME}: {error}\n")
        sys.stderr.flush()
        return 1
    try:
        server = ThreadingHTTPServer((host, port), HealthRequestHandler)
    except OSError as error:
        # Binding is the first operation in this program that can fail for reasons
        # outside its control, most often because the port is already taken. One
        # readable line and a non-zero status serve the operator better than a
        # traceback.
        sys.stderr.write(f"{APP_NAME}: cannot bind {host}:{port}: {error}\n")
        sys.stderr.flush()
        return 1
    # SIGINT already arrives as KeyboardInterrupt, so turning SIGTERM into the same
    # exception lets both signals leave serve_forever through one shutdown path.
    signal.signal(signal.SIGTERM, stop_on_signal)
    sys.stderr.write(f"{APP_NAME} {APP_VERSION} serving {HEALTH_PATH} on http://{host}:{port}\n")
    sys.stderr.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        sys.stderr.write(f"{APP_NAME}: shutting down\n")
        sys.stderr.flush()
    finally:
        # Releases the listening socket, so the port is free the moment this
        # process ends. Request threads are daemons and never delay this call.
        server.server_close()
    return 0


if __name__ == "__main__":
    user = "Lakshya"
    print(greet(user))
    # Opt-in server: the endpoint runs only when --serve is passed, so the default
    # invocation above stays byte-for-byte what it has always been. The flush
    # matters because CPython block-buffers a redirected standard output, which
    # would otherwise hide the line above for the whole life of the server.
    if "--serve" in sys.argv[1:]:
        sys.stdout.flush()
        sys.exit(serve_health())
