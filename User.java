import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    private static final String ACCEPT_THREAD_SUFFIX = "-ingress";

    // The listening socket this program answers on is its own, and the platform's server sits
    // behind it on a port the system picks, reachable only over the loopback interface.
    private static final int SYSTEM_ASSIGNED_PORT = 0;
    private static final int BACKEND_CONNECT_MILLIS = 5000;

    // Every connection forwarded to the server behind this listener is remembered by the local
    // port it was forwarded from, so the server can tell those connections from any other.
    private static final Set<Integer> FORWARDED_SOURCES = ConcurrentHashMap.newKeySet();

    // The request line is read a line at a time to be examined, so the length examined is
    // capped - at twice the 8 KiB the widely deployed servers settle on, and at the same 16 KiB
    // the JavaScript application's runtime reads. A target longer than this cannot be judged,
    // and is answered as an unknown one rather than buffered further.
    private static final int MAX_REQUEST_LINE = 16384;
    private static final int MAX_LINE_BUFFER = 128;
    private static final String ROOT_TARGET = "/";
    private static final String DEFAULT_REQUEST_VERSION = "HTTP/1.1";
    private static final char SPACE = ' ';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char LINE_FEED = '\n';
    private static final char COLON = ':';
    private static final char ZERO_DIGIT = '0';
    private static final char NINE_DIGIT = '9';

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
        // keeps the connection reusable: nothing is discarded on this program's behalf any
        // more, so a request stream left unread closes the connection instead.
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
        ServerSocket ingress;
        try {
            ingress = new ServerSocket();
            ingress.bind(new InetSocketAddress(host, port), SYSTEM_ASSIGNED_PORT);
        } catch (IOException error) {
            // One readable line and a non-zero status instead of a stack trace.
            String where = DEFAULT_HOST.equals(host) ? host + ":" + port
                : "the address named by HEALTH_HOST, port " + port;
            report(APP_NAME + ": cannot bind " + where + ": " + bindReason(error));
            return 1;
        }
        HttpServer server;
        try {
            // The requested address is already held above, so this one is left to the system
            // and kept on the loopback interface: it is reached from this program only.
            server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), SYSTEM_ASSIGNED_PORT),
                SYSTEM_ASSIGNED_PORT);
        } catch (IOException error) {
            report(APP_NAME + ": cannot start the request server: " + bindReason(error));
            closeIngress(ingress);
            return 1;
        }
        int backendPort = server.getAddress().getPort();
        // Requests are answered on this listener's own threads rather than on the one it
        // dispatches from, so a client that stops sending holds nothing but its own thread.
        ExecutorService handlers = handlerPool();
        server.setExecutor(handlers);
        // One context, at the root, so every request is answered by sendJson() below - 200 and
        // the health document for /health, 405 with Allow for a method not served there, 404
        // for every other target - and no other text ever leaves this program as a response
        // body. Reaching that for every target is what the listener above is for. A context is
        // matched on the path parsed out of the request target, and a context path must begin
        // with a slash, so a target that parses to no such path matches nothing: //health
        // parses to an empty path, * and a target with no leading slash parse to something
        // that is not a path at all. Unmatched, those are answered by this platform itself,
        // with its own text/html page, before any context is consulted - and that page cannot
        // be replaced from here, because no context can be registered that an empty path would
        // match and every hook offered - a filter, an authenticator, a handler predicate -
        // runs only once a context has already been found. So the target is read off the
        // request line as it arrives and, where it would match nothing, the root is forwarded
        // in its place: the request reaches the handler below, which answers on the target's
        // own terms, and the platform's page is never the reply. Nothing else about the request
        // is rewritten, no reply is written anywhere but here, and this remains the one
        // context that answers.
        server.createContext("/", exchange -> {
            try (HttpExchange open = exchange) {
                if (!wasForwarded(open)) {
                    // Not a connection this program's listener opened, so the health document
                    // is not served on it and it is answered as an unknown target instead. The
                    // address it arrived on is a loopback one the system assigns afresh at
                    // every start, so reaching it means a process on this same host found it.
                    sendJson(open, 404, statusPayload(STATUS_NOT_FOUND),
                        !"HEAD".equals(open.getRequestMethod()), null, true);
                    return;
                }
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
        // this line, because the socket above is bound well before start() below: the address
        // is taken while this method is still walking towards the hook. Shutdown
        // is then already under way, and this platform refuses that state twice over -
        // registering a hook once shutdown has begun, and starting a listener whose hook has
        // already stopped it, both throw. Left alone either throw would leave main by way of
        // the default handler, which prints a trace to the error stream and would be cut off
        // mid-sentence by the exit racing it. Neither is a condition to hand the user a trace
        // for, so each is answered instead with the one notice and the released port a
        // completed startup would have given them.
        boolean hooked = false;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                report(APP_NAME + ": shutting down");
                // The listening socket first, so nothing further is accepted into a server
                // that is stopping.
                closeIngress(ingress);
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
                // The hook was refused, so nothing else will report this shutdown or close
                // the sockets opened above; when it was accepted it does both itself, and
                // reporting here as well would announce the one shutdown twice.
                report(APP_NAME + ": shutting down");
                closeIngress(ingress);
                server.stop(0);
                handlers.shutdownNow();
            }
            // Returning rather than exiting leaves the status the signal produces untouched,
            // and leaves the error stream carrying that one notice and nothing else.
            return 0;
        }
        acceptConnections(ingress, backendPort, handlers);
        report(APP_NAME + " " + APP_VERSION + " serving " + HEALTH_PATH + " on http://"
            + host + ":" + port);
        return 0;
    }

    private static void acceptConnections(ServerSocket ingress, int backendPort,
            ExecutorService connections) {
        // One thread waits for connections; each one it takes is served on a thread of its
        // own, so a client that connects and then says nothing delays no other client.
        Thread accepting = new Thread(() -> {
            while (!ingress.isClosed()) {
                Socket client;
                try {
                    client = ingress.accept();
                } catch (IOException closed) {
                    // The socket was closed by the shutdown hook, or accepting failed on it;
                    // either way there is nothing further to accept here.
                    return;
                }
                try {
                    connections.execute(() -> relay(client, backendPort, connections));
                } catch (RuntimeException stopping) {
                    // Shutdown has begun, so this connection will not be served; it is closed
                    // now rather than left open on a client waiting for a reply.
                    closeConnection(client);
                    return;
                }
            }
        }, APP_NAME + ACCEPT_THREAD_SUFFIX);
        // Waiting for a connection must not be what keeps this process alive; the platform's
        // own listener threads do that, and the shutdown hook is what ends it.
        accepting.setDaemon(true);
        accepting.start();
    }

    private static void relay(Socket client, int backendPort, ExecutorService connections) {
        Integer source = null;
        Future<?> requests = null;
        // Both sockets are closed on the way out of this block, in every outcome below.
        try (Socket clientSocket = client; Socket backend = new Socket()) {
            backend.connect(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), backendPort),
                BACKEND_CONNECT_MILLIS);
            source = backend.getLocalPort();
            FORWARDED_SOURCES.add(source);
            InputStream fromClient = new BufferedInputStream(clientSocket.getInputStream(),
                MAX_REQUEST_LINE);
            // Requests are forwarded on one thread and replies carried back on this one, so
            // neither direction waits on the other and a kept-alive connection stays open.
            requests = connections.submit(() -> {
                forwardRequests(fromClient, backend);
                return null;
            });
            backend.getInputStream().transferTo(clientSocket.getOutputStream());
            clientSocket.getOutputStream().flush();
            // The last reply has been carried back, so no further request will be answered on
            // this connection: closing the receiving half ends the forwarding above at once
            // rather than leaving it waiting on a client that is still holding the socket open.
            clientSocket.shutdownInput();
            requests.get();
        } catch (IOException hungUp) {
            // A client that disappears mid-connection is an ordinary condition for a listener,
            // so it is named on one line without a trace and nothing else is disturbed.
            report(APP_NAME + ": connection ended early: " + hungUp.getClass().getSimpleName());
        } catch (ExecutionException failed) {
            report(APP_NAME + ": connection ended early: " + causeName(failed));
        } catch (InterruptedException interrupted) {
            // Shutdown reached this thread while it was waiting; the interrupt is restored so
            // the thread ends as interrupted rather than swallowing it.
            Thread.currentThread().interrupt();
        } catch (RuntimeException failed) {
            report(APP_NAME + ": serving a connection failed: "
                + failed.getClass().getSimpleName());
        } finally {
            if (requests != null) {
                requests.cancel(true);
            }
            if (source != null) {
                FORWARDED_SOURCES.remove(source);
            }
        }
    }

    private static String causeName(ExecutionException failed) {
        Throwable cause = failed.getCause();
        return cause == null ? failed.getClass().getSimpleName()
            : cause.getClass().getSimpleName();
    }

    private static void closeIngress(ServerSocket ingress) {
        try {
            ingress.close();
        } catch (IOException error) {
            report(APP_NAME + ": releasing the listening socket failed: "
                + error.getClass().getSimpleName());
        }
    }

    private static void closeConnection(Socket client) {
        try {
            client.close();
        } catch (IOException error) {
            report(APP_NAME + ": closing a connection failed: "
                + error.getClass().getSimpleName());
        }
    }

    private static boolean wasForwarded(HttpExchange exchange) {
        return FORWARDED_SOURCES.contains(exchange.getRemoteAddress().getPort());
    }

    private static void forwardRequests(InputStream fromClient, Socket backend)
            throws IOException {
        forward(fromClient, backend.getOutputStream());
        // The end of the client's requests is passed on as well, so the server behind knows
        // that no further request is coming.
        backend.shutdownOutput();
    }

    private static void forward(InputStream fromClient, OutputStream toBackend)
            throws IOException {
        while (true) {
            byte[] line = readLine(fromClient);
            // An empty line where a request line is expected is skipped rather than read as
            // one, which is what a server is asked to tolerate; forwarding it keeps this
            // stream a copy of the one that arrived.
            while (line != null && terminated(line) && blank(line)) {
                toBackend.write(line);
                line = readLine(fromClient);
            }
            if (line == null) {
                toBackend.flush();
                return;
            }
            if (!terminated(line)) {
                byte[] shortened = shortenedRequestLine(line);
                if (shortened == null) {
                    // Not a request line at all within the length examined, so it is forwarded
                    // as it stands and the rest of the connection with it.
                    toBackend.write(line);
                    fromClient.transferTo(toBackend);
                    toBackend.flush();
                    return;
                }
                // A target this long cannot be judged reachable, so the root goes in its place
                // and the rest of the line is dropped: the request is answered as an unknown
                // target, which is the answer a target of any length that matches nothing
                // gets, and the reply is this program's rather than the platform's page.
                toBackend.write(shortened);
                discardLine(fromClient);
            } else {
                toBackend.write(reachableRequestLine(line));
            }
            boolean carriesPayload = false;
            while (true) {
                byte[] header = readLine(fromClient);
                if (header == null) {
                    toBackend.flush();
                    return;
                }
                // Header lines are forwarded exactly as they arrived; only the request line is
                // ever rewritten, and only its target.
                toBackend.write(header);
                if (!terminated(header)) {
                    fromClient.transferTo(toBackend);
                    toBackend.flush();
                    return;
                }
                if (blank(header)) {
                    break;
                }
                if (declaresPayload(header)) {
                    carriesPayload = true;
                }
            }
            toBackend.flush();
            if (carriesPayload) {
                // A declared payload closes the connection once it is answered, so this is
                // the last request on it: the rest is forwarded without being read as lines,
                // and a payload can never be mistaken for the request line after it.
                fromClient.transferTo(toBackend);
                toBackend.flush();
                return;
            }
        }
    }

    private static byte[] readLine(InputStream fromClient) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(MAX_LINE_BUFFER);
        while (line.size() <= MAX_REQUEST_LINE) {
            int octet = fromClient.read();
            if (octet < 0) {
                return line.size() == 0 ? null : line.toByteArray();
            }
            line.write(octet);
            if (octet == LINE_FEED) {
                return line.toByteArray();
            }
        }
        return line.toByteArray();
    }

    private static boolean terminated(byte[] line) {
        return line.length <= MAX_REQUEST_LINE && line[line.length - 1] == LINE_FEED;
    }

    private static boolean blank(byte[] line) {
        for (int index = 0; index < line.length; index++) {
            if (line[index] != CARRIAGE_RETURN && line[index] != LINE_FEED) {
                return false;
            }
        }
        return true;
    }

    private static boolean declaresPayload(byte[] header) {
        String text = new String(header, StandardCharsets.ISO_8859_1);
        int colon = text.indexOf(COLON);
        if (colon < 0) {
            return false;
        }
        String name = trimBlanks(text.substring(0, colon));
        if (name.equalsIgnoreCase(TRANSFER_ENCODING_HEADER)) {
            return true;
        }
        if (!name.equalsIgnoreCase(CONTENT_LENGTH_HEADER)) {
            return false;
        }
        // The same reading as declaresBody() applies here: a length that is absent or zero
        // declares nothing, and anything else - including a length this program cannot read -
        // is treated as a payload rather than assumed away.
        String length = trimBlanks(text.substring(colon + 1));
        if (length.isEmpty()) {
            return false;
        }
        boolean zero = true;
        for (int index = 0; index < length.length(); index++) {
            char digit = length.charAt(index);
            if (digit < ZERO_DIGIT || digit > NINE_DIGIT) {
                return true;
            }
            if (digit != ZERO_DIGIT) {
                zero = false;
            }
        }
        return !zero;
    }

    private static byte[] reachableRequestLine(byte[] line) {
        String text = new String(line, StandardCharsets.ISO_8859_1);
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == LINE_FEED
                || text.charAt(end - 1) == CARRIAGE_RETURN)) {
            end--;
        }
        String terminator = text.substring(end);
        String head = text.substring(0, end);
        int method = head.indexOf(SPACE);
        if (method < 0) {
            // No target on this line at all, so there is none to make reachable and nothing
            // here to rewrite.
            return line;
        }
        int afterTarget = head.indexOf(SPACE, method + 1);
        String target = afterTarget < 0 ? head.substring(method + 1)
            : head.substring(method + 1, afterTarget);
        boolean reachable = matchesRootContext(target);
        if (reachable && afterTarget >= 0) {
            return line;
        }
        // A request line with no version is the oldest form of the protocol; the version the
        // other applications read it as is supplied here, so it is answered rather than
        // refused.
        String rest = afterTarget < 0 ? SPACE + DEFAULT_REQUEST_VERSION
            : head.substring(afterTarget);
        return (head.substring(0, method + 1) + (reachable ? target : ROOT_TARGET) + rest
            + terminator).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] shortenedRequestLine(byte[] line) {
        String head = new String(line, StandardCharsets.ISO_8859_1);
        int method = head.indexOf(SPACE);
        if (method < 0) {
            return null;
        }
        return (head.substring(0, method + 1) + ROOT_TARGET + SPACE + DEFAULT_REQUEST_VERSION
            + CARRIAGE_RETURN + LINE_FEED).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void discardLine(InputStream fromClient) throws IOException {
        int octet = fromClient.read();
        while (octet >= 0 && octet != LINE_FEED) {
            octet = fromClient.read();
        }
    }

    private static boolean matchesRootContext(String target) {
        // The one test the server behind applies: the path parsed out of the target, and
        // whether a context registered at the root could match it.
        try {
            String path = new URI(target).getPath();
            return path != null && path.startsWith(ROOT_TARGET);
        } catch (URISyntaxException unparseable) {
            return false;
        }
    }

    private static void limitRequestHandling() {
        System.setProperty(DRAIN_AMOUNT_PROPERTY, DRAIN_AMOUNT);
        System.setProperty(MAX_REQUEST_TIME_PROPERTY, MAX_REQUEST_SECONDS);
        System.setProperty(MAX_RESPONSE_TIME_PROPERTY, MAX_RESPONSE_SECONDS);
    }

    private static ExecutorService handlerPool() {
        // One virtual thread per exchange and per connection rather than a fixed pool, so
        // clients that stop sending cannot monopolise a small set of threads.
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
