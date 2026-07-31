import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
        // same way on every target, not only on the one resource this program serves.
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            rejectMethod(exchange, path, withBody);
            return;
        }
        if (!HEALTH_PATH.equals(path)) {
            sendJson(exchange, 404, statusPayload(STATUS_NOT_FOUND), withBody, null, false);
            return;
        }
        sendJson(exchange, 200, healthPayload(), withBody, null, false);
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
        }));
        server.start();
        report(APP_NAME + " " + APP_VERSION + " serving " + HEALTH_PATH + " on http://"
            + host + ":" + port);
        return 0;
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
