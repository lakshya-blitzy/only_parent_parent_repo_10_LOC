import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    // The two fields HTTP frames a request payload with. A liveness answer needs no payload,
    // so a request announcing one is answered without its payload being read: reading a body
    // is the one thing a client can make this program wait for.
    private static final String CONTENT_LENGTH_HEADER = "Content-Length";
    private static final String TRANSFER_ENCODING_HEADER = "Transfer-Encoding";

    // Three platform settings this listener cannot leave at their defaults, because each
    // default lets one unfinished request hold the thread serving it for as long as its
    // sender likes: an unread request body is otherwise read and discarded when the exchange
    // closes (64k), and nothing bounds how long a request may take to arrive or a reply to
    // be read (no deadline at all). Discarding nothing and closing a connection that stops
    // progressing is what keeps this endpoint answerable while such a client is connected.
    // They are properties rather than API calls, and the platform reads them once as its
    // server classes initialise, so they are set before the first call into them.
    private static final String DRAIN_AMOUNT_PROPERTY = "sun.net.httpserver.drainAmount";
    private static final String MAX_REQUEST_TIME_PROPERTY = "sun.net.httpserver.maxReqTime";
    private static final String MAX_RESPONSE_TIME_PROPERTY = "sun.net.httpserver.maxRspTime";
    private static final String DRAIN_AMOUNT = "0";

    // Seconds, matching the read timeout the Python listener applies to the same contract.
    private static final String MAX_REQUEST_SECONDS = "10";
    private static final String MAX_RESPONSE_SECONDS = "10";

    // What every thread this listener creates is called, numbered from the suffix onwards.
    private static final String HANDLER_THREAD_PREFIX = "-health-";
    private static final long FIRST_HANDLER_THREAD = 0;

    private static final String SERVE_FLAG = "--serve";

    // One explicit grammar for the overrides - ASCII blanks trimmed, and a port written in
    // ASCII decimal digits only - so every application reading them accepts the same
    // values. The vertical tab is written as an octal escape because Java has no \v.
    private static final String ASCII_BLANKS = " \t\n\013\f\r";
    private static final int PORT_MAX_DIGITS = 5;
    private static final int PORT_MIN = 1;
    private static final int PORT_MAX = 65535;

    // What healthPort() answers for a present but unusable override, which no port is.
    private static final int PORT_REJECTED = -1;

    public static void main(String[] args) {
        String name = "Test";
        System.out.println(name);
        // Opt-in: without --serve the line above is the whole program, exactly as before.
        if (hasServeFlag(args)) {
            // Flushed before this process becomes long-running, so the line above reaches a
            // redirected descriptor 1 now rather than at exit.
            System.out.flush();
            int status = serveHealth();
            if (status != 0) {
                System.exit(status);
            }
            // Started: the listener's own non-daemon thread keeps this process alive and the
            // shutdown hook releases the port when a signal arrives. Returning here instead
            // of exiting is what leaves the status a signal produces untouched.
        }
    }

    private static boolean hasServeFlag(String[] args) {
        // The first read this program has ever made of its argument vector; anything else
        // on the command line is ignored.
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
            // ISO_INSTANT prints the fewest digits it can, so an instant landing on a whole
            // second arrives with no fraction at all - about one request in a thousand. The
            // three zeros are restored here, because a value whose width changed that often
            // would not be the fixed-width timestamp the other two applications emit.
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
        // The one shape every reply that is not a health document takes, so no request text
        // and no internal detail can reach a response body.
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
                        // Every remaining control character has no shorthand of its own, so
                        // it is written as the six-character escape JSON requires.
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
        // getRawPath() excludes the query string and leaves the target exactly as it
        // arrived, so /health?probe=1 matches while /health/ and an encoded /%68ealth are
        // targets this program does not serve - the same rule the other two apply.
        String path = exchange.getRequestURI().getRawPath();
        return path == null ? "" : path;
    }

    private static boolean declaresBody(HttpExchange exchange) {
        // HTTP announces a request payload with one of two fields, so their presence is the
        // whole test: any Transfer-Encoding at all, or a Content-Length above zero.
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
        // A declared length of zero frames no payload, so such a request is served normally.
        return false;
    }

    private static void endRequest(HttpExchange exchange) throws IOException {
        // Only requests that announced no payload reach here, so this read reports the end
        // of the stream at once without waiting on the connection. Reaching that end is what
        // keeps the connection reusable: nothing is discarded on this program's behalf any
        // more, so a request stream left unread closes the connection instead.
        InputStream request = exchange.getRequestBody();
        if (request.read() != -1) {
            // A byte HTTP's own framing rules say cannot be there. It is discarded and the
            // stream closed rather than read on, because no request payload has any part in
            // a liveness answer.
            request.close();
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String payload,
            boolean withBody, String allow, boolean close) throws IOException {
        // Every reply leaves through here, so there is one place where the wire contract is
        // stated and one place it can be read from.
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
            // This connection is to stay open, which it only can once the request it is
            // carrying has been read to its end.
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
        // The method is tested before the path so that a rejected method is answered the
        // same way on every target, not only on the one resource this program serves. Those
        // replies already end their connection, so they need no payload test of their own.
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            rejectMethod(exchange, path, withBody);
            return;
        }
        // Read once and carried into both replies below. A declared payload changes nothing
        // about which reply a request receives - the status and body are the ones this
        // contract specifies either way - only whether the connection survives it: the
        // payload is never read, because waiting for a body a sender never sends is exactly
        // how one client could keep this endpoint from answering, and bytes left unread on a
        // kept-alive connection would be misread as the request after it.
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
        // The served resource answers 405 with its Allow header, every other target the
        // same 404, and either way the connection is closed.
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
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (IOException error) {
            // One readable line and a non-zero status instead of a stack trace. A host that
            // arrived in HEALTH_HOST is named by its variable rather than quoted back, since
            // only the resolver can discover that the word names nothing.
            String where = DEFAULT_HOST.equals(host) ? host + ":" + port
                : "the address named by HEALTH_HOST, port " + port;
            report(APP_NAME + ": cannot bind " + where + ": " + bindReason(error));
            return 1;
        }
        // Requests are answered on threads of this listener's own rather than on the one
        // thread it dispatches from, and on one such thread each, so a client that stops
        // sending holds nothing but the thread reading it and the endpoint keeps answering
        // everyone else.
        ExecutorService handlers = handlerPool();
        server.setExecutor(handlers);
        // One context at the root and one handler: the rule in handleRequest is the whole
        // route table, so every target reaches the same decision.
        server.createContext("/", exchange -> {
            try (HttpExchange open = exchange) {
                handleRequest(open);
            } catch (IOException | RuntimeException error) {
                // A probe hanging up mid-reply is routine, so the condition is named on one
                // line, without a trace, and the listener carries on serving.
                report(APP_NAME + ": request handling failed: "
                    + error.getClass().getSimpleName());
            }
        });
        // Registered before start(), so a signal arriving in the same instant as the first
        // request still finds a hook that releases the port.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            report(APP_NAME + ": shutting down");
            server.stop(0);
            // Stopping the listener leaves the threads it handed work to running, so they
            // are ended here as well; a request still in flight is abandoned, which is what
            // a signalled shutdown asks for.
            handlers.shutdownNow();
        }));
        server.start();
        report(APP_NAME + " " + APP_VERSION + " serving " + HEALTH_PATH + " on http://"
            + host + ":" + port);
        return 0;
    }

    private static void limitRequestHandling() {
        // Nothing is discarded on this program's behalf, and a request that stops arriving or
        // a reply that stops being read is given a deadline instead of the platform's none,
        // so the work one client can create for this listener is finite.
        System.setProperty(DRAIN_AMOUNT_PROPERTY, DRAIN_AMOUNT);
        System.setProperty(MAX_REQUEST_TIME_PROPERTY, MAX_REQUEST_SECONDS);
        System.setProperty(MAX_RESPONSE_TIME_PROPERTY, MAX_RESPONSE_SECONDS);
    }

    private static ExecutorService handlerPool() {
        // One thread per exchange rather than a fixed set of them, because the platform reads
        // a request head on the thread it hands the exchange to: with a fixed set, that many
        // clients that stop sending mid-head occupy every thread and this endpoint answers
        // nobody until their deadline expires - the same denial a single client could cause
        // when handling ran on the dispatch thread. A virtual thread is what makes one per
        // exchange affordable: it costs a few kilobytes rather than a megabyte of stack, it
        // releases its carrier while a read waits, and it is always a daemon, so what keeps
        // this process alive is still the listener's own thread and the status a signal
        // produces is unchanged. What bounds the work one client can create is the request
        // and response deadline set in limitRequestHandling(), not a thread count.
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
            // A host name or address carries no blanks and no control characters, so
            // anything else is refused rather than repaired.
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
        // The remaining categories that render nothing at all: format, surrogate, private
        // use and unassigned code points.
        int category = Character.getType(character);
        return category == Character.FORMAT || category == Character.SURROGATE
            || category == Character.PRIVATE_USE || category == Character.UNASSIGNED;
    }

    private static int healthPort() {
        String requested = trimBlanks(environment("HEALTH_PORT"));
        if (requested.isEmpty()) {
            return DEFAULT_PORT;
        }
        // One to five ASCII decimal digits and nothing else, checked before any conversion,
        // so no sign, hexadecimal or fullwidth form can reach the parse below - and five
        // digits cannot overflow the value it returns.
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
        // A bind failure names its condition without quoting the address, so its message
        // stands as it is; one that carries no message is named by its type instead.
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
