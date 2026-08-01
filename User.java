import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

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
    private static final String STATUS_BAD_REQUEST = "BAD_REQUEST";
    private static final String STATUS_REQUEST_TIMEOUT = "REQUEST_TIMEOUT";
    private static final String STATUS_URI_TOO_LONG = "URI_TOO_LONG";
    private static final String STATUS_HEADER_FIELDS_TOO_LARGE =
        "REQUEST_HEADER_FIELDS_TOO_LARGE";

    private static final String GET_METHOD = "GET";
    private static final String HEAD_METHOD = "HEAD";

    // Every status this application can send, and no other.
    private static final int STATUS_OK = 200;
    private static final int BAD_REQUEST = 400;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int REQUEST_TIMEOUT = 408;
    private static final int URI_TOO_LONG = 414;
    private static final int HEADER_FIELDS_TOO_LARGE = 431;
    private static final int NO_REJECTION = 0;

    private static final String CONTENT_LENGTH_HEADER = "Content-Length";
    private static final String TRANSFER_ENCODING_HEADER = "Transfer-Encoding";
    private static final String CONNECTION_HEADER = "Connection";
    private static final String CLOSE_TOKEN = "close";
    private static final String KEEP_ALIVE_TOKEN = "keep-alive";

    // The two versions whose framing rules this application implements. Anything else is a
    // request it cannot answer on the terms the sender asked for, so it is refused.
    private static final String HTTP_1_1 = "HTTP/1.1";
    private static final String HTTP_1_0 = "HTTP/1.0";

    private static final String CRLF = "\r\n";
    private static final String DATE_FIELD = "Date: ";
    private static final String CONTENT_TYPE_FIELD = "Content-Type: application/json";
    private static final String CONTENT_LENGTH_FIELD = "Content-Length: ";
    private static final String CACHE_CONTROL_FIELD = "Cache-Control: no-store";
    private static final String ALLOW_FIELD = "Allow: ";
    private static final String CONNECTION_CLOSE_FIELD = "Connection: close";

    // The fixed-width form HTTP dates take, in English and in GMT whatever the host's locale
    // and zone are, because a field value is not localised text.
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
        .ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    // The punctuation a field name or a method may carry, from the token grammar.
    private static final String TOKEN_PUNCTUATION = "!#$%&'*+-.^_`|~";
    private static final char DELETE_CHARACTER = '\u007f';
    private static final char COMMA = ',';

    // What this application will read of one request before it stops rather than grow with
    // whatever a client chooses to send: a request line no longer than the target the other
    // two applications accept, and a head of bounded size and count behind it.
    private static final int MAX_REQUEST_LINE_BYTES = 65536;
    private static final int MAX_HEADER_BYTES = 65536;
    private static final int MAX_HEADER_FIELDS = 100;
    private static final int MAX_LEADING_EMPTY_LINES = 2;

    // A request or a reply that stops progressing is bounded, so no one client can hold a
    // handler thread for the lifetime of this process: the same bound applies to one read
    // and to reading a whole request, so a request arriving a byte at a time is bounded too.
    private static final int READ_TIMEOUT_MILLIS = 10000;
    private static final long MAX_REQUEST_NANOS = READ_TIMEOUT_MILLIS * 1000000L;
    private static final int LISTEN_BACKLOG = 128;
    private static final int ACCEPT_RETRY_MILLIS = 100;

    // How much of a payload this application never read it will take off a connection it is
    // closing, and for how long: enough that the close is orderly, bounded so that a client
    // still sending cannot make it wait.
    private static final int DISCARD_TIMEOUT_MILLIS = 1000;
    private static final int DISCARD_BUFFER_BYTES = 4096;
    private static final long MAX_DISCARD_BYTES = 65536;

    private static final String HANDLER_THREAD_PREFIX = "-health-";
    private static final long FIRST_HANDLER_THREAD = 0;

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

    private static String requestTarget(String target) {
        // The target exactly as the request line carried it, minus only the query string, so
        // /health?probe=1 matches while /health/, //health, ///health, an absolute-form
        // target and an encoded /%68ealth do not - the same rule the other two applications
        // apply to their own request target. Reading it from the request line rather than
        // from a parsed URI is what makes every one of those targets reachable here: a
        // target parsed as a URI loses //health to an authority component and an empty path,
        // which is a target no route can then be asked about.
        int query = target.indexOf('?');
        return query < 0 ? target : target.substring(0, query);
    }

    private static final class Request {
        // Only the parts of a request this application acts on. Every other field is read to
        // find the end of the head and then discarded, so nothing a client sends is stored
        // beyond the moment it is understood.
        private String method = "";
        private String target = "";
        private boolean version11;
        private boolean closeRequested;
        private boolean keepAliveRequested;
        private boolean lengthDeclared;
        private boolean encodingDeclared;
        private boolean declaresBody;
        private int rejection = NO_REJECTION;
    }

    private static Request readRequest(InputStream in) throws IOException {
        // The head is read a byte at a time up to the empty line that ends it, because the
        // request line has to be seen as sent: the routing rule this contract states is
        // about the target the client wrote, not about a normalised form of it.
        Request request = new Request();
        StringBuilder field = new StringBuilder();
        boolean haveRequestLine = false;
        int headerBytes = 0;
        int headerFields = 0;
        int emptyLines = 0;
        long deadline = 0;
        while (true) {
            int next;
            try {
                next = in.read();
            } catch (SocketTimeoutException waited) {
                if (!haveRequestLine && field.length() == 0) {
                    // Nothing of a request had arrived, so this is a kept-alive connection
                    // that went idle rather than one that stopped mid-request: it is closed
                    // as quietly as a client hanging up closes it.
                    return null;
                }
                request.rejection = REQUEST_TIMEOUT;
                return request;
            }
            if (next < 0) {
                if (!haveRequestLine && field.length() == 0) {
                    return null;
                }
                // A head that ended before its empty line frames nothing that can be
                // answered on the terms it was sent.
                request.rejection = BAD_REQUEST;
                return request;
            }
            if (deadline == 0) {
                // Timed from the first byte of this request rather than from the connection,
                // so a kept-alive connection is not charged for the wait between requests,
                // while a request that trickles in a byte at a time is still bounded.
                deadline = System.nanoTime() + MAX_REQUEST_NANOS;
            } else if (System.nanoTime() - deadline > 0) {
                request.rejection = REQUEST_TIMEOUT;
                return request;
            }
            if (next != '\n') {
                int remaining = haveRequestLine ? MAX_HEADER_BYTES - headerBytes
                    : MAX_REQUEST_LINE_BYTES;
                if (field.length() >= remaining) {
                    request.rejection = haveRequestLine ? HEADER_FIELDS_TOO_LARGE
                        : URI_TOO_LONG;
                    return request;
                }
                // Each byte is one character here, so the request line is compared as the
                // bytes it arrived as and no decoding can change what it matches.
                field.append((char) next);
                continue;
            }
            int consumed = field.length();
            String line = withoutTrailingReturn(field);
            field.setLength(0);
            if (line.isEmpty()) {
                if (!haveRequestLine) {
                    // An empty line before the request line is ignored rather than refused,
                    // which is what a sender that opens with one is owed - but only a
                    // sender's opening, not a stream of them in place of a request.
                    if (++emptyLines > MAX_LEADING_EMPTY_LINES) {
                        request.rejection = BAD_REQUEST;
                        return request;
                    }
                    continue;
                }
                return request;
            }
            if (!haveRequestLine) {
                haveRequestLine = true;
                if (!readRequestLine(request, line)) {
                    request.rejection = BAD_REQUEST;
                    return request;
                }
                continue;
            }
            headerBytes += consumed;
            if (++headerFields > MAX_HEADER_FIELDS) {
                request.rejection = HEADER_FIELDS_TOO_LARGE;
                return request;
            }
            readHeaderField(request, line);
            if (request.rejection != NO_REJECTION) {
                return request;
            }
        }
    }

    private static String withoutTrailingReturn(StringBuilder field) {
        int length = field.length();
        if (length > 0 && field.charAt(length - 1) == '\r') {
            return field.substring(0, length - 1);
        }
        return field.substring(0);
    }

    private static boolean readRequestLine(Request request, String line) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace <= 0) {
            return false;
        }
        int secondSpace = line.indexOf(' ', firstSpace + 1);
        if (secondSpace <= firstSpace + 1 || line.indexOf(' ', secondSpace + 1) >= 0) {
            // Three tokens, no more and no fewer: a request line with an unquoted space in
            // it is one whose target cannot be told from its version.
            return false;
        }
        String method = line.substring(0, firstSpace);
        String target = line.substring(firstSpace + 1, secondSpace);
        String version = line.substring(secondSpace + 1);
        if (!isToken(method) || !isPrintableTarget(target)) {
            return false;
        }
        if (!HTTP_1_1.equals(version) && !HTTP_1_0.equals(version)) {
            return false;
        }
        request.method = method;
        request.target = requestTarget(target);
        request.version11 = HTTP_1_1.equals(version);
        return true;
    }

    private static void readHeaderField(Request request, String line) {
        int colon = line.indexOf(':');
        String name = colon <= 0 ? "" : line.substring(0, colon);
        if (!isToken(name)) {
            // A field with no name, a name that is not a token, or a line folded onto the
            // one before it: a head this application will not guess the meaning of.
            request.rejection = BAD_REQUEST;
            return;
        }
        String value = trimBlanks(line.substring(colon + 1));
        if (CONTENT_LENGTH_HEADER.equalsIgnoreCase(name)) {
            readContentLength(request, value);
        } else if (TRANSFER_ENCODING_HEADER.equalsIgnoreCase(name)) {
            // A transfer coding frames a payload this application never reads, so the
            // connection is closed after the reply rather than reused.
            request.encodingDeclared = true;
            request.declaresBody = true;
        } else if (CONNECTION_HEADER.equalsIgnoreCase(name)) {
            // Accumulated rather than overwritten, so the option holds however many field
            // lines and however many tokens per line carry it.
            request.closeRequested |= hasToken(value, CLOSE_TOKEN);
            request.keepAliveRequested |= hasToken(value, KEEP_ALIVE_TOKEN);
        }
        if (request.lengthDeclared && request.encodingDeclared) {
            // Two framings for one payload: which of them a recipient believes decides where
            // the next request on this connection starts, so this one is refused rather than
            // answered on a guess.
            request.rejection = BAD_REQUEST;
        }
    }

    private static void readContentLength(Request request, String value) {
        if (request.lengthDeclared || value.isEmpty() || !isAsciiDigits(value)) {
            // A repeated or unreadable length frames a request this application cannot read,
            // so it is refused rather than answered as though the payload were absent.
            request.rejection = BAD_REQUEST;
            return;
        }
        request.lengthDeclared = true;
        for (int index = 0; index < value.length(); index++) {
            // Tested digit by digit rather than converted, so a length too long for any
            // numeric type still answers the one question asked of it.
            if (value.charAt(index) != '0') {
                request.declaresBody = true;
                return;
            }
        }
    }

    private static boolean hasToken(String value, String token) {
        int start = 0;
        while (start <= value.length()) {
            int comma = value.indexOf(COMMA, start);
            int end = comma < 0 ? value.length() : comma;
            if (token.equalsIgnoreCase(trimBlanks(value.substring(start, end)))) {
                return true;
            }
            if (comma < 0) {
                return false;
            }
            start = comma + 1;
        }
        return false;
    }

    private static boolean isToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= '0' && character <= '9')
                || (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || TOKEN_PUNCTUATION.indexOf(character) >= 0;
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPrintableTarget(String target) {
        if (target.isEmpty()) {
            return false;
        }
        for (int index = 0; index < target.length(); index++) {
            char character = target.charAt(index);
            if (character <= ' ' || character == DELETE_CHARACTER) {
                // A control character in a request line is refused rather than routed: it is
                // the one thing in a target that could frame a reply of its own.
                return false;
            }
        }
        return true;
    }

    private static boolean respond(OutputStream out, Request request) throws IOException {
        // A HEAD withholds a body whatever the status, and a request whose method was never
        // read is answered with one.
        boolean withBody = !HEAD_METHOD.equals(request.method);
        if (request.rejection != NO_REJECTION) {
            // A request that could not be read is answered in the same JSON as every other
            // reply, and its connection is then closed: where the next request would begin
            // on it is exactly what could not be established.
            writeResponse(out, request.rejection,
                statusPayload(rejectionStatus(request.rejection)), withBody, null, true);
            return false;
        }
        boolean health = HEALTH_PATH.equals(request.target);
        if (!GET_METHOD.equals(request.method) && !HEAD_METHOD.equals(request.method)) {
            // The method is tested before the target so that a rejected method is answered
            // the same way everywhere, not only on the one resource this application serves.
            // Closing after it means an unread payload can never be misread as the start of
            // the next request on this connection.
            if (health) {
                writeResponse(out, METHOD_NOT_ALLOWED,
                    statusPayload(STATUS_METHOD_NOT_ALLOWED), withBody, HEALTH_METHODS, true);
            } else {
                writeResponse(out, NOT_FOUND, statusPayload(STATUS_NOT_FOUND), withBody, null,
                    true);
            }
            return false;
        }
        // A declared payload is never read, so the connection closes after the reply for the
        // same reason; so does a connection the client or its version asked to close.
        boolean close = request.declaresBody || request.closeRequested
            || (!request.version11 && !request.keepAliveRequested);
        if (!health) {
            writeResponse(out, NOT_FOUND, statusPayload(STATUS_NOT_FOUND), withBody, null,
                close);
            return !close;
        }
        writeResponse(out, STATUS_OK, healthPayload(), withBody, null, close);
        return !close;
    }

    private static void writeResponse(OutputStream out, int status, String payload,
            boolean withBody, String allow, boolean close) throws IOException {
        // The byte count, never the character count: the two part company as soon as a value
        // stops being ASCII. A HEAD reply carries the same fields as the GET it stands in
        // for, this length among them, and withholds only the body.
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append(HTTP_1_1).append(' ').append(status).append(' ')
            .append(reasonPhrase(status)).append(CRLF);
        head.append(DATE_FIELD).append(HTTP_DATE.format(Instant.now())).append(CRLF);
        head.append(CONTENT_TYPE_FIELD).append(CRLF);
        head.append(CONTENT_LENGTH_FIELD).append(body.length).append(CRLF);
        head.append(CACHE_CONTROL_FIELD).append(CRLF);
        if (allow != null) {
            head.append(ALLOW_FIELD).append(allow).append(CRLF);
        }
        if (close) {
            head.append(CONNECTION_CLOSE_FIELD).append(CRLF);
        }
        head.append(CRLF);
        // Every field name and value written here is composed by this application and is
        // ASCII by construction, so this encoding is exact and no request text reaches it.
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        if (withBody) {
            out.write(body);
        }
        out.flush();
    }

    private static String rejectionStatus(int status) {
        // The token each refusal carries in its body. A request that could not be read at
        // all is the one the default names.
        switch (status) {
            case REQUEST_TIMEOUT:
                return STATUS_REQUEST_TIMEOUT;
            case URI_TOO_LONG:
                return STATUS_URI_TOO_LONG;
            case HEADER_FIELDS_TOO_LARGE:
                return STATUS_HEADER_FIELDS_TOO_LARGE;
            default:
                return STATUS_BAD_REQUEST;
        }
    }

    private static String reasonPhrase(int status) {
        // The phrase registered for each status this application sends, and no other; the
        // default is the phrase for the refusal it sends a request it could not read.
        switch (status) {
            case STATUS_OK:
                return "OK";
            case NOT_FOUND:
                return "Not Found";
            case METHOD_NOT_ALLOWED:
                return "Method Not Allowed";
            case REQUEST_TIMEOUT:
                return "Request Timeout";
            case URI_TOO_LONG:
                return "URI Too Long";
            case HEADER_FIELDS_TOO_LARGE:
                return "Request Header Fields Too Large";
            default:
                return "Bad Request";
        }
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
        // This application owns its listening socket rather than handing the port to a
        // server that routes on a parsed URI, which is what lets every target a request line
        // can carry - //health among them - be answered by writeResponse() above and by
        // nothing else. No other text can leave this process as a response body.
        ServerSocket listener = null;
        try {
            listener = new ServerSocket();
            // Set before binding: a listener that has just released this port can leave
            // connections in a closing state behind it, and this is what lets the next start
            // take the port back at once. It does not let a second listener share a port
            // already being listened on, which still fails as it should.
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(host, port), LISTEN_BACKLOG);
        } catch (IOException error) {
            // One readable line and a non-zero status instead of a stack trace.
            closeListener(listener);
            String where = DEFAULT_HOST.equals(host) ? host + ":" + port
                : "the address named by HEALTH_HOST, port " + port;
            report(APP_NAME + ": cannot bind " + where + ": " + bindReason(error));
            return 1;
        }
        // Requests are answered on threads of this listener's own rather than on the one it
        // accepts from, so a client that stops sending holds nothing but its own thread.
        ExecutorService handlers = handlerPool();
        ServerSocket bound = listener;
        // Registered before the first connection is accepted, so a signal arriving in the
        // same instant as that connection still finds a hook that releases the port. A signal
        // can also arrive before this line, because the bind above is what makes the listener
        // reachable: shutdown is then already under way and registering a hook throws. Left
        // alone that throw would leave main by way of the default handler, which prints a
        // trace to the error stream and would be cut off mid-sentence by the exit racing it.
        // That is not a condition to hand the user a trace for, so it is answered instead
        // with the one notice and the released port a completed startup would have given.
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                report(APP_NAME + ": shutting down");
                // Closing the listener releases the port and ends the wait for the next
                // connection, which is what returns the thread below out of serveHealth().
                closeListener(bound);
                // Connections already handed on are left to their own threads, and those are
                // ended here as well; an exchange still in flight is abandoned, which is what
                // a signalled shutdown asks for.
                handlers.shutdownNow();
            }));
        } catch (IllegalStateException shuttingDown) {
            // The hook was refused, so nothing else will report this shutdown or close the
            // socket the bind opened; when it is accepted it does both itself, and reporting
            // here as well would announce the one shutdown twice. Returning rather than
            // exiting leaves the status the signal produces untouched.
            report(APP_NAME + ": shutting down");
            closeListener(bound);
            handlers.shutdownNow();
            return 0;
        }
        report(APP_NAME + " " + APP_VERSION + " serving " + HEALTH_PATH + " on http://"
            + host + ":" + port);
        try {
            acceptConnections(bound, handlers);
        } finally {
            // Reached only once the listener is done with, whether it was closed by the hook
            // or by a condition the loop could not carry on through.
            closeListener(bound);
            handlers.shutdown();
        }
        return 0;
    }

    private static void acceptConnections(ServerSocket listener, ExecutorService handlers) {
        // Runs on the thread that called it and returns only once the listener is closed,
        // which is what keeps this process alive while it is serving: the threads answering
        // requests are virtual, and virtual threads do not hold a process open.
        boolean reported = false;
        while (true) {
            Socket connection;
            try {
                connection = listener.accept();
            } catch (IOException error) {
                if (listener.isClosed()) {
                    return;
                }
                if (!reported) {
                    // Named once per run of failures rather than once per failure, so a
                    // condition that persists cannot fill the error stream with one line per
                    // attempt while the listener waits for it to pass.
                    report(APP_NAME + ": cannot accept a connection: "
                        + error.getClass().getSimpleName());
                    reported = true;
                }
                pauseAfterFailedAccept();
                continue;
            }
            reported = false;
            try {
                connection.setSoTimeout(READ_TIMEOUT_MILLIS);
                // A reply of this size is one write, so it leaves without waiting for
                // anything else to be written after it.
                connection.setTcpNoDelay(true);
                handlers.execute(() -> serveConnection(connection));
            } catch (IOException | RejectedExecutionException refused) {
                // A connection that cannot be set up, or that arrives once the handlers have
                // been ended, is closed rather than left open with nothing serving it.
                closeConnection(connection);
            }
        }
    }

    private static void serveConnection(Socket connection) {
        try (Socket open = connection;
                InputStream in = new BufferedInputStream(open.getInputStream());
                OutputStream out = new BufferedOutputStream(open.getOutputStream())) {
            while (true) {
                Request request = readRequest(in);
                if (request == null) {
                    // The client hung up, or sent nothing for as long as this application
                    // waits: either way there is nothing left on this connection to answer.
                    return;
                }
                if (!respond(out, request)) {
                    endConnection(open, in);
                    return;
                }
            }
        } catch (SocketException ended) {
            // A probe hanging up mid-exchange is routine, so it is named on one line rather
            // than reported as a failure of this application.
            report(APP_NAME + ": client disconnected: " + ended.getClass().getSimpleName());
        } catch (IOException | RuntimeException error) {
            // The condition is named on one line, without a trace, and the listener carries
            // on serving.
            report(APP_NAME + ": request handling failed: " + error.getClass().getSimpleName());
        }
    }

    private static void endConnection(Socket open, InputStream in) {
        try {
            // The reply is written and this connection is finished with, so the sending half
            // is closed first: the client is told the response is complete before this end
            // goes away. Then a bounded amount of whatever is still arriving - a payload this
            // application never read, most often - is taken off the connection, because
            // closing it with bytes still queued resets it instead of ending it, and a reset
            // can cost the client the reply it is in the middle of reading.
            open.shutdownOutput();
            open.setSoTimeout(DISCARD_TIMEOUT_MILLIS);
            byte[] discard = new byte[DISCARD_BUFFER_BYTES];
            long remaining = MAX_DISCARD_BYTES;
            while (remaining > 0) {
                int read = in.read(discard, 0, (int) Math.min(discard.length, remaining));
                if (read < 0) {
                    return;
                }
                remaining -= read;
            }
        } catch (IOException ended) {
            // Nothing is owed to a connection already finished with: the client hung up, or
            // stopped sending, and either way the close that follows is all that is left to
            // do. Reporting it would name a routine end to an exchange as a failure.
        }
    }

    private static void pauseAfterFailedAccept() {
        try {
            Thread.sleep(ACCEPT_RETRY_MILLIS);
        } catch (InterruptedException interrupted) {
            // Cutting the wait short only brings the next attempt forward; the interrupt is
            // restored so nothing later in this thread loses it.
            Thread.currentThread().interrupt();
        }
    }

    private static void closeListener(ServerSocket listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.close();
        } catch (IOException error) {
            // Closing is the last use this application has for the socket, so a failure to
            // close changes nothing that follows it: it is named on one line rather than
            // raised into a shutdown path that could not act on it.
            report(APP_NAME + ": cannot close the listener: "
                + error.getClass().getSimpleName());
        }
    }

    private static void closeConnection(Socket connection) {
        try {
            connection.close();
        } catch (IOException error) {
            report(APP_NAME + ": cannot close a connection: "
                + error.getClass().getSimpleName());
        }
    }

    private static ExecutorService handlerPool() {
        // One virtual thread per connection rather than a fixed pool, so clients that stop
        // sending cannot monopolise a small set of handler threads.
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
