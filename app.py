import json
import os
import signal
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APP_NAME = "greeter-app"
APP_VERSION = "1.0.0"

# Loopback only: with no deployment target, the listener stays off external interfaces.
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8000

HEALTH_PATH = "/health"
HEALTH_METHODS = "GET, HEAD"

# One explicit grammar for the overrides - ASCII blanks trimmed, and a port written in
# ASCII decimal digits only - so every application reading them accepts the same values.
ASCII_BLANKS = " \t\n\v\f\r"
PORT_MAX_DIGITS = 5


def greet(name):
    return f"Hello {name}"


def health_timestamp():
    # timespec is explicit because the default rendering is microseconds, not the
    # contract's milliseconds.
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def health_payload():
    # Insertion order is the contract order, and these four members are the whole document.
    return {
        "name": APP_NAME,
        "version": APP_VERSION,
        "timestamp": health_timestamp(),
        "status": "UP",
    }


def log_safe(text):
    # Escaping every unprintable character keeps a diagnostic to one line and stops
    # supplied text from forging a log record.
    return "".join(ch if ch.isprintable() else ch.encode("unicode_escape").decode("ascii")
                   for ch in text)


def report(text):
    # The one sink for diagnostics: descriptor 2 only, so descriptor 1 keeps carrying
    # just the output this program has always produced.
    sys.stderr.write(log_safe(text) + "\n")
    sys.stderr.flush()


class HealthRequestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # A request line carrying no version leaves the version at this default, and the
    # framework suppresses the status line and every header whenever that default is the
    # 0.9 one it ships. Naming 1.1 here is what lets a version-less or unreadable request
    # line be answered with a status line, application/json and Cache-Control instead of a
    # bare body. A request line that does carry a version overwrites this before any
    # response is written, so nothing else about the contract changes.
    default_request_version = "HTTP/1.1"

    # A bounded read timeout keeps an idle or half-open client from holding a
    # worker thread for the lifetime of the process.
    timeout = 10

    # Re-points the framework's HTML error page at JSON, so every response this program
    # can emit is JSON and no request text reaches a response body.
    error_content_type = "application/json"
    error_message_format = '{"status":"ERROR","code":%(code)d}'

    def handle(self):
        # A probe hanging up mid-request is routine, so it is reported on one line
        # instead of raising; every other exception still propagates.
        try:
            super().handle()
        except ConnectionError as error:
            self.close_connection = True
            self.log_event(f"client disconnected: {error}")

    def do_GET(self):
        self.dispatch(with_body=True)

    def do_HEAD(self):
        self.dispatch(with_body=False)

    def __getattr__(self, name):
        # Every method token other than GET and HEAD resolves here, so one rejection path
        # answers any of them rather than the framework's own 501. The prefix test keeps
        # the hook narrow: any other missing attribute still raises.
        if name.startswith("do_") and len(name) > len("do_"):
            return self.reject_method
        raise AttributeError(f"{type(self).__name__} has no attribute {name!r}")

    def dispatch(self, with_body):
        # A declared payload closes the connection rather than being read, so the bytes
        # framing it can never be misread as the request after it on a kept-alive
        # connection. Nothing on this path consumes a request payload, and the framework
        # does not consume one either, so leaving the connection reusable would let a
        # body of its own composing be parsed as a second request and answered a second
        # time. Closing is what the rejected-method path below and the other two
        # applications already do with a declared payload.
        close = self.declares_body()
        if self.request_path() == HEALTH_PATH:
            self.send_json(200, health_payload(), with_body=with_body, close=close)
        else:
            self.send_json(404, {"status": "NOT_FOUND"}, with_body=with_body, close=close)

    def reject_method(self):
        # Closing after a rejected request means an unread request payload can
        # never be misread as the start of the next request on this connection.
        if self.request_path() == HEALTH_PATH:
            self.send_json(405, {"status": "METHOD_NOT_ALLOWED"}, allow=HEALTH_METHODS, close=True)
        else:
            self.send_json(404, {"status": "NOT_FOUND"}, close=True)

    def request_path(self):
        # The target exactly as the request line carried it, because the parsed target is
        # not it: the framework rewrites a target that opens with two slashes down to one
        # before any handler is reached, so //health, ///health and //x/health would all be
        # answered as aliases of /health that this contract does not declare. The request
        # line is recorded before that rewrite, so the target is read back from it here.
        # Only the query string is dropped, so /health?probe=1 matches while /health/ and
        # /health#extra are targets this program does not serve - the same rule the other
        # two applications apply to their own request target.
        words = self.requestline.split()
        if len(words) < 2:
            return ""
        return words[1].split("?", 1)[0]

    def declares_body(self):
        # Whether this request announced a payload, which is the question the framing of
        # the request after it on this connection turns on. Every answer here is yes
        # unless the request plainly announced nothing, so a header this program cannot
        # read counts as a payload rather than as an absent one.
        if self.headers.get_all("Transfer-Encoding"):
            return True
        declared = self.headers.get_all("Content-Length")
        if not declared:
            return False
        if len(declared) > 1:
            # More than one length frames the request more than one way, so it counts as
            # a payload whatever the values are.
            return True
        length = declared[0].strip(ASCII_BLANKS)
        if not length:
            return False
        # isascii() is what makes isdigit() mean [0-9], so no fullwidth digit passes as a
        # length here. Tested digit by digit rather than converted, so a length too long
        # for any conversion still answers this question instead of failing to parse.
        if not (length.isascii() and length.isdigit()):
            return True
        return any(digit != "0" for digit in length)

    def send_response(self, code, message=None):
        # Sends the status line, the redacted access record, Date and the cache directive,
        # but not the inherited Server header, whose value would name the library and
        # interpreter version on every response. Every response this program writes passes
        # through here, including the framework's own send_error() ones, which is why the
        # cache directive is set here rather than alongside the other headers: the document
        # is generated per request, so none of them may be served from a cache.
        #
        # The reason phrase is dropped rather than passed on, so the status line always
        # carries the one registered for the code. The framework composes its own phrase for
        # a request it cannot parse by quoting the request line inside it, which would
        # reflect whatever bytes a client sent back to that client on the first line of the
        # response; withholding it keeps request text out of every part of every reply, not
        # only the body.
        self.log_request(code)
        self.send_response_only(code)
        self.send_header("Date", self.date_time_string())
        self.send_header("Cache-Control", "no-store")

    def send_json(self, status, payload, with_body=True, allow=None, close=False):
        # Compact separators are part of the wire contract: the default ", " and ": "
        # would pad the document.
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if allow is not None:
            self.send_header("Allow", allow)
        if close:
            self.send_header("Connection", "close")
        self.end_headers()
        if with_body:
            self.wfile.write(body)

    def log_event(self, event):
        # The one record sink: only text this class composes reaches it, never anything
        # taken from a request.
        report(f"{APP_NAME}: [{self.log_date_time_string()}] {event}")

    def log_request(self, code="-", size="-"):
        # Records the status alone, because the framework's own access line quotes the
        # request target and prefixes the peer address, neither of which belongs in a log.
        self.log_event(f"responded {int(code) if isinstance(code, int) else code}")

    def log_error(self, format, *args):
        # The framework's diagnosis of an unparseable request quotes the offending token,
        # so the arguments are dropped and only the fact is recorded.
        self.log_event("request could not be served as sent")

    def log_message(self, format, *args):
        # Unreachable while both framework callers are overridden above, and still
        # withholds its arguments because they would arrive with request text in them.
        self.log_event("standard library diagnostic withheld")


class HealthServer(ThreadingHTTPServer):
    # A thread per connection, and one sink for exceptions the handler did not catch.

    def handle_error(self, request, client_address):
        # Reports the exception type on one line instead of socketserver's peer address
        # and traceback; swallowing it here, as the default does, keeps one failed request
        # from stopping the listener.
        error = sys.exc_info()[1]
        report(f"{APP_NAME}: request handling failed: {type(error).__name__}")


class InvalidHealthConfig(Exception):
    pass


def health_host():
    host = os.environ.get("HEALTH_HOST", "").strip(ASCII_BLANKS)
    if not host:
        return DEFAULT_HOST
    if not host.isprintable() or any(ch.isspace() for ch in host):
        # A host name or address carries no blanks and no control characters, so anything
        # else is refused rather than repaired, and the rejected value is never quoted
        # back into a diagnostic.
        raise InvalidHealthConfig(
            "invalid HEALTH_HOST: expected a host name or address with no blanks and no "
            "control characters")
    return host


def health_port():
    port = os.environ.get("HEALTH_PORT", "").strip(ASCII_BLANKS)
    if not port:
        return DEFAULT_PORT
    # One to five ASCII decimal digits and nothing else, checked before any conversion:
    # isascii() is what makes isdigit() mean [0-9], so no fullwidth digit reaches int().
    if len(port) <= PORT_MAX_DIGITS and port.isascii() and port.isdigit():
        number = int(port)
        if 0 < number < 65536:
            return number
    # A present but unusable override stops startup rather than silently binding the
    # default, and the rejected value is not quoted back.
    raise InvalidHealthConfig(
        f"invalid HEALTH_PORT: expected 1 to {PORT_MAX_DIGITS} decimal digits denoting "
        "a port from 1 to 65535")


def stop_on_signal(signum, frame):
    # Raising unwinds into the single shutdown path in serve_health below, where calling
    # server.shutdown() from here would deadlock on the loop this handler interrupted.
    # Restoring the default action first makes the path idempotent under a second signal.
    signal.signal(signal.SIGINT, signal.SIG_DFL)
    signal.signal(signal.SIGTERM, signal.SIG_DFL)
    raise KeyboardInterrupt


def serve_health():
    server = None
    try:
        # Armed inside this block, before the first step that can fail or block, so a
        # signal arriving during startup - including while these two lines run - still
        # leaves through the shutdown path below.
        signal.signal(signal.SIGINT, stop_on_signal)
        signal.signal(signal.SIGTERM, stop_on_signal)
        try:
            host = health_host()
            port = health_port()
        except InvalidHealthConfig as error:
            report(f"{APP_NAME}: {error}")
            return 1
        try:
            server = HealthServer((host, port), HealthRequestHandler)
        except OSError as error:
            # One readable line and a non-zero status instead of a traceback. A host that
            # arrived in HEALTH_HOST is named by its variable rather than quoted, since
            # only the resolver can discover that the word names nothing.
            if host == DEFAULT_HOST:
                where = f"{host}:{port}"
            else:
                where = f"the address named by HEALTH_HOST, port {port}"
            report(f"{APP_NAME}: cannot bind {where}: {error}")
            return 1
        report(f"{APP_NAME} {APP_VERSION} serving {HEALTH_PATH} on http://{host}:{port}")
        server.serve_forever()
    except KeyboardInterrupt:
        report(f"{APP_NAME}: shutting down")
    finally:
        if server is not None:
            server.server_close()
    return 0


if __name__ == "__main__":
    user = "Lakshya"
    print(greet(user))
    # Opt-in: the flush is what puts the line above on a redirected descriptor 1 before
    # this process becomes long-running.
    if "--serve" in sys.argv[1:]:
        sys.stdout.flush()
        sys.exit(serve_health())
