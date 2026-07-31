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

# The blanks stripped from an override before it is read, and the digits a port may be
# written with. Both sets are spelled out rather than left to str.strip() and int(),
# because those two disagree with the JavaScript facilities index.js would otherwise
# reach for: str.strip() removes U+0085 where trim() does not and leaves U+FEFF where
# trim() removes it, and int() accepts underscore separators, a leading sign and any
# Unicode decimal digit, so HEALTH_PORT=1_9433 silently started this application on
# port 19433 while index.js refused the same value. An override has to mean the same
# thing to every application that reads it, so the grammar is stated here instead of
# inherited from a language.
ASCII_BLANKS = " \t\n\v\f\r"
PORT_MAX_DIGITS = 5


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


def log_safe(text):
    # Every line this program writes to file descriptor 2 is rendered through here
    # first. A carriage return, a line feed or a Unicode line separator reaching a
    # log stream lets whoever supplied the text forge additional records, and a
    # terminal escape reaching a terminal is acted on rather than printed, so any
    # character that is not printable is replaced by its escape sequence instead.
    # Printable text - which is all a host name, an address, an operator-facing
    # sentence or a runtime error message legitimately consists of - is returned
    # unchanged, so every diagnostic reads exactly as written and occupies one line.
    return "".join(ch if ch.isprintable() else ch.encode("unicode_escape").decode("ascii")
                   for ch in text)


def report(text):
    # Every line this program writes to file descriptor 2 leaves through here, so the
    # escaping above cannot be bypassed by any diagnostic and none of them can span
    # more than one line. The explicit flush puts each line on the stream the moment it
    # is written, whatever buffering the runtime chose for a redirected descriptor 2.
    # Nothing is ever written to descriptor 1 from here: that stream carries only the
    # output this program has always produced.
    sys.stderr.write(log_safe(text) + "\n")
    sys.stderr.flush()


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
            # The message comes from the operating system, names an errno and nothing the
            # client sent, so it is safe to record as it stands.
            self.log_event(f"client disconnected: {error}")

    def do_GET(self):
        self.dispatch(with_body=True)

    def do_HEAD(self):
        # A response to HEAD carries the headers of the equivalent GET, no body.
        self.dispatch(with_body=False)

    def __getattr__(self, name):
        # BaseHTTPRequestHandler dispatches a request by looking up "do_" followed by
        # the method token, and when that attribute is missing it answers with a 501
        # of its own making: no Allow header, and a status line this class never
        # chose. Only GET and HEAD are declared above, so every other token resolves
        # here instead and is answered by the one rejection path below - POST, PUT,
        # PATCH, DELETE, OPTIONS and TRACE, but equally CONNECT, a WebDAV verb, or an
        # extension verb a client invents. Answering them from a single place is what
        # makes "any other method" hold for literally any method, rather than for a
        # list of names that has to be kept complete. The prefix test keeps the hook
        # narrow: every other missing attribute still raises, so a typo elsewhere in
        # this class stays as visible as it would be without it.
        if name.startswith("do_") and len(name) > len("do_"):
            return self.reject_method
        raise AttributeError(f"{type(self).__name__} has no attribute {name!r}")

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
        # and every other path do not. A query string is the only component dropped
        # here. A "#" is deliberately left in place: a fragment is not part of a
        # request-target and a conforming client never sends one, so treating
        # /health#anything as an alias of /health would invent a second route rather
        # than tolerate a real one, and would answer UP on a target this program does
        # not serve. index.js draws the line in the same place.
        return self.path.split("?", 1)[0]

    def send_response(self, code, message=None):
        # The status line, and the only header the standard library adds here that this
        # program is willing to send. BaseHTTPRequestHandler.send_response() appends two
        # of its own to every response: Date, and a Server header whose value is
        # version_string() - server_version followed by sys_version, which reads
        # "BaseHTTP/0.6 Python/3.12.13". That names the library and the interpreter this
        # endpoint runs on down to their patch levels, on the one route a liveness probe
        # is guaranteed to reach, and a patch level is precisely what a published
        # advisory is indexed by. It is also something nobody asked this program for: the
        # response already carries the only name and version this contract publishes, and
        # both of those describe the application rather than the machinery underneath it.
        # So the header is not sent at all. HTTP has always made it optional, and
        # index.js sends none either, so the two applications now answer with the same
        # set of headers as well as the same document. Date is kept, because RFC 9110
        # asks an origin server that knows the time to date its responses, and because it
        # describes the response rather than this program. The access record is unchanged:
        # log_request() below is the redacted one this class defines. Overriding here
        # rather than at the single call site is what makes this hold for every response
        # the program can emit, including the ones send_error() writes for a request the
        # standard library could not parse.
        self.log_request(code)
        self.send_response_only(code, message)
        self.send_header("Date", self.date_time_string())

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

    def log_event(self, event):
        # The one place this handler records anything. Only text this class composes
        # itself reaches it, so nothing derived from a request can be written. Access
        # lines and handler diagnostics belong on file descriptor 2 so that descriptor 1
        # carries only this program's original output.
        report(f"{APP_NAME}: [{self.log_date_time_string()}] {event}")

    def log_request(self, code="-", size="-"):
        # The access line, and deliberately only the response status this program chose.
        # BaseHTTPRequestHandler's own access line quotes the request line exactly as the
        # client sent it and prefixes the address it came from, so a probe of
        # /health?token=... put that token, and the address that sent it, on descriptor 2
        # for whoever reads the logs afterwards. A liveness endpoint is polled by anything
        # that can reach it and its target is chosen by the caller, so neither the request
        # line nor the peer address belongs in a log record. The status is the whole of the
        # operationally useful signal and, unlike the request line, it comes from this
        # program rather than from the client. int() renders an HTTPStatus as its number
        # on every version that ships one.
        self.log_event(f"responded {int(code) if isinstance(code, int) else code}")

    def log_error(self, format, *args):
        # send_error() reports its diagnosis through here, and the standard library's
        # diagnosis of a request it could not parse quotes the offending token - "code
        # 400, message Bad request version ('HTTP/9')" - which is client input. The
        # arguments are therefore dropped and only the fact is recorded. The status of the
        # response that follows is still recorded by log_request() above, so nothing
        # operationally useful is lost with them.
        self.log_event("request could not be served as sent")

    def log_message(self, format, *args):
        # A guard rather than a sink. Both standard-library callers are overridden above -
        # log_request() for a request that was answered and log_error() for one that was
        # not - so nothing reaches here today. Anything that ever did would arrive with
        # request text already interpolated into it, so the arguments are withheld instead
        # of written, and the call is still recorded so that it cannot pass unnoticed.
        self.log_event("standard library diagnostic withheld")


class HealthServer(ThreadingHTTPServer):
    # A thread per connection, so one slow or half-open client cannot keep a probe
    # waiting, exactly as ThreadingHTTPServer provides. The one behavior changed here is
    # how an unexpected handler exception is reported.

    def handle_error(self, request, client_address):
        # socketserver reports an exception the handler did not catch by printing two
        # separator lines, the address the request came from, and a full traceback. That
        # is four lines where this program emits one, it puts the peer's address on
        # descriptor 2, and the traceback discloses interpreter and source paths - none of
        # which belongs in the output of a liveness endpoint. The exception type is
        # recorded instead: it comes from this program rather than from the request, and
        # it is enough to tell an operator that a fault occurred and which one. The
        # exception is swallowed here exactly as it is by the default, so one failed
        # request never takes the listener down with it.
        error = sys.exc_info()[1]
        report(f"{APP_NAME}: request handling failed: {type(error).__name__}")


class InvalidHealthConfig(Exception):
    # Raised when an environment override is present but unusable. It carries the
    # operator-facing sentence, so the caller can report the fault on one line and
    # exit non-zero without a traceback ever reaching the operator.
    pass


def health_host():
    host = os.environ.get("HEALTH_HOST", "").strip(ASCII_BLANKS)
    if not host:
        # Unset, empty or blank-only means "not overridden", which is not a fault:
        # the documented default applies, silently.
        return DEFAULT_HOST
    if not host.isprintable() or any(ch.isspace() for ch in host):
        # A host name, an IPv4 or IPv6 literal and an IPv6 zone identifier are all
        # made of printable, non-blank characters, so anything else is refused right
        # here, before the value can reach a socket or a diagnostic. A carriage return
        # or a line feed inside it would otherwise let whoever set the variable forge
        # extra lines on descriptor 2, and an interior blank cannot name a host in any
        # case. Refusing rather than repairing is also the only safe answer: a value
        # quietly trimmed to something bindable would put the endpoint on an address
        # nobody asked for. The same rule is applied by index.js, so an override is
        # accepted or rejected identically whichever application reads it.
        #
        # The rejected value is deliberately not quoted back. An environment variable is
        # a common place for a secret to be pasted by mistake, and a diagnostic is the
        # one part of this program that is routinely collected, forwarded and kept, so
        # echoing the value would write whatever was supplied into a log this program
        # does not control. Naming the variable and the rule it broke is all an operator
        # needs to correct it, and they already have the value: they set it.
        raise InvalidHealthConfig(
            "invalid HEALTH_HOST: expected a host name or address with no blanks and no "
            "control characters")
    return host


def health_port():
    port = os.environ.get("HEALTH_PORT", "").strip(ASCII_BLANKS)
    if not port:
        # Unset, empty or blank-only means "not overridden", which is not a fault:
        # the documented default applies, silently.
        return DEFAULT_PORT
    # The grammar is checked before any conversion, and it is exactly the grammar
    # index.js applies: one to five ASCII decimal digits and nothing else. isascii()
    # is what makes isdigit() mean [0-9] here - on its own isdigit() is also true of
    # a fullwidth or superscript digit, and int() would then convert it, so a
    # HEALTH_PORT of U+FF11 followed by 8000 would have bound port 18000. Anything a
    # reader might expect int() to take - "1_9433", "+8000", "0x4be0", "1e3" - is
    # refused by both applications alike.
    if len(port) <= PORT_MAX_DIGITS and port.isascii() and port.isdigit():
        number = int(port)
        if 0 < number < 65536:
            return number
    # An override that is present but cannot be honoured is a configuration fault,
    # so startup stops here. Falling back to the default would answer a typo by
    # silently moving the endpoint off the port that was asked for: the process
    # would report itself UP while the probe watching the intended port saw nothing
    # at all. Failing fast makes that mistake impossible to miss. The rejected value
    # is not quoted back, for the reason given in health_host() above: an environment
    # variable can carry a secret, and a diagnostic outlives the process that wrote it.
    raise InvalidHealthConfig(
        f"invalid HEALTH_PORT: expected 1 to {PORT_MAX_DIGITS} decimal digits denoting "
        "a port from 1 to 65535")


def stop_on_signal(signum, frame):
    # Signal handlers run on the main thread, so raising from here unwinds whatever
    # startup step or serve_forever loop was running and reaches the one shutdown path
    # in serve_health below. Calling server.shutdown() from a handler would deadlock
    # instead, because it waits for the very loop the handler interrupted.
    #
    # Both handlers are dropped first, which makes this idempotent: the shutdown path
    # cannot be re-entered by a second signal and cannot be interrupted by one either,
    # so it can neither report twice nor surface as a traceback. Restoring the default
    # action rather than ignoring the signal also means an operator who signals again
    # is never left waiting - the second one ends the process outright. index.js drops
    # its two listeners at the same point and for the same reasons.
    signal.signal(signal.SIGINT, signal.SIG_DFL)
    signal.signal(signal.SIGTERM, signal.SIG_DFL)
    raise KeyboardInterrupt


def serve_health():
    server = None
    try:
        # Stop handling is installed before the first step that can fail, block or take
        # any measurable time. Registering it only once the listener was up left a window -
        # environment reads, then the bind - in which a SIGTERM from a supervisor took the
        # interpreter's default action: the process died on the signal with no notice on
        # descriptor 2 and a signal exit status, even though it had been asked to stop
        # politely. SIGINT already arrives as KeyboardInterrupt, and routing SIGTERM into
        # the same exception is what lets both signals leave through the one shutdown path
        # below, whether they arrive during startup or an hour into serving.
        #
        # It is installed from inside this block rather than ahead of it, because the
        # earliest signal these two lines can catch is one that arrives while the second
        # of them is still running: the handler exists by then, so it raises, and with the
        # registration sitting outside the block that exception had nowhere to go. It left
        # through this function uncaught, which printed the interpreter's own traceback -
        # interpreter path, library path and source path with it - and ended the process on
        # a signal status, for a stop request the program was in the middle of arming
        # itself to honour. Registering here means the shutdown path exists before the
        # handlers that reach it do.
        signal.signal(signal.SIGINT, stop_on_signal)
        signal.signal(signal.SIGTERM, stop_on_signal)
        try:
            # Both overrides are validated before anything is constructed, so a value
            # that cannot be honoured stops startup instead of reaching a socket call.
            host = health_host()
            port = health_port()
        except InvalidHealthConfig as error:
            # Reported in the same shape as the bind failure below, one readable line
            # and a non-zero status, so that no listener is ever started on an address
            # nobody asked for.
            report(f"{APP_NAME}: {error}")
            return 1
        try:
            server = HealthServer((host, port), HealthRequestHandler)
        except OSError as error:
            # Binding is the first operation in this program that can fail for reasons
            # outside its control, most often because the port is already taken. One
            # readable line and a non-zero status serve the operator better than a
            # traceback.
            #
            # Naming the address is what makes the line actionable, so the default is
            # named in full: that literal is declared at the top of this file, and
            # repeating a value already present in the source discloses nothing. A host
            # that arrived in HEALTH_HOST is named only by its variable, because the
            # checks above accept any printable, blank-free word as a host name and only
            # the resolver can discover that the word names nothing. A variable set to
            # something that was never an address - a token, a path, a URL - would
            # otherwise be copied into a record that outlives this process, and its
            # length would set the length of this line. Naming the variable tells the
            # operator exactly where to look without repeating what was found there. The
            # port is always named because it is validated to be nothing but decimal
            # digits, so it can carry nothing else, and the reason comes from the errno,
            # whose text describes the failure without ever quoting the operand.
            if host == DEFAULT_HOST:
                where = f"{host}:{port}"
            else:
                where = f"the address named by HEALTH_HOST, port {port}"
            report(f"{APP_NAME}: cannot bind {where}: {error}")
            return 1
        report(f"{APP_NAME} {APP_VERSION} serving {HEALTH_PATH} on http://{host}:{port}")
        server.serve_forever()
    except KeyboardInterrupt:
        # Reached from a signal delivered at any point above: while stop handling was
        # still being installed, while an override was being read, while the socket was
        # being bound, or while requests were being served. The notice and the exit status
        # are the same in every case, and the listener - if one exists by then - is closed
        # by the finally below.
        report(f"{APP_NAME}: shutting down")
    finally:
        # Releases the listening socket, so the port is free the moment this process
        # ends. Request threads are daemons and never delay this call. Guarded because a
        # signal or a failed bind can reach here before there is a server to close.
        if server is not None:
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
