import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class User {
    private static final String APP_NAME = "user-app";
    private static final String APP_VERSION = "1.0.0";

    // Loopback only: with no deployment target, the listener stays off external interfaces.
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8080;

    private static final String HEALTH_PATH = "/health";
    private static final String HEALTH_METHODS = "GET, HEAD";
    private static final String HEALTH_STATUS = "UP";
    private static final String STATUS_NOT_FOUND = "NOT_FOUND";
    private static final String STATUS_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

    private static final String CONTENT_LENGTH_HEADER = "Content-Length";
    private static final String TRANSFER_ENCODING_HEADER = "Transfer-Encoding";

    // Platform properties rather than API calls, and only honoured if set before the server
    // is created: nothing is drained on this program's behalf, and a request or reply that
    // stops progressing is bounded, so no one client can hold a handler thread indefinitely.
    private static final String DRAIN_AMOUNT_PROPERTY = "sun.net.httpserver.drainAmount";
    private static final String MAX_REQUEST_TIME_PROPERTY = "sun.net.httpserver.maxReqTime";
    private static final String MAX_RESPONSE_TIME_PROPERTY = "sun.net.httpserver.maxRspTime";
    private static final String DRAIN_AMOUNT = "0";

    private static final String MAX_REQUEST_SECONDS = "10";
    private static final String MAX_RESPONSE_SECONDS = "10";

    private static final String HANDLER_THREAD_PREFIX = "-health-";
    private static final long FIRST_HANDLER_THREAD = 0;

    // The one route this program registers, and the backlog it leaves to the system.
    private static final String ROOT_CONTEXT = "/";
    private static final int SYSTEM_BACKLOG = 0;

    // Where this platform publishes the signals this process ignores, and the bits standing
    // for the two the shutdown hook below exists to answer. A signal already ignored when a
    // process of this platform starts stays ignored: it will not install a handler over an
    // inherited disposition of ignore, so the kernel discards such a signal before this
    // program can see it and the hook is never reached. Requesting a handler through this
    // platform's unsupported signal API does not change that: it reports the inherited
    // ignore back and installs nothing, so the signal stays undeliverable. Nothing in this
    // platform's supported API reports that state either, so the published mask is read
    // instead, and only so the condition can be said out loud at startup rather than
    // discovered when a stop attempt does nothing. A shell that starts a background job from
    // a script ignores SIGINT on that job's behalf, which is the ordinary way a serving
    // process arrives in this state; a shell with job control enabled does not, so the same
    // job started in the foreground, or with job control, leaves SIGINT deliverable and the
    // hook below reachable. The notice written at startup names both remedies.
    private static final String IGNORED_SIGNALS_FILE = "/proc/self/status";
    private static final String IGNORED_SIGNALS_FIELD = "SigIgn:";
    private static final int IGNORED_SIGNALS_RADIX = 16;
    private static final long NO_SIGNALS_IGNORED = 0L;

    // Bit n - 1 of that mask stands for signal n, and these are SIGINT and SIGTERM.
    private static final long SIGINT_BIT = 1L << 1;
    private static final long SIGTERM_BIT = 1L << 14;

    private static final String SERVE_FLAG = "--serve";

    // One explicit grammar for the overrides - ASCII blanks trimmed, and a port written in
    // ASCII decimal digits only - so every application reading them accepts the same values.
    private static final String ASCII_BLANKS = " \t\n\013\f\r";
    private static final int PORT_MAX_DIGITS = 5;
    private static final int PORT_MIN = 1;
    private static final int PORT_MAX = 65535;

    private static final int PORT_REJECTED = -1;

    public static void main(String[] args) {
        String name = "Test";
        System.out.println(name);
        // Opt-in: without --serve the line above is the whole program.
        if (hasServeFlag(args)) {
            // Flushed before this process becomes long-running, so the line above reaches a
            // redirected descriptor 1 now rather than at exit.
            System.out.flush();
            int status = serveHealth();
            if (status != 0) {
                System.exit(status);
            }
            // Returning rather than exiting leaves the status a signal produces untouched.
        }
    }

    private static boolean hasServeFlag(String[] args) {
        for (int index = 0; index < args.length; index++) {
            if (SERVE_FLAG.equals(args[index])) {
                return true;
            }
        }
        return false;
    }

    private static String healthTimestamp() {
        // truncatedTo is explicit because this platform's default rendering is nanoseconds,
        // not the contract's milliseconds.
        String rendered = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        if (rendered.indexOf('.') < 0) {
            // An instant landing on a whole second renders with no fraction at all, so the
            // three zeros are restored to keep the timestamp fixed-width.
            return rendered.substring(0, rendered.length() - 1) + ".000Z";
        }
        return rendered;
    }

    private static String healthPayload() {
        // Member order is the contract order, and these four members are the whole
        // document. This platform ships no JSON API, so the text is assembled here:
        // compact, with no whitespace between tokens, and with every value escaped.
        return "{\"name\":" + jsonString(APP_NAME)
            + ",\"version\":" + jsonString(APP_VERSION)
            + ",\"timestamp\":" + jsonString(healthTimestamp())
            + ",\"status\":" + jsonString(HEALTH_STATUS)
            + "}";
    }

    private static String statusPayload(String status) {
        return "{\"status\":" + jsonString(status) + "}";
    }

    private static String jsonString(String value) {
        return "\"" + jsonEscape(value) + "\"";
    }

    private static String jsonEscape(String value) {
        // Assembled text is only valid JSON if the values in it are escaped, so every value
        // this program interpolates is routed through here.
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < ' ') {
                        escaped.append(unicodeEscape(character));
                    } else {
                        escaped.append(character);
                    }
                    break;
            }
        }
        return escaped.toString();
    }

    private static String unicodeEscape(char character) {
        // The doubled backslash is one literal backslash, so this yields the six characters
        // backslash, u and four hex digits rather than the character they denote. The
        // sequence is not spelled out in this comment because a compiler translates that
        // spelling wherever it appears in a source file, comments included.
        return String.format("\\u%04x", (int) character);
    }

    private static String requestPath(HttpExchange exchange) {
        // The target exactly as the request line carried it, because the parsed path is not
        // the target: this platform parses the target as a URI, so //x/health arrives with
        // its authority in a separate component and a parsed path of /health, and ///health
        // and an absolute-form target do the same. Routing on the parsed path would answer
        // all of them as aliases of /health, which the contract does not declare. Only the
        // query string is dropped, so /health?probe=1 matches while /health/ and an encoded
        // /%68ealth do not - the same rule the other two applications apply to their own
        // request target.
        String target = exchange.getRequestURI().toString();
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    private static boolean declaresBody(HttpExchange exchange) {
        if (exchange.getRequestHeaders().getFirst(TRANSFER_ENCODING_HEADER) != null) {
            return true;
        }
        String declared = exchange.getRequestHeaders().getFirst(CONTENT_LENGTH_HEADER);
        if (declared == null) {
            return false;
        }
        String length = trimBlanks(declared);
        if (length.isEmpty()) {
            return false;
        }
        if (!isAsciiDigits(length)) {
            // A length this program cannot read as a number frames a request it cannot read
            // either, so it counts as a payload rather than as an absent one.
            return true;
        }
        for (int index = 0; index < length.length(); index++) {
            // Tested digit by digit rather than converted, so a length too long for any
            // numeric type still answers this question instead of failing to parse.
            if (length.charAt(index) != '0') {
                return true;
            }
        }
        return false;
    }

    private static void endRequest(HttpExchange exchange) throws IOException {
        // Only requests that announced no payload reach here, so this read reports the end
        // of the stream at once without waiting on the connection. Reaching that end is what
        // keeps the connection reusable: nothing is drained on this program's behalf, so a
        // request stream left unread closes the connection instead.
        InputStream request = exchange.getRequestBody();
        if (request.read() != -1) {
            request.close();
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String payload,
            boolean withBody, String allow, boolean close) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (allow != null) {
            exchange.getResponseHeaders().set("Allow", allow);
        }
        if (close) {
            // Closing discards an unread request payload, so it can never be misread as the
            // start of the next request on this connection.
            exchange.getResponseHeaders().set("Connection", "close");
        } else {
            endRequest(exchange);
        }
        if (!withBody) {
            // The no-body form. Its negative length is also why a HEAD reply carries no
            // Content-length: that is this platform's behaviour, not an omission here.
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        // The byte count, never the character count: the two part company as soon as a
        // value stops being ASCII.
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = requestPath(exchange);
        boolean withBody = !"HEAD".equals(method);
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            rejectMethod(exchange, path, withBody);
            return;
        }
        // A declared payload is closed rather than read, so it can neither stall this reply
        // nor be misread as the request after it on a kept-alive connection.
        boolean carriesPayload = declaresBody(exchange);
        if (!HEALTH_PATH.equals(path)) {
            sendJson(exchange, 404, statusPayload(STATUS_NOT_FOUND), withBody, null,
                carriesPayload);
            return;
        }
        sendJson(exchange, 200, healthPayload(), withBody, null, carriesPayload);
    }

    private static void rejectMethod(HttpExchange exchange, String path, boolean withBody)
            throws IOException {
        if (!HEALTH_PATH.equals(path)) {
            sendJson(exchange, 404, statusPayload(STATUS_NOT_FOUND), withBody, null, true);
            return;
        }
        sendJson(exchange, 405, statusPayload(STATUS_METHOD_NOT_ALLOWED), withBody,
            HEALTH_METHODS, true);
    }

    private static int serveHealth() {
        String host = healthHost();
        if (host == null) {
            // A present but unusable override stops startup rather than silently binding the
            // default, and the rejected value is never quoted back.
            report(APP_NAME + ": invalid HEALTH_HOST: expected a host name or address with "
                + "no blanks and no control characters");
            return 1;
        }
        int port = healthPort();
        if (port == PORT_REJECTED) {
            report(APP_NAME + ": invalid HEALTH_PORT: expected " + PORT_MIN + " to "
                + PORT_MAX_DIGITS + " decimal digits denoting a port from " + PORT_MIN
                + " to " + PORT_MAX);
            return 1;
        }
        // Before the first call into the platform's server classes, because that is the only
        // point at which these settings are still read.
        limitRequestHandling();
        HttpServer server;
        try {
            // The platform's own server, bound directly to the configured address: loopback by
            // default, and the backlog left to the system.
            server = HttpServer.create(new InetSocketAddress(host, port), SYSTEM_BACKLOG);
        } catch (IOException error) {
            // One readable line and a non-zero status instead of a stack trace.
            String where = DEFAULT_HOST.equals(host) ? host + ":" + port
                : "the address named by HEALTH_HOST, port " + port;
            report(APP_NAME + ": cannot bind " + where + ": " + bindReason(error));
            return 1;
        }
        // Requests are answered on this server's own threads rather than on the one it
        // dispatches from, so a client that stops sending holds nothing but its own thread.
        ExecutorService handlers = handlerPool();
        server.setExecutor(handlers);
        // One context, at the root, and it is the only one: every request this server routes is
        // answered by sendJson() below - 200 and the health document for /health, 405 with Allow
        // for a method not served there, 404 for every other path - so no other text leaves this
        // program as a response body. A context is matched on the path parsed out of the request
        // target and a context path must begin with a slash, so the root matches every path a
        // client can address.
        //
        // What this platform will not route it answers itself, before any context is consulted:
        // a request line it cannot split into three tokens or whose tokens are separated by more
        // than one blank, a target that is not a legal URI, a target it can parse but whose path
        // matches no context - //health, whose path parses empty, the asterisk form, or one
        // carrying no leading slash - a Content-Length it cannot read as a number or two of them
        // that disagree, and a header key holding an illegal character. Each of those gets its
        // own text/html page, 400 or 404 according to the step that refused it, carrying neither
        // Date nor Cache-Control; a request arriving with more than two hundred header fields
        // gets no reply at all. That is a property of the platform's server rather than a route
        // left unwritten here, and it is not worked around, because doing so would mean putting
        // something other than this server in front of it. The README records the same set, and
        // the two must be kept in step.
        server.createContext(ROOT_CONTEXT, exchange -> {
            try (HttpExchange open = exchange) {
                handleRequest(open);
            } catch (IOException | RuntimeException error) {
                // The condition is named on one line, without a trace, and the listener
                // carries on serving.
                report(APP_NAME + ": request handling failed: "
                    + error.getClass().getSimpleName());
            }
        });
        // Registered before start(), so a signal arriving in the same instant as the first
        // request still finds a hook that releases the port. A signal can also arrive before
        // this line, because create() above binds the address well before start() below: the
        // port is taken while this method is still walking towards the hook. Shutdown is then
        // already under way, and this platform refuses that state twice over - registering a
        // hook once shutdown has begun, and starting a listener whose hook has already stopped
        // it, both throw. Left alone either throw would leave main by way of the default
        // handler, which prints a trace to the error stream and would be cut off mid-sentence
        // by the exit racing it. Neither is a condition to hand the user a trace for, so each
        // is answered instead with the one notice and the released port a completed startup
        // would have given them.
        boolean hooked = false;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                report(APP_NAME + ": shutting down");
                server.stop(0);
                // Stopping the listener leaves the threads it handed work to running, so they
                // are ended here as well; a request still in flight is abandoned, which is what
                // a signalled shutdown asks for.
                handlers.shutdownNow();
            }));
            hooked = true;
            server.start();
        } catch (IllegalStateException shuttingDown) {
            if (!hooked) {
                // The hook was refused, so nothing else will report this shutdown or release
                // the port bound above; when it was accepted it does both itself, and
                // reporting here as well would announce the one shutdown twice.
                report(APP_NAME + ": shutting down");
                server.stop(0);
                handlers.shutdownNow();
            }
            // Returning rather than exiting leaves the status the signal produces untouched,
            // and leaves the error stream carrying that one notice and nothing else.
            return 0;
        }
        report(APP_NAME + " " + APP_VERSION + " serving " + HEALTH_PATH + " on http://"
            + host + ":" + port);
        String undeliverable = ignoredSignalNotice();
        if (undeliverable != null) {
            // Said once, beside the banner, so whoever started this listener learns which
            // signal will stop it - and how to start it so the other one would too - before
            // trying one this process can no longer receive.
            report(APP_NAME + ": " + undeliverable);
        }
        return 0;
    }

    private static String ignoredSignalNotice() {
        // Only the two signals the hook above exists to answer are reported, and only while
        // this process ignores them, because that is the one state in which a stop attempt is
        // discarded instead of answered. Each notice carries both remedies: the signal that
        // will stop this listener as it stands, and the way to start it so the ignored one
        // reaches it after all. The text is fixed here rather than assembled from anything
        // outside this program.
        long ignored = ignoredSignals();
        boolean interrupt = (ignored & SIGINT_BIT) != 0;
        boolean terminate = (ignored & SIGTERM_BIT) != 0;
        if (interrupt && terminate) {
            return "SIGINT and SIGTERM are ignored by this process and cannot stop this "
                + "listener; send SIGKILL instead, or start it again where neither signal "
                + "is ignored";
        }
        if (interrupt) {
            return "SIGINT is ignored by this process and cannot stop this listener; send "
                + "SIGTERM instead, or start it where SIGINT is not ignored - in the "
                + "foreground, or with job control enabled (set -m)";
        }
        if (terminate) {
            return "SIGTERM is ignored by this process and cannot stop this listener; send "
                + "SIGINT instead, or start it again where SIGTERM is not ignored";
        }
        return null;
    }

    private static long ignoredSignals() {
        // A best-effort read, because the mask is a property of the platform rather than of
        // this program. Every way it can come to nothing - no such file on a platform that
        // publishes no mask, a file that cannot be read, or one carrying something other than
        // the expected field and hex digits - answers that no signal is known to be ignored,
        // so no notice is written and nothing else about this program changes.
        Path published = Path.of(IGNORED_SIGNALS_FILE);
        if (!Files.isReadable(published)) {
            return NO_SIGNALS_IGNORED;
        }
        String status;
        try {
            status = Files.readString(published, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return NO_SIGNALS_IGNORED;
        }
        for (String line : status.split("\n")) {
            if (!line.startsWith(IGNORED_SIGNALS_FIELD)) {
                continue;
            }
            String mask = trimBlanks(line.substring(IGNORED_SIGNALS_FIELD.length()));
            try {
                return Long.parseUnsignedLong(mask, IGNORED_SIGNALS_RADIX);
            } catch (NumberFormatException unreadable) {
                return NO_SIGNALS_IGNORED;
            }
        }
        return NO_SIGNALS_IGNORED;
    }

    private static void limitRequestHandling() {
        System.setProperty(DRAIN_AMOUNT_PROPERTY, DRAIN_AMOUNT);
        System.setProperty(MAX_REQUEST_TIME_PROPERTY, MAX_REQUEST_SECONDS);
        System.setProperty(MAX_RESPONSE_TIME_PROPERTY, MAX_RESPONSE_SECONDS);
    }

    private static ExecutorService handlerPool() {
        // One virtual thread per exchange rather than a fixed pool, so clients that stop
        // sending cannot monopolise a small set of threads.
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name(APP_NAME + HANDLER_THREAD_PREFIX, FIRST_HANDLER_THREAD)
            .factory());
    }

    private static String environment(String variable) {
        String value = System.getenv(variable);
        return value == null ? "" : value;
    }

    private static String trimBlanks(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && ASCII_BLANKS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && ASCII_BLANKS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    private static String healthHost() {
        String requested = trimBlanks(environment("HEALTH_HOST"));
        if (requested.isEmpty()) {
            return DEFAULT_HOST;
        }
        for (int index = 0; index < requested.length(); index++) {
            if (isUnprintable(requested.charAt(index))) {
                return null;
            }
        }
        return requested;
    }

    private static boolean isUnprintable(char character) {
        if (Character.isISOControl(character) || Character.isWhitespace(character)
                || Character.isSpaceChar(character)) {
            return true;
        }
        // Refused as well, to keep a host value unambiguous: format, surrogate, private use
        // and unassigned code points.
        int category = Character.getType(character);
        return category == Character.FORMAT || category == Character.SURROGATE
            || category == Character.PRIVATE_USE || category == Character.UNASSIGNED;
    }

    private static int healthPort() {
        String requested = trimBlanks(environment("HEALTH_PORT"));
        if (requested.isEmpty()) {
            return DEFAULT_PORT;
        }
        if (requested.length() > PORT_MAX_DIGITS || !isAsciiDigits(requested)) {
            return PORT_REJECTED;
        }
        int port = Integer.parseInt(requested);
        return port >= PORT_MIN && port <= PORT_MAX ? port : PORT_REJECTED;
    }

    private static boolean isAsciiDigits(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static String bindReason(IOException error) {
        // A failure that carries no message is named by its type instead.
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private static void report(String text) {
        // The one sink for diagnostics: descriptor 2 only, so descriptor 1 keeps carrying
        // just the output this program has always produced.
        System.err.println(logSafe(text));
    }

    private static String logSafe(String text) {
        // Escaping every control character keeps a diagnostic to one line and stops text
        // that came from outside this program from forging a record of its own.
        StringBuilder safe = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isISOControl(character)) {
                safe.append(unicodeEscape(character));
            } else {
                safe.append(character);
            }
        }
        return safe.toString();
    }
}
