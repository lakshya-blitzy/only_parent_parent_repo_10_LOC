/*
 * =============================================================================
 * User.java - the Java application of this polyglot repository, and the Java
 *             implementation of the shared /health endpoint (feature F-009).
 * =============================================================================
 *
 * THREE MODES
 * -----------
 *   java User          Prints "Test" and exits 0. This is the program's
 *                      original, pre-existing behaviour and it is preserved
 *                      byte for byte: the two statements that produce it are
 *                      unchanged down to their indentation, and this path
 *                      performs no configuration read, no network bind and no
 *                      write to any stream other than that single line.
 *   java User --serve  Binds an HTTP listener and serves the health endpoint
 *                      until the process is terminated.
 *   java User --probe  Performs one in-process GET against its own health
 *                      endpoint and exits 0 (healthy) or 1 (unhealthy).
 *
 * An unrecognised argument falls through to the default mode: no usage message,
 * no diagnostic, and an exit status of 0.
 *
 * WHY --probe LIVES INSIDE THIS CLASS
 * -----------------------------------
 * The container's Java runtime stage is a JRE: it ships no compiler, so a
 * separate probe source file could not be compiled there, and slim runtime
 * images ship neither curl nor wget. Keeping the probe inside User is what
 * makes "HEALTHCHECK CMD java -cp /app User --probe" work with no extra
 * tooling, no image growth and no added attack surface.
 *
 * ZERO DEPENDENCIES
 * -----------------
 * JDK only. The listener is a {@code java.net.ServerSocket} from java.base and
 * the self-check uses java.net.http, so no --add-modules flag, no build tool
 * and no third-party library is needed anywhere, for the application or for its
 * tests. The JDK ships no JSON serializer, and that is the one place this
 * implementation differs in mechanism from its Python and JavaScript siblings:
 * the payload is assembled by hand through an explicit escape helper, and the
 * result is byte-identical to theirs for identical configuration.
 *
 * WHY THE LISTENER IS A RAW SOCKET AND NOT com.sun.net.httpserver
 * --------------------------------------------------------------
 * The frozen contract fixes the response header set at exactly Content-Type,
 * Cache-Control and Content-Length - plus Allow on a 405 - and fixes every
 * error body as JSON. com.sun.net.httpserver satisfies neither, and its default
 * executor introduces a denial of service; all four failures were reproduced by
 * execution before this listener replaced it:
 *   1. It writes its own Date response header, which an application cannot set,
 *      blank or remove.
 *   2. It normalises response header names, emitting "Content-type" where the
 *      other two implementations emit "Content-Type".
 *   3. It answers a request that its own URI or request-line parser rejects
 *      with its own HTML page - carrying no cache directives, and in one case
 *      naming the exception it caught - before any handler is reached.
 *   4. Its default executor runs every exchange on the single dispatcher
 *      thread, so one client that connects and then stalls mid-request blocks
 *      every other client for as long as it holds the socket.
 * Owning the socket removes all four at once: this class writes every response
 * byte itself and answers each connection on its own virtual thread. It also
 * needs nothing beyond java.base, so the container's JRE stage requires no
 * module that a JRE might omit.
 *
 * DEFAULT UNNAMED PACKAGE
 * -----------------------
 * There is deliberately no package declaration. That is what keeps both
 * "java -cp . User" and "java User.java" single-file source launch working, and
 * it is what lets the sibling UserTest.java reach the package-private helpers
 * below with no classpath, no build step and no test framework.
 *
 * THE FROZEN RESPONSE CONTRACT (identical in app.py, index.js and User.java)
 * -------------------------------------------------------------------------
 *   GET <health.path>     200 and a compact JSON body whose keys appear in this
 *                         exact order: name, version, timestamp, status. For
 *                         example (108 bytes with the shipped configuration):
 *                         {"name":"only_parent_parent_repo_10_LOC","version":
 *                         "1.1.0","timestamp":"2026-07-28T13:47:08Z","status":
 *                         "UP"}
 *   GET any other path    404 and {"error":"Not Found"}
 *   Any other method      405, {"error":"Method Not Allowed"} and Allow: GET
 *   Malformed request     400 and {"error":"Bad Request"}
 *   Request line too long 414 and {"error":"URI Too Long"}
 *   Header block too big  431 and {"error":"Request Header Fields Too Large"}
 *   HTTP major not 1      505 and {"error":"HTTP Version Not Supported"}
 *   Headers set here      Content-Type: application/json
 *                         Cache-Control: no-cache, no-store, must-revalidate
 *                         Content-Length, from the encoded byte length
 *                         Allow: GET, on the 405 response only
 *                         Connection: close, on the four error statuses only,
 *                         which are the only responses after which this server
 *                         stops reading from the connection
 *
 * A 200, a 404 and a 405 therefore carry exactly three headers - no Date, no
 * Server, no Connection, no Keep-Alive - which is byte for byte the header set
 * app.py and index.js emit, so one case-insensitive assertion set covers all
 * three implementations.
 *
 * The query string is stripped before matching and one optional trailing slash
 * is accepted, so /health, /health/ and /health?x=1 all reach the endpoint.
 * Nothing else is forgiven: the request target is matched verbatim, with no
 * percent-decoding, no dot-segment resolution and no collapsing of repeated
 * leading slashes, so //health, /health%2f and /health/../health are all 404
 * exactly as they are in the other two implementations.
 * A health response is never cacheable: a cached health answer is worse than
 * no health answer at all.
 *
 * TWO DELIBERATE DEVIATIONS FROM draft-inadarei-api-health-check-06
 * -----------------------------------------------------------------
 *   1. The media type is plain application/json rather than the draft's
 *      health-specific type, because plain JSON is what generic tooling and
 *      the shared verification script expect.
 *   2. HEAD is answered with 405 rather than being supported alongside GET.
 *      RFC 9110 expects HEAD wherever GET is supported, so this is a documented
 *      deferral rather than an oversight; the 405 answer to a HEAD request
 *      correctly carries no body.
 * The literal status value "UP" is the draft's own recognised alias for a
 * passing status, and a passing status is required to use a 2xx code, which is
 * why this endpoint answers 200 rather than 204.
 *
 * REQUEST CLASSIFICATION ORDER (identical in app.py, index.js and User.java)
 * -------------------------------------------------------------------------
 * Every request is graded in exactly this order, and the first rule that fires
 * decides the response. The order is what makes the three implementations agree
 * on requests that break more than one rule at once.
 *   1. A request line longer than {@value #MAX_REQUEST_LINE_BYTES} bytes -> 414.
 *   2. A request line that is not METHOD SP target SP HTTP-version, a method
 *      that is not an RFC 9110 token, or a target carrying a space, a control
 *      character or a byte outside visible US-ASCII -> 400.
 *   3. An HTTP major version other than 1 -> 505.
 *   4. A header block over {@value #MAX_HEADER_BLOCK_BYTES} bytes or over
 *      {@value #MAX_HEADER_FIELDS} fields -> 431.
 *   5. An HTTP/1.1 request with no Host header -> 400.
 *   6. Any method other than the exact token GET -> 405 with Allow: GET. This
 *      is total: HEAD, OPTIONS, CONNECT, TRACE, PROPFIND and any unknown token
 *      all reach it, and none of them is ever echoed back to the caller.
 *   7. Otherwise the normalised target either equals the configured route -> 200
 *      with the health document, or it does not -> 404.
 *
 * CONCURRENCY AND CONNECTION LIFETIME
 * -----------------------------------
 * One virtual thread per accepted connection, dispatched by a single non-daemon
 * acceptor thread that is also what keeps the process alive in --serve mode. A
 * stalled or half-open connection therefore costs one parked virtual thread and
 * nothing else; it cannot delay any other client. Every socket carries a
 * {@value #IDLE_TIMEOUT_MILLIS} ms idle timeout so a connection that goes quiet
 * mid-request is reclaimed rather than held forever, persistent connections are
 * reused for as many requests as the client sends, and a request body is drained
 * before the response so that an unwanted body cannot desynchronise the stream
 * or provoke a connection reset instead of a reply.
 *
 * LEAST DISCLOSURE
 * ----------------
 * The payload carries exactly the four required fields and nothing else. Error
 * bodies are fixed strings: the requested path, the request method and any
 * exception detail are never echoed to a caller, only to stderr. No
 * interpreter, framework or server banner is exposed.
 */
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class User {
    /**
     * Three-way entry point: serve, probe, or the original default behaviour.
     *
     * <p>The dispatcher is written as guard clauses rather than an if/else
     * chain for one specific reason: it keeps the two preserved statements of
     * the original program at their original indentation and in their original
     * form, so the default path is provably unchanged rather than merely
     * equivalent. An unrecognised argument reaches those same statements, which
     * is why no usage message is printed and the exit status stays 0.
     *
     * <p>The argument vector is read here for the first time in this
     * program's history; it was previously declared and ignored.
     *
     * @param args process arguments; {@code --serve} and {@code --probe} select
     *             the two new modes and every other value selects the default
     */
    public static void main(String[] args) {
        if (hasFlag(args, FLAG_SERVE)) {
            serve();
            return;
        }
        if (hasFlag(args, FLAG_PROBE)) {
            System.exit(probe());
            return; // Defensive: System.exit does not return, but this keeps
                    // the default branch unreachable from the probe path even
                    // if that ever changed.
        }
        String name = "Test";
        System.out.println(name);
    }

    // -------------------------------------------------------------------------
    // Mode selection and process exit status
    // -------------------------------------------------------------------------

    /** Selects the long-running HTTP listener mode. */
    private static final String FLAG_SERVE = "--serve";

    /** Selects the one-shot self-check mode consumed by the container probe. */
    private static final String FLAG_PROBE = "--probe";

    /** Process exit status meaning "healthy" to a container health check. */
    private static final int EXIT_SUCCESS = 0;

    /** Process exit status meaning "unhealthy", and the fail-closed default. */
    private static final int EXIT_FAILURE = 1;

    // -------------------------------------------------------------------------
    // Configuration: the single cross-language source of truth
    //
    // Precedence, identical in all three implementations:
    //     environment variable  >  app.config.properties  >  built-in default
    // and for the listener port the universal PORT variable outranks even the
    // language-specific JAVA_PORT, following the twelve-factor convention for
    // one application per container.
    // -------------------------------------------------------------------------

    /**
     * Name of the shared properties file.
     *
     * <p>It is looked for beside the code source first - the directory holding
     * {@code User.class}, or holding {@code User.java} under a single-file source
     * launch - and only then in the working directory. That is what makes this
     * implementation agree with app.py and index.js, which resolve the same file
     * relative to {@code __file__} and {@code __dirname}: all three find the
     * repository's configuration no matter which directory the process was
     * started from, and the container layout that copies the file next to the
     * compiled class keeps working unchanged.
     */
    private static final String CONFIG_FILE = "app.config.properties";

    /** Optional absolute or relative override for the properties file path. */
    private static final String CONFIG_FILE_ENV = "APP_CONFIG_FILE";

    private static final String KEY_APP_NAME = "app.name";
    private static final String ENV_APP_NAME = "APP_NAME";
    private static final String DEFAULT_APP_NAME = "only_parent_parent_repo_10_LOC";

    private static final String KEY_APP_VERSION = "app.version";
    private static final String ENV_APP_VERSION = "APP_VERSION";
    private static final String DEFAULT_APP_VERSION = "1.1.0";

    private static final String KEY_HEALTH_PATH = "health.path";
    private static final String ENV_HEALTH_PATH = "HEALTH_PATH";
    private static final String DEFAULT_HEALTH_PATH = "/health";

    private static final String KEY_APP_HOST = "app.host";
    private static final String ENV_APP_HOST = "APP_HOST";

    /**
     * The wildcard bind address is deliberate: a container health probe or an
     * orchestrator cannot reach a listener bound to loopback.
     */
    private static final String DEFAULT_APP_HOST = "0.0.0.0";

    private static final String KEY_JAVA_PORT = "java.port";
    private static final String ENV_JAVA_PORT = "JAVA_PORT";
    private static final String ENV_UNIVERSAL_PORT = "PORT";

    /**
     * One distinct default port per language lets all three servers run
     * concurrently on a single CI runner without a bind collision.
     */
    private static final int DEFAULT_JAVA_PORT = 8002;

    /** Lowest accepted port; 0 is legal and asks the OS for an ephemeral port. */
    private static final int MIN_PORT = 0;

    /** Highest accepted port. */
    private static final int MAX_PORT = 65535;

    // -------------------------------------------------------------------------
    // The frozen wire contract
    // -------------------------------------------------------------------------

    /** The one and only value this endpoint reports for a passing status. */
    private static final String STATUS_UP = "UP";

    /** What a healthy body must contain; used by the self-check. */
    private static final String STATUS_UP_FRAGMENT = "\"status\":\"UP\"";

    /**
     * HTTP method tokens are case-sensitive per RFC 9110, so these comparisons
     * are deliberately case-sensitive: "get" is not GET and is answered 405.
     */
    private static final String METHOD_GET = "GET";

    /** A HEAD response must carry no body, which is handled explicitly. */
    private static final String METHOD_HEAD = "HEAD";

    /**
     * The complete response head, written verbatim on every contract response.
     *
     * <p>Composed here rather than through a header map so that the field names,
     * their casing and their order are literally the bytes that reach the wire:
     * a 200, a 404 and a 405 carry these three fields and nothing else, matching
     * app.py and index.js field for field.
     */
    private static final String HEADER_CONTENT_TYPE = "Content-Type: application/json";
    private static final String HEADER_CACHE_CONTROL =
            "Cache-Control: no-cache, no-store, must-revalidate";
    private static final String HEADER_CONTENT_LENGTH_PREFIX = "Content-Length: ";
    private static final String HEADER_ALLOW_GET = "Allow: " + METHOD_GET;
    private static final String HEADER_CONNECTION_CLOSE = "Connection: close";

    /** Line terminator required between HTTP header fields. */
    private static final String CRLF = "\r\n";

    /** Version this server speaks, and the only major version it accepts. */
    private static final String HTTP_VERSION = "HTTP/1.1";

    private static final int HTTP_OK = 200;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_URI_TOO_LONG = 414;
    private static final int HTTP_HEADERS_TOO_LARGE = 431;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final int HTTP_VERSION_NOT_SUPPORTED = 505;

    private static final String REASON_OK = "OK";
    private static final String REASON_BAD_REQUEST = "Bad Request";
    private static final String REASON_NOT_FOUND = "Not Found";
    private static final String REASON_METHOD_NOT_ALLOWED = "Method Not Allowed";
    private static final String REASON_URI_TOO_LONG = "URI Too Long";
    private static final String REASON_HEADERS_TOO_LARGE = "Request Header Fields Too Large";
    private static final String REASON_INTERNAL_ERROR = "Internal Server Error";
    private static final String REASON_VERSION_NOT_SUPPORTED = "HTTP Version Not Supported";

    /** Fixed error bodies: nothing about the request is ever reflected back. */
    private static final String BODY_BAD_REQUEST = "{\"error\":\"Bad Request\"}";
    private static final String BODY_NOT_FOUND = "{\"error\":\"Not Found\"}";
    private static final String BODY_METHOD_NOT_ALLOWED = "{\"error\":\"Method Not Allowed\"}";
    private static final String BODY_URI_TOO_LONG = "{\"error\":\"URI Too Long\"}";
    private static final String BODY_HEADERS_TOO_LARGE =
            "{\"error\":\"Request Header Fields Too Large\"}";
    private static final String BODY_INTERNAL_ERROR = "{\"error\":\"Internal Server Error\"}";
    private static final String BODY_VERSION_NOT_SUPPORTED =
            "{\"error\":\"HTTP Version Not Supported\"}";

    // -------------------------------------------------------------------------
    // Server, probe and formatting limits
    // -------------------------------------------------------------------------

    /** The path every normalisation falls back to, and the route prefix. */
    private static final String ROOT_PATH = "/";

    /** Listen backlog; generous enough that a burst of clients is queued, not refused. */
    private static final int SERVER_BACKLOG = 128;

    /** Name of the acceptor thread, which is what keeps --serve mode alive. */
    private static final String ACCEPTOR_THREAD_NAME = "health-acceptor";

    /** Name of the shutdown hook thread that closes the listening socket. */
    private static final String SHUTDOWN_THREAD_NAME = "health-shutdown";

    /**
     * Idle budget for one connection, in milliseconds.
     *
     * <p>Applied to the socket, so it bounds both the wait for a request line on
     * a reused connection and the wait for the rest of a request that arrived in
     * pieces. A client that connects and then says nothing is dropped after this
     * long instead of parking a thread forever; it can never delay another
     * client, because it holds nothing but its own virtual thread. The value
     * matches app.py's connection timeout so all three implementations reclaim an
     * abandoned connection on the same schedule.
     */
    private static final int IDLE_TIMEOUT_MILLIS = 30_000;

    /**
     * Grace period for reading whatever a client is still sending after an error
     * response, in milliseconds. Draining briefly before closing is what lets the
     * client read the response instead of seeing a connection reset.
     */
    private static final int LINGER_TIMEOUT_MILLIS = 1_000;

    /** Read and write buffer size for one connection. */
    private static final int IO_BUFFER_BYTES = 8192;

    /** Initial capacity for a request-line or header-field buffer. */
    private static final int LINE_CAPACITY = 128;

    /** Initial capacity for the assembled response head. */
    private static final int HEAD_CAPACITY = 192;

    /** A request line longer than this is answered 414 rather than parsed. */
    private static final int MAX_REQUEST_LINE_BYTES = 65_536;

    /** A single header field line longer than this is answered 431. */
    private static final int MAX_HEADER_LINE_BYTES = 16_384;

    /** A header block larger than this in total is answered 431. */
    private static final int MAX_HEADER_BLOCK_BYTES = 16_384;

    /** More header fields than this is answered 431. */
    private static final int MAX_HEADER_FIELDS = 100;

    /** Exactly three space-separated tokens make a well-formed request line. */
    private static final int REQUEST_LINE_TOKENS = 3;

    /** Connect and read budget for the self-check; it must fail fast, not hang. */
    private static final int PROBE_TIMEOUT_SECONDS = 3;

    /** Loopback target used by the self-check. */
    private static final String LOOPBACK_HOST = "127.0.0.1";

    /** IPv4 wildcard, which is not a routable target for the self-check. */
    private static final String WILDCARD_HOST_V4 = "0.0.0.0";

    /** IPv6 wildcard, which is likewise not a routable target. */
    private static final String WILDCARD_HOST_V6 = "::";

    /**
     * Upper bound on request-body bytes drained before the connection is closed.
     *
     * <p>The endpoint never inspects a body, but bytes left unread desynchronise
     * a persistent connection and closing a socket with unread data queued makes
     * the kernel answer with a reset - which is what turned a large POST into "no
     * HTTP response at all" before this cap was raised. Eight mebibytes is far
     * above any body a health request could plausibly carry, so every realistic
     * request is drained in full and answered on a connection that stays usable;
     * a body beyond the cap is still answered, and the connection is then retired
     * rather than reused, because its framing can no longer be trusted.
     */
    private static final long MAX_REQUEST_DRAIN_BYTES = 8L * 1024L * 1024L;

    /** Bytes read from an abandoned request before an error close gives up. */
    private static final long MAX_LINGER_DRAIN_BYTES = 1024L * 1024L;

    /** Largest chunked body this server will drain, in bytes. */
    private static final long MAX_CHUNKED_BODY_BYTES = MAX_REQUEST_DRAIN_BYTES;

    /** Largest number of chunked-encoding trailer fields tolerated. */
    private static final int MAX_TRAILER_FIELDS = MAX_HEADER_FIELDS;

    /** Interim response sent when a client asks permission before sending a body. */
    private static final String CONTINUE_RESPONSE = HTTP_VERSION + " 100 Continue" + CRLF + CRLF;

    /** Request header names this server acts on, compared case-insensitively. */
    private static final String HEADER_NAME_HOST = "host";
    private static final String HEADER_NAME_CONTENT_LENGTH = "content-length";
    private static final String HEADER_NAME_TRANSFER_ENCODING = "transfer-encoding";
    private static final String HEADER_NAME_CONNECTION = "connection";
    private static final String HEADER_NAME_EXPECT = "expect";

    private static final String TRANSFER_ENCODING_CHUNKED = "chunked";
    private static final String CONNECTION_CLOSE = "close";
    private static final String CONNECTION_KEEP_ALIVE = "keep-alive";
    private static final String EXPECT_CONTINUE = "100-continue";

    /** Radix of a chunk-size line in a chunked request body. */
    private static final int CHUNK_SIZE_RADIX = 16;

    /** Characters other than ALPHA and DIGIT that may appear in a method token. */
    private static final String METHOD_TOKEN_SPECIALS = "!#$%&'*+-.^_`|~";

    /** Lowest byte value a request target may contain; SP and the CTLs are out. */
    private static final char TARGET_MIN_CHAR = 0x21;

    /** Highest byte value a request target may contain; DEL and above are out. */
    private static final char TARGET_MAX_CHAR = 0x7E;

    /** Scheme separator that marks an absolute-form request target. */
    private static final String SCHEME_SEPARATOR = "://";

    /** Slack added when sizing the escape buffer, to avoid an early re-grow. */
    private static final int JSON_ESCAPE_HEADROOM = 16;

    /** Initial capacity for the payload buffer; the shipped body is 108 bytes. */
    private static final int PAYLOAD_CAPACITY = 128;

    /** Prefix for every diagnostic; all diagnostics go to stderr, never stdout. */
    private static final String LOG_PREFIX = "[User] ";

    // -------------------------------------------------------------------------
    // Argument handling
    // -------------------------------------------------------------------------

    /**
     * Reports whether an exact flag is present in the argument vector.
     *
     * <p>Matching is exact and order-independent, so flags may be combined and
     * may appear in any position. Unknown arguments are ignored rather than
     * rejected, which is what allows the default mode to stay reachable.
     *
     * @param args the process argument vector, possibly {@code null} or empty
     * @param flag the exact flag to look for
     * @return {@code true} when {@code args} contains {@code flag}
     */
    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Configuration resolution
    // -------------------------------------------------------------------------

    /**
     * Reads and parses the properties file.
     *
     * <p>The file is read on every call rather than cached for the lifetime of
     * the process, which is what makes this implementation behave like its two
     * siblings: index.js reads it when a server is created and app.py reads it
     * per request, so a process-wide cache here was the one place where the same
     * running configuration could be resolved differently. Every call site in
     * this class resolves a whole {@link Config} in one go, so serving a request
     * still touches the filesystem exactly never - the snapshot is taken once,
     * when the server is created.
     *
     * <p>The returned instance is empty - never {@code null} - when the file is
     * absent or unreadable, so callers always fall back to their built-in
     * defaults rather than failing.
     *
     * @return the file-backed configuration, possibly empty
     */
    static Properties configProperties() {
        return loadProperties();
    }

    /**
     * Reads the properties file, treating its absence as a normal condition.
     *
     * <p>The file is read as UTF-8 through {@code Properties.load(Reader)}
     * rather than as ISO-8859-1 bytes, because the Python and JavaScript
     * loaders of the same file both decode UTF-8; matching them is what keeps a
     * non-ASCII application name byte-identical across all three
     * implementations instead of mangling it in this one.
     *
     * <p>Failure is silent by design. A container copies the file in, but the
     * application must still serve when it is missing, unreadable or truncated,
     * and a health endpoint that refuses to start because its optional
     * configuration is absent defeats its own purpose.
     *
     * @return a populated {@link Properties}, or an empty one on any I/O failure
     */
    private static Properties loadProperties() {
        Properties loaded = new Properties();
        Path location = configLocation();
        if (location == null) {
            return loaded;
        }
        try (BufferedReader reader = Files.newBufferedReader(location, StandardCharsets.UTF_8)) {
            loaded.load(reader);
        } catch (IOException absentOrUnreadable) {
            // Intentionally silent: the configuration file is optional and every
            // key it can supply has a built-in default. Nothing is printed here
            // so that no mode of this program writes an unexpected diagnostic.
            return new Properties();
        } catch (IllegalArgumentException malformed) {
            // A malformed escape sequence in the file is likewise not fatal.
            return new Properties();
        }
        return loaded;
    }

    /**
     * Chooses which properties file to read.
     *
     * <p>Three candidates are tried in a fixed order: the {@value #CONFIG_FILE_ENV}
     * override, then the file sitting beside this class's own code source, then
     * the working directory. The middle candidate is the one that matters: app.py
     * resolves the file relative to {@code __file__} and index.js relative to
     * {@code __dirname}, so resolving it only against the working directory made
     * Java the single implementation that lost its configuration when the process
     * was started from another directory.
     *
     * @return the path to read, or {@code null} if no candidate can be formed
     */
    private static Path configLocation() {
        String override = System.getenv(CONFIG_FILE_ENV);
        if (override != null && !override.isEmpty()) {
            try {
                return Path.of(override);
            } catch (InvalidPathException unusable) {
                System.err.println(LOG_PREFIX + "ignoring unusable " + CONFIG_FILE_ENV + " value");
                return null;
            }
        }
        Path beside = codeSourceDirectory();
        if (beside != null) {
            Path candidate = beside.resolve(CONFIG_FILE);
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }
        try {
            return Path.of(CONFIG_FILE);
        } catch (InvalidPathException impossible) {
            return null;
        }
    }

    /**
     * Locates the directory this class was loaded from.
     *
     * <p>Under {@code java -cp <dir> User} the code source is the classpath
     * directory; under a {@code java User.java} source launch it is the source
     * file itself, whose parent is the same directory. Both cases are handled, and
     * anything unexpected - a jar, a module image, no code source at all - yields
     * {@code null} so that the caller falls back to the working directory.
     *
     * @return the directory holding this class's code source, or {@code null}
     */
    private static Path codeSourceDirectory() {
        try {
            CodeSource source = User.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            URL location = source.getLocation();
            if (location == null) {
                return null;
            }
            Path path = Path.of(location.toURI());
            return Files.isDirectory(path) ? path : path.getParent();
        } catch (URISyntaxException | IllegalArgumentException
                | UnsupportedOperationException | SecurityException notAFilesystemPath) {
            // A jar, a module image, a non-file URL or a path this filesystem cannot
            // express: every one of them simply means "look in the working directory".
            return null;
        }
    }

    /**
     * Resolves one configuration value against the fixed precedence order.
     *
     * <p>Empty values are treated as absent at every level, so exporting an
     * empty environment variable does not blank out a configured value.
     *
     * @param props    file-backed configuration, may be {@code null} or empty
     * @param key      properties key to read
     * @param envName  environment variable that overrides the file
     * @param fallback built-in default used when neither source supplies a value
     * @return the effective value, never {@code null}
     */
    static String resolve(Properties props, String key, String envName, String fallback) {
        String fromEnvironment = System.getenv(envName);
        if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
            return fromEnvironment;
        }
        String fromFile = (props == null) ? null : props.getProperty(key);
        if (fromFile != null && !fromFile.isEmpty()) {
            return fromFile;
        }
        return fallback;
    }

    /**
     * Resolves the listener port, adding the universal {@code PORT} override on
     * top of the standard precedence order, and fails closed on bad input.
     *
     * <p>A value that is not a number, or that falls outside the legal port
     * range, is rejected rather than quietly replaced by the built-in default.
     * Silently substituting a different port is the worse failure of the two: an
     * operator who mistypes {@code PORT} then gets a healthy-looking process
     * listening somewhere they are not watching, while every probe aimed at the
     * port they asked for fails. app.py already refused such a value, so refusing
     * it here is also what makes the three implementations agree.
     *
     * @param props           file-backed configuration, may be {@code null}
     * @param key             properties key holding the language-specific port
     * @param envName         language-specific environment override
     * @param universalEnvName universal override, which outranks all others
     * @param fallback        built-in default port
     * @return a port in the range {@value #MIN_PORT}..{@value #MAX_PORT}
     * @throws IllegalArgumentException if the resolved value is not a legal port
     */
    static int resolvePort(Properties props, String key, String envName,
            String universalEnvName, int fallback) {
        String universal = System.getenv(universalEnvName);
        String raw = (universal != null && !universal.isEmpty())
                ? universal
                : resolve(props, key, envName, Integer.toString(fallback));
        String trimmed = raw.trim();
        try {
            int parsed = Integer.parseInt(trimmed);
            if (parsed >= MIN_PORT && parsed <= MAX_PORT) {
                return parsed;
            }
        } catch (NumberFormatException notANumber) {
            // Fall through to the rejection below; the value is reported there.
        }
        throw new IllegalArgumentException("invalid port value: " + trimmed);
    }

    /**
     * @return the effective application name reported as the payload's
     *         {@code name} field
     */
    static String appName() {
        return resolve(configProperties(), KEY_APP_NAME, ENV_APP_NAME, DEFAULT_APP_NAME);
    }

    /**
     * @return the effective application version reported as the payload's
     *         {@code version} field, and kept in step with the project
     *         manifests and the README by the version-consistency gate
     */
    static String appVersion() {
        return resolve(configProperties(), KEY_APP_VERSION, ENV_APP_VERSION, DEFAULT_APP_VERSION);
    }

    /**
     * Returns the effective health path, normalised for matching.
     *
     * <p>A configured value with no leading slash gains one and a configured
     * trailing slash is removed, so a path written as {@code health},
     * {@code /health} or {@code /health/} all behave identically. The result is
     * compared against the equally normalised request path.
     *
     * @return the route this endpoint answers, always starting with {@code /}
     */
    static String healthPath() {
        return healthRoute(configProperties());
    }

    /**
     * Normalises a configured health path into the route that is matched.
     *
     * @param props file-backed configuration, may be {@code null} or empty
     * @return the route this endpoint answers, always starting with {@code /}
     */
    private static String healthRoute(Properties props) {
        String configured = resolve(props, KEY_HEALTH_PATH, ENV_HEALTH_PATH, DEFAULT_HEALTH_PATH);
        String path = configured.startsWith(ROOT_PATH) ? configured : ROOT_PATH + configured;
        return normalisePath(path);
    }

    /**
     * @return the effective bind address for the listener, the wildcard address
     *         by default so that a container probe or orchestrator can reach it
     */
    static String appHost() {
        return resolve(configProperties(), KEY_APP_HOST, ENV_APP_HOST, DEFAULT_APP_HOST);
    }

    /**
     * @return the effective listener port
     * @throws IllegalArgumentException if the configured value is not a legal port
     */
    static int javaPort() {
        return resolvePort(configProperties(), KEY_JAVA_PORT, ENV_JAVA_PORT,
                ENV_UNIVERSAL_PORT, DEFAULT_JAVA_PORT);
    }

    /**
     * One immutable snapshot of every setting a request can depend on.
     *
     * <p>Taken once, when a server is created, and read by every response that
     * server writes. Resolving configuration per request would make two responses
     * from one process disagree if the file changed underneath them, and would put
     * a filesystem read on the path of a health check whose whole purpose is to
     * answer quickly and predictably. index.js snapshots at the same moment, so
     * the two behave identically.
     *
     * @param name       value of the payload's {@code name} field
     * @param version    value of the payload's {@code version} field
     * @param healthPath normalised route this server answers
     * @param host       bind address
     * @param port       requested port, {@code 0} meaning "any free port"
     */
    record Config(String name, String version, String healthPath, String host, int port) { }

    /**
     * Resolves every setting once, reading the properties file exactly once.
     *
     * @return the effective configuration
     * @throws IllegalArgumentException if the configured port is not a legal port
     */
    static Config loadConfig() {
        Properties props = configProperties();
        return new Config(
                resolve(props, KEY_APP_NAME, ENV_APP_NAME, DEFAULT_APP_NAME),
                resolve(props, KEY_APP_VERSION, ENV_APP_VERSION, DEFAULT_APP_VERSION),
                healthRoute(props),
                resolve(props, KEY_APP_HOST, ENV_APP_HOST, DEFAULT_APP_HOST),
                resolvePort(props, KEY_JAVA_PORT, ENV_JAVA_PORT,
                        ENV_UNIVERSAL_PORT, DEFAULT_JAVA_PORT));
    }

    // -------------------------------------------------------------------------
    // Payload construction
    //
    // The JDK has no JSON serializer, so the document is assembled here. The
    // output is compact - no whitespace anywhere - and its four keys appear in
    // the frozen order name, version, timestamp, status, which is what makes it
    // byte-identical to the Python and JavaScript payloads.
    // -------------------------------------------------------------------------

    /**
     * Returns the current instant, truncated to whole seconds.
     *
     * <p>This is the only non-deterministic field in the payload, and it is the
     * first wall-clock dependence in this repository. It is emitted as a
     * fixed-width UTC instant with a trailing {@code Z}, matching
     * {@code ^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$}. Truncating to seconds is
     * what makes the width fixed and keeps all three implementations aligned;
     * every automated assertion on this field checks its format and never its
     * value, so no gate can become time-flaky.
     *
     * @return an instant such as {@code 2026-07-28T13:47:08Z}
     */
    static String timestamp() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * Escapes a string for inclusion in a JSON string literal.
     *
     * <p>Exactly the escapes required by RFC 8259 are applied: the quote, the
     * backslash, the five two-character control escapes, and any remaining
     * character below {@code 0x20} as a lower-case {@code \}{@code u00XX}
     * sequence. Nothing else is escaped - notably not the forward slash and not
     * non-ASCII characters - because over-escaping would break byte parity with
     * {@code json.dumps} and {@code JSON.stringify}, which do not escape them
     * either.
     *
     * @param raw the value to escape, {@code null} being treated as empty
     * @return the escaped value, safe to place between two quote characters
     */
    static String jsonEscape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(raw.length() + JSON_ESCAPE_HEADROOM);
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (current < ' ') {
                        escaped.append(String.format("\\u%04x", (int) current));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Renders the health document from explicit values.
     *
     * <p>Kept free of any ambient state so that it can be exercised directly,
     * and so that the key order - the part of the contract most easily broken
     * by a well-meaning edit - lives in exactly one place.
     *
     * @param name      value of the {@code name} field
     * @param version   value of the {@code version} field
     * @param timestamp value of the {@code timestamp} field
     * @param status    value of the {@code status} field
     * @return compact JSON with no whitespace and no trailing newline
     */
    static String renderPayload(String name, String version, String timestamp, String status) {
        return new StringBuilder(PAYLOAD_CAPACITY)
                .append("{\"name\":\"").append(jsonEscape(name))
                .append("\",\"version\":\"").append(jsonEscape(version))
                .append("\",\"timestamp\":\"").append(jsonEscape(timestamp))
                .append("\",\"status\":\"").append(jsonEscape(status))
                .append("\"}")
                .toString();
    }

    /**
     * Builds the health document from the effective configuration and clock.
     *
     * @return the body served for a successful health request
     */
    static String healthPayload() {
        Properties props = configProperties();
        return renderPayload(
                resolve(props, KEY_APP_NAME, ENV_APP_NAME, DEFAULT_APP_NAME),
                resolve(props, KEY_APP_VERSION, ENV_APP_VERSION, DEFAULT_APP_VERSION),
                timestamp(),
                STATUS_UP);
    }

    /**
     * Builds the health document from an already-resolved snapshot.
     *
     * <p>This is the form the server uses, so answering a request reads the clock
     * and nothing else.
     *
     * @param config the snapshot taken when the server was created
     * @return the body served for a successful health request
     */
    static String healthPayload(Config config) {
        return renderPayload(config.name(), config.version(), timestamp(), STATUS_UP);
    }

    // -------------------------------------------------------------------------
    // Request routing and response writing
    // -------------------------------------------------------------------------

    /**
     * Normalises a request target so that routing is forgiving but exact.
     *
     * <p>Four transformations are applied, in this order, and no others:
     * <ol>
     *   <li>An absolute-form target - {@code http://host/health}, which RFC 9112
     *       permits any client to send and a proxy always sends - is reduced to
     *       its path. Without this the same request would reach the endpoint in
     *       app.py and be a 404 here.</li>
     *   <li>Any query string is removed, so {@code /health?probe=1} matches.</li>
     *   <li>Any fragment is removed. A fragment has no business in a request
     *       target, but a careless client sends one and stripping it costs
     *       nothing.</li>
     *   <li>Exactly one trailing slash is dropped, so {@code /health/} matches
     *       while {@code /health//} does not.</li>
     * </ol>
     *
     * <p>What is deliberately <em>not</em> done matters just as much, because each
     * omission is a way two requests could otherwise reach the same route by
     * different spellings: percent-escapes are never decoded, so {@code /health%2f}
     * is a different path; dot segments are never resolved, so
     * {@code /health/../health} is a different path; and repeated leading slashes
     * are never collapsed, so {@code //health} and {@code ///health} are different
     * paths. All three are 404, and all three are 404 in app.py and index.js too.
     *
     * @param rawPath target as received, possibly {@code null}
     * @return a normalised path, {@code /} when nothing usable remains
     */
    static String normalisePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return ROOT_PATH;
        }
        String path = stripAuthority(rawPath);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        if (path.length() > 1 && path.endsWith(ROOT_PATH)) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? ROOT_PATH : path;
    }

    /**
     * Reduces an absolute-form request target to its path component.
     *
     * <p>The scheme is validated before anything is stripped, so a relative target
     * whose query string happens to contain {@code ://} - a redirect parameter, for
     * instance - is left completely alone.
     *
     * @param target the raw request target
     * @return the path component of an absolute-form target, or the target itself
     */
    private static String stripAuthority(String target) {
        int separator = target.indexOf(SCHEME_SEPARATOR);
        if (separator <= 0 || !isScheme(target.substring(0, separator))) {
            return target;
        }
        int authorityStart = separator + SCHEME_SEPARATOR.length();
        int pathStart = target.indexOf('/', authorityStart);
        return (pathStart < 0) ? ROOT_PATH : target.substring(pathStart);
    }

    /**
     * Reports whether a string is a URI scheme as RFC 3986 defines one.
     *
     * @param candidate the text before {@code ://} in a request target
     * @return {@code true} if it is ALPHA followed by ALPHA, DIGIT, +, - or .
     */
    private static boolean isScheme(String candidate) {
        if (candidate.isEmpty() || !isAsciiLetter(candidate.charAt(0))) {
            return false;
        }
        for (int index = 1; index < candidate.length(); index++) {
            char current = candidate.charAt(index);
            boolean allowed = isAsciiLetter(current) || isAsciiDigit(current)
                    || current == '+' || current == '-' || current == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** @return {@code true} for A-Z and a-z only, never for a non-ASCII letter */
    private static boolean isAsciiLetter(char current) {
        return (current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z');
    }

    /** @return {@code true} for 0-9 only, never for a non-ASCII digit */
    private static boolean isAsciiDigit(char current) {
        return current >= '0' && current <= '9';
    }

    /**
     * One line read from a connection, and how the read ended.
     *
     * @param text        the line with its CRLF or LF terminator removed
     * @param truncated   {@code true} if the byte cap was reached first
     * @param endOfStream {@code true} if the stream ended before any byte arrived
     */
    private record Line(String text, boolean truncated, boolean endOfStream) { }

    /**
     * One parsed request line, or the status that rejects it.
     *
     * @param method  the method token exactly as sent
     * @param target  the request target exactly as sent
     * @param major   HTTP major version, or {@code 0} when the request is rejected
     * @param minor   HTTP minor version, or {@code 0} when the request is rejected
     * @param failure the status to answer with, or {@code 0} when the line is valid
     */
    private record RequestLine(String method, String target, int major, int minor, int failure) {

        /** @return {@code true} when this line was rejected and must not be routed */
        boolean rejected() {
            return failure != 0;
        }

        /** @return {@code true} when the client may reuse the connection by default */
        boolean persistentByDefault() {
            return major > 1 || (major == 1 && minor >= 1);
        }
    }

    /**
     * Reads one line of a request, bounded, without decoding it.
     *
     * <p>Bytes are mapped to characters one for one through ISO-8859-1 rather than
     * decoded as UTF-8. A request line and a header field are ASCII by
     * specification, and any byte outside that range is rejected rather than
     * interpreted, so a lossless byte-preserving mapping is exactly what is wanted:
     * no replacement character can silently turn an illegal request into a legal
     * one.
     *
     * @param source the connection to read from
     * @param limit  the largest number of bytes this line may occupy
     * @return the line, flagged if it was truncated or the stream had ended
     * @throws IOException if the connection fails or falls idle
     */
    private static Line readLine(InputStream source, int limit) throws IOException {
        StringBuilder text = new StringBuilder(LINE_CAPACITY);
        int consumed = 0;
        while (true) {
            int next = source.read();
            if (next < 0) {
                return new Line(text.toString(), false, consumed == 0);
            }
            consumed++;
            if (next == '\n') {
                int length = text.length();
                if (length > 0 && text.charAt(length - 1) == '\r') {
                    text.setLength(length - 1);
                }
                return new Line(text.toString(), false, false);
            }
            if (consumed > limit) {
                return new Line(text.toString(), true, false);
            }
            text.append((char) next);
        }
    }

    /**
     * Parses and validates a request line against RFC 9112.
     *
     * <p>The line must be exactly {@value #REQUEST_LINE_TOKENS} single-space
     * separated tokens; the method must be a token as RFC 9110 defines one; the
     * target must be non-empty and made only of visible US-ASCII; and the version
     * must be {@code HTTP/}, digits, {@code .}, digits. Anything else is a 400. A
     * well-formed version whose major is not 1 is a 505 - the request was
     * understood, it simply cannot be served.
     *
     * @param line the request line with its terminator removed
     * @return the parsed line, or one carrying the status that rejects it
     */
    private static RequestLine parseRequestLine(String line) {
        String[] tokens = line.split(" ", -1);
        if (tokens.length != REQUEST_LINE_TOKENS) {
            return rejectedLine(HTTP_BAD_REQUEST);
        }
        String method = tokens[0];
        String target = tokens[1];
        String version = tokens[2];
        if (!isMethodToken(method) || !isRequestTarget(target)) {
            return rejectedLine(HTTP_BAD_REQUEST);
        }
        int separator = version.indexOf('/');
        if (!version.startsWith("HTTP/") || separator < 0) {
            return rejectedLine(HTTP_BAD_REQUEST);
        }
        String number = version.substring(separator + 1);
        int dot = number.indexOf('.');
        if (dot <= 0 || dot == number.length() - 1) {
            return rejectedLine(HTTP_BAD_REQUEST);
        }
        String majorText = number.substring(0, dot);
        String minorText = number.substring(dot + 1);
        if (!isDigits(majorText) || !isDigits(minorText)) {
            return rejectedLine(HTTP_BAD_REQUEST);
        }
        int major = parseBoundedNumber(majorText);
        int minor = parseBoundedNumber(minorText);
        if (major != 1) {
            return new RequestLine(method, target, major, minor, HTTP_VERSION_NOT_SUPPORTED);
        }
        return new RequestLine(method, target, major, minor, 0);
    }

    /** @return a rejected request line carrying {@code status} and nothing else */
    private static RequestLine rejectedLine(int status) {
        return new RequestLine("", "", 0, 0, status);
    }

    /**
     * Parses a run of digits without overflowing on an absurd one.
     *
     * @param digits a non-empty run of ASCII digits
     * @return the value, or {@link Integer#MAX_VALUE} if it does not fit
     */
    private static int parseBoundedNumber(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLarge) {
            return Integer.MAX_VALUE;
        }
    }

    /** @return {@code true} if every character is an ASCII digit and there is one */
    private static boolean isDigits(String candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            if (!isAsciiDigit(candidate.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a string is a method token as RFC 9110 defines one.
     *
     * <p>This is what makes an unknown or wrongly-cased verb a 405 rather than a
     * parse failure: {@code FOO}, {@code GETX}, {@code get} and {@code Get} are all
     * valid tokens, so all four are routed and all four are answered 405 with
     * {@code Allow: GET}, exactly as {@code TRACE} and {@code PROPFIND} are.
     *
     * @param candidate the first token of the request line
     * @return {@code true} if it is a legal, non-empty method token
     */
    private static boolean isMethodToken(String candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            char current = candidate.charAt(index);
            boolean allowed = isAsciiLetter(current) || isAsciiDigit(current)
                    || METHOD_TOKEN_SPECIALS.indexOf(current) >= 0;
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a request target is made only of characters allowed in one.
     *
     * <p>Every byte must be visible US-ASCII: {@value #TARGET_MIN_CHAR} through
     * {@value #TARGET_MAX_CHAR}. That excludes the space, every control character
     * including CR, LF and TAB, DEL, and every byte above the ASCII range. A tab
     * inside a target is therefore a 400 rather than something the router has to
     * reason about, and a CR or LF can never reach the router at all, which is what
     * makes response-header injection through the target structurally impossible.
     *
     * @param candidate the second token of the request line
     * @return {@code true} if the target is non-empty and entirely legal
     */
    private static boolean isRequestTarget(String candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            char current = candidate.charAt(index);
            if (current < TARGET_MIN_CHAR || current > TARGET_MAX_CHAR) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes one complete response: status line, header block and optional body.
     *
     * <p>Every byte is assembled here, which is the whole point of owning the
     * socket: the three contract headers appear in a fixed order with fixed casing
     * and nothing is added behind this method's back - no Date, no Server, no
     * transport header on a contract response. {@code Content-Length} is the
     * encoded byte length, never the character count, so a multi-byte character in
     * a configured value cannot desynchronise the length from the body.
     *
     * <p>The head is encoded as ISO-8859-1 and every value placed in it is either a
     * compile-time constant or a number, so no request-supplied text can reach it.
     *
     * @param sink        the connection to write to
     * @param status      HTTP status code
     * @param reason      reason phrase for the status line
     * @param body        complete response body, already compact JSON
     * @param sendAllow   whether to add {@code Allow: GET}
     * @param sendClose   whether to add {@code Connection: close}
     * @param includeBody whether the body bytes follow the header block
     * @throws IOException if the response cannot be written
     */
    private static void writeResponse(OutputStream sink, int status, String reason, String body,
            boolean sendAllow, boolean sendClose, boolean includeBody) throws IOException {
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(HEAD_CAPACITY)
                .append(HTTP_VERSION).append(' ').append(status).append(' ').append(reason)
                .append(CRLF)
                .append(HEADER_CONTENT_TYPE).append(CRLF)
                .append(HEADER_CACHE_CONTROL).append(CRLF)
                .append(HEADER_CONTENT_LENGTH_PREFIX).append(encoded.length).append(CRLF);
        if (sendAllow) {
            head.append(HEADER_ALLOW_GET).append(CRLF);
        }
        if (sendClose) {
            head.append(HEADER_CONNECTION_CLOSE).append(CRLF);
        }
        head.append(CRLF);
        sink.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (includeBody) {
            sink.write(encoded);
        }
        sink.flush();
    }

    /**
     * Writes one of the four transport-error responses and gives up the connection.
     *
     * <p>These are the only responses that carry {@code Connection: close}, because
     * they are the only ones after which this server stops reading: once a request
     * line could not be parsed, whatever follows it on the connection cannot be
     * framed either.
     *
     * @param sink   the connection to write to
     * @param status one of 400, 414, 431 or 505
     * @throws IOException if the response cannot be written
     */
    private static void writeTransportError(OutputStream sink, int status) throws IOException {
        String reason;
        String body;
        switch (status) {
            case HTTP_URI_TOO_LONG -> {
                reason = REASON_URI_TOO_LONG;
                body = BODY_URI_TOO_LONG;
            }
            case HTTP_HEADERS_TOO_LARGE -> {
                reason = REASON_HEADERS_TOO_LARGE;
                body = BODY_HEADERS_TOO_LARGE;
            }
            case HTTP_VERSION_NOT_SUPPORTED -> {
                reason = REASON_VERSION_NOT_SUPPORTED;
                body = BODY_VERSION_NOT_SUPPORTED;
            }
            case HTTP_INTERNAL_ERROR -> {
                reason = REASON_INTERNAL_ERROR;
                body = BODY_INTERNAL_ERROR;
            }
            default -> {
                reason = REASON_BAD_REQUEST;
                body = BODY_BAD_REQUEST;
            }
        }
        writeResponse(sink, status, reason, body, false, true, true);
    }

    /**
     * Finds one request header value, comparing the field name case-insensitively.
     *
     * @param headers the header block as received, one entry per field line
     * @param name    the lower-case field name to look for
     * @return the first matching value with surrounding whitespace removed, or
     *         {@code null} when the field is absent
     */
    private static String headerValue(List<String> headers, String name) {
        for (String field : headers) {
            int colon = field.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String fieldName = field.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (fieldName.equals(name)) {
                return field.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** @return {@code true} if a comma-separated header value contains a token */
    private static boolean containsToken(String value, String token) {
        if (value == null) {
            return false;
        }
        for (String part : value.split(",", -1)) {
            if (part.trim().equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the header block that follows a request line.
     *
     * @param source  the connection to read from
     * @param headers collects one entry per header field line, in order
     * @return {@code 0} when the block was read in full, or 431 when a cap was hit
     * @throws IOException if the connection fails or falls idle
     */
    private static int readHeaders(InputStream source, List<String> headers) throws IOException {
        int consumed = 0;
        while (true) {
            Line line = readLine(source, MAX_HEADER_LINE_BYTES);
            if (line.truncated()) {
                return HTTP_HEADERS_TOO_LARGE;
            }
            if (line.endOfStream()) {
                // The client stopped in the middle of its own header block; the
                // request is incomplete rather than oversized.
                return HTTP_BAD_REQUEST;
            }
            if (line.text().isEmpty()) {
                return 0;
            }
            consumed += line.text().length() + CRLF.length();
            if (consumed > MAX_HEADER_BLOCK_BYTES || headers.size() >= MAX_HEADER_FIELDS) {
                return HTTP_HEADERS_TOO_LARGE;
            }
            headers.add(line.text());
        }
    }

    /**
     * Consumes a request body so that the connection can be reused.
     *
     * <p>Nothing this endpoint answers depends on a body, but bytes left unread
     * desynchronise a persistent connection, and closing a socket that still has
     * unread data queued makes the kernel reply with a reset instead of letting the
     * client read the response that was already written. Draining is therefore part
     * of answering correctly, not an optimisation.
     *
     * @param source            the connection to read from
     * @param contentLength     the {@code Content-Length} value, or {@code null}
     * @param transferEncoding  the {@code Transfer-Encoding} value, or {@code null}
     * @return {@code true} if the body was consumed in full and the connection may
     *         be reused, {@code false} if the connection must now be closed
     * @throws IOException if the connection fails or falls idle
     */
    private static boolean drainRequestBody(InputStream source, String contentLength,
            String transferEncoding) throws IOException {
        if (transferEncoding != null && !transferEncoding.isEmpty()) {
            return containsToken(transferEncoding, TRANSFER_ENCODING_CHUNKED)
                    && drainChunkedBody(source);
        }
        if (contentLength == null || contentLength.isEmpty()) {
            return true;
        }
        long declared;
        try {
            declared = Long.parseLong(contentLength.trim());
        } catch (NumberFormatException unparseable) {
            return false;
        }
        if (declared < 0) {
            return false;
        }
        if (declared > MAX_REQUEST_DRAIN_BYTES) {
            return false;
        }
        return skipExactly(source, declared);
    }

    /**
     * Discards a chunked request body, including its trailer section.
     *
     * @param source the connection to read from
     * @return {@code true} if the whole body and trailer were consumed
     * @throws IOException if the connection fails or falls idle
     */
    private static boolean drainChunkedBody(InputStream source) throws IOException {
        long total = 0;
        while (true) {
            Line sizeLine = readLine(source, MAX_HEADER_LINE_BYTES);
            if (sizeLine.truncated() || sizeLine.endOfStream()) {
                return false;
            }
            String size = sizeLine.text();
            int extension = size.indexOf(';');
            if (extension >= 0) {
                size = size.substring(0, extension);
            }
            size = size.trim();
            long chunk;
            try {
                chunk = Long.parseLong(size, CHUNK_SIZE_RADIX);
            } catch (NumberFormatException unparseable) {
                return false;
            }
            if (chunk < 0) {
                return false;
            }
            if (chunk == 0) {
                return skipTrailer(source);
            }
            total += chunk;
            if (total > MAX_CHUNKED_BODY_BYTES || !skipExactly(source, chunk)) {
                return false;
            }
            Line terminator = readLine(source, MAX_HEADER_LINE_BYTES);
            if (!terminator.text().isEmpty() || terminator.endOfStream()) {
                return false;
            }
        }
    }

    /**
     * Discards the trailer section that closes a chunked body.
     *
     * @param source the connection to read from
     * @return {@code true} if the trailer ended with its blank line
     * @throws IOException if the connection fails or falls idle
     */
    private static boolean skipTrailer(InputStream source) throws IOException {
        for (int field = 0; field <= MAX_TRAILER_FIELDS; field++) {
            Line line = readLine(source, MAX_HEADER_LINE_BYTES);
            if (line.truncated() || line.endOfStream()) {
                return false;
            }
            if (line.text().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads and discards an exact number of bytes.
     *
     * @param source the connection to read from
     * @param count  how many bytes to discard
     * @return {@code true} if every byte arrived
     * @throws IOException if the connection fails or falls idle
     */
    private static boolean skipExactly(InputStream source, long count) throws IOException {
        byte[] scratch = new byte[IO_BUFFER_BYTES];
        long remaining = count;
        while (remaining > 0) {
            int wanted = (int) Math.min(remaining, scratch.length);
            int read = source.read(scratch, 0, wanted);
            if (read < 0) {
                return false;
            }
            remaining -= read;
        }
        return true;
    }

    /**
     * Serves one request on an established connection.
     *
     * <p>Grades the request through the frozen order documented on this class and
     * writes exactly one response. Nothing about the request - not the target, not
     * the method, not an exception message - is ever placed in a response.
     *
     * @param source the connection to read the request from
     * @param sink   the connection to write the response to
     * @param config the snapshot this server was created with
     * @return {@code true} if the connection may be reused for another request
     * @throws IOException if the connection fails or falls idle
     */
    private static boolean serveExchange(InputStream source, OutputStream sink, Config config)
            throws IOException {
        Line line = readLine(source, MAX_REQUEST_LINE_BYTES);
        if (line.endOfStream()) {
            return false;
        }
        if (!line.truncated() && line.text().isEmpty()) {
            // RFC 9112 asks a server to tolerate one empty line before a request
            // line, because a client that terminated its previous request with an
            // extra CRLF is common and harmless. Exactly one is skipped.
            line = readLine(source, MAX_REQUEST_LINE_BYTES);
            if (line.endOfStream()) {
                return false;
            }
        }
        if (line.truncated()) {
            writeTransportError(sink, HTTP_URI_TOO_LONG);
            return false;
        }
        RequestLine request = parseRequestLine(line.text());
        if (request.rejected()) {
            writeTransportError(sink, request.failure());
            return false;
        }
        List<String> headers = new ArrayList<>();
        int headerFailure = readHeaders(source, headers);
        if (headerFailure != 0) {
            writeTransportError(sink, headerFailure);
            return false;
        }
        if (request.persistentByDefault() && headerValue(headers, HEADER_NAME_HOST) == null) {
            // RFC 9112 requires exactly this: an HTTP/1.1 request without a Host
            // header is malformed, and a server must reject it rather than guess.
            writeTransportError(sink, HTTP_BAD_REQUEST);
            return false;
        }
        String expect = headerValue(headers, HEADER_NAME_EXPECT);
        if (containsToken(expect, EXPECT_CONTINUE)) {
            sink.write(CONTINUE_RESPONSE.getBytes(StandardCharsets.ISO_8859_1));
            sink.flush();
        }
        boolean bodyConsumed = drainRequestBody(source,
                headerValue(headers, HEADER_NAME_CONTENT_LENGTH),
                headerValue(headers, HEADER_NAME_TRANSFER_ENCODING));
        String connection = headerValue(headers, HEADER_NAME_CONNECTION);
        boolean clientWantsClose = containsToken(connection, CONNECTION_CLOSE)
                || (!request.persistentByDefault() && !containsToken(connection, CONNECTION_KEEP_ALIVE));
        boolean reusable = bodyConsumed && !clientWantsClose;
        // A contract response carries no Connection header in any of the three
        // implementations, so the header set stays at exactly three fields whatever
        // the client asked for; the connection is simply closed afterwards when it
        // has to be.
        boolean sendBody = !METHOD_HEAD.equals(request.method());
        if (!METHOD_GET.equals(request.method())) {
            writeResponse(sink, HTTP_METHOD_NOT_ALLOWED, REASON_METHOD_NOT_ALLOWED,
                    BODY_METHOD_NOT_ALLOWED, true, false, sendBody);
            return reusable;
        }
        if (config.healthPath().equals(normalisePath(request.target()))) {
            writeResponse(sink, HTTP_OK, REASON_OK, healthPayload(config), false, false, true);
        } else {
            writeResponse(sink, HTTP_NOT_FOUND, REASON_NOT_FOUND, BODY_NOT_FOUND, false, false, true);
        }
        return reusable;
    }

    /**
     * Serves one accepted connection, on its own virtual thread, until it ends.
     *
     * <p>Requests are answered one after another for as long as the client keeps the
     * connection open and useful, which is what makes a keep-alive client see the
     * same behaviour here as it does from the other two implementations. The socket
     * is always closed, and it is closed politely: the write side is shut down first
     * and anything the client is still sending is drained briefly, because closing
     * on top of unread data is what makes a kernel answer with a reset and a client
     * report "no response" for a request that was in fact answered.
     *
     * @param accepted the connection to serve
     * @param config   the snapshot this server was created with
     */
    private static void serveConnection(Socket accepted, Config config) {
        try (Socket connection = accepted) {
            connection.setSoTimeout(IDLE_TIMEOUT_MILLIS);
            connection.setTcpNoDelay(true);
            InputStream source =
                    new BufferedInputStream(connection.getInputStream(), IO_BUFFER_BYTES);
            OutputStream sink =
                    new BufferedOutputStream(connection.getOutputStream(), IO_BUFFER_BYTES);
            boolean reusable = true;
            while (reusable) {
                reusable = serveExchange(source, sink, config);
            }
            politeClose(connection, source);
        } catch (SocketTimeoutException idle) {
            // A client that stopped mid-request; its connection is simply reclaimed.
        } catch (IOException disconnected) {
            // A client that vanished. Not this server's problem and not worth a line
            // of output: an unreachable peer is normal traffic on a public port.
        } catch (RuntimeException unexpected) {
            System.err.println(LOG_PREFIX + "connection failed unexpectedly: " + unexpected);
        }
    }

    /**
     * Shuts down the write side and drains briefly before the socket is closed.
     *
     * @param connection the connection being retired
     * @param source     the connection's buffered input
     */
    private static void politeClose(Socket connection, InputStream source) {
        try {
            connection.shutdownOutput();
            connection.setSoTimeout(LINGER_TIMEOUT_MILLIS);
            byte[] scratch = new byte[IO_BUFFER_BYTES];
            long discarded = 0;
            while (discarded < MAX_LINGER_DRAIN_BYTES) {
                int read = source.read(scratch);
                if (read < 0) {
                    return;
                }
                discarded += read;
            }
        } catch (IOException alreadyGone) {
            // Nothing left to do; the caller closes the socket either way.
        }
    }

    // -------------------------------------------------------------------------
    // Server lifecycle
    // -------------------------------------------------------------------------

    /**
     * A listening health endpoint: one socket, one acceptor, one thread per client.
     *
     * <p>The acceptor is a platform thread and deliberately <em>not</em> a daemon,
     * because it is what keeps the process alive in --serve mode. Each accepted
     * connection is handed to its own virtual thread, so the cost of a client that
     * connects and then stalls is one parked virtual thread - a few hundred bytes -
     * and no other client waits behind it. Virtual threads are daemon threads, so
     * they never delay shutdown, and {@link #stop()} closes the socket, which is
     * what releases the acceptor and lets a JVM that started a server exit on its
     * own.
     */
    static final class HealthServer {
        private final ServerSocket listener;
        private final Config config;
        private final ExecutorService workers;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile Thread acceptor;

        private HealthServer(ServerSocket listener, Config config) {
            this.listener = listener;
            this.config = config;
            this.workers = Executors.newVirtualThreadPerTaskExecutor();
        }

        /** @return the port actually bound, which is resolved even when 0 was asked for */
        int port() {
            return listener.getLocalPort();
        }

        /** @return the snapshot every response from this server is built from */
        Config config() {
            return config;
        }

        /** Starts accepting connections. Returns as soon as the acceptor is running. */
        void start() {
            Thread thread = new Thread(this::acceptLoop, ACCEPTOR_THREAD_NAME);
            thread.setDaemon(false);
            acceptor = thread;
            thread.start();
        }

        /**
         * Stops accepting, interrupts connections in flight and closes the socket.
         *
         * <p>Idempotent, because it is reachable both from a shutdown hook and from
         * a caller that stops the server itself.
         */
        void stop() {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            try {
                listener.close();
            } catch (IOException alreadyClosed) {
                // The socket is going away regardless; nothing to report.
            }
            workers.shutdownNow();
            Thread thread = acceptor;
            if (thread != null) {
                thread.interrupt();
            }
        }

        private void acceptLoop() {
            while (running.get()) {
                Socket accepted;
                try {
                    accepted = listener.accept();
                } catch (IOException closedOrRefused) {
                    if (running.get()) {
                        System.err.println(LOG_PREFIX + "accept failed: " + closedOrRefused);
                        continue;
                    }
                    return;
                }
                try {
                    workers.execute(() -> serveConnection(accepted, config));
                } catch (RejectedExecutionException stopping) {
                    closeQuietly(accepted);
                    return;
                }
            }
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException alreadyClosed) {
                // Nothing to do: the connection is being abandoned deliberately.
            }
        }
    }

    /**
     * Creates and binds a health server without starting it.
     *
     * <p>Passing {@code 0} as the port binds an ephemeral one, and the chosen port
     * is readable from {@code server.port()} as soon as this method returns, which
     * is what lets a test run against a port that cannot collide with anything else
     * on the host.
     *
     * <p>The returned server is <strong>not</strong> listening yet: call
     * {@code start()} on it, or use {@link #startServer(String, int)} which does
     * both.
     *
     * @param host bind address; the wildcard address accepts every interface
     * @param port port to bind, or {@code 0} for an ephemeral port
     * @return a bound but not yet started server
     * @throws IOException if the address cannot be bound
     * @throws IllegalArgumentException if the configured port is not a legal port
     */
    static HealthServer createServer(String host, int port) throws IOException {
        Config resolved = loadConfig();
        return createServer(new Config(resolved.name(), resolved.version(),
                resolved.healthPath(), host, port));
    }

    /**
     * Creates and binds a health server from an explicit snapshot.
     *
     * <p>The snapshot is taken once, here, and every response this server writes is
     * built from it, so no request ever touches the filesystem and two responses from
     * one server can never disagree about the configuration they were serving.
     *
     * @param config the configuration to bind and serve with
     * @return a bound but not yet started server
     * @throws IOException if the address cannot be bound
     */
    static HealthServer createServer(Config config) throws IOException {
        ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(config.host(), config.port()), SERVER_BACKLOG);
        } catch (IOException | RuntimeException bindFailure) {
            try {
                listener.close();
            } catch (IOException alsoFailed) {
                bindFailure.addSuppressed(alsoFailed);
            }
            throw bindFailure;
        }
        return new HealthServer(listener, config);
    }

    /**
     * Creates, binds and starts a health server.
     *
     * @param host bind address; the wildcard address accepts every interface
     * @param port port to bind, or {@code 0} for an ephemeral port
     * @return a server that is already listening
     * @throws IOException if the address cannot be bound
     * @throws IllegalArgumentException if the configured port is not a legal port
     */
    static HealthServer startServer(String host, int port) throws IOException {
        HealthServer server = createServer(host, port);
        server.start();
        return server;
    }

    /**
     * Serves the health endpoint from the effective configuration.
     *
     * <p>The startup line is written to stderr rather than stdout, and that is
     * not a stylistic choice: the default mode's stdout is asserted byte for
     * byte by the backward-compatibility gate, so this program keeps stdout
     * reserved for that single preserved line. Control characters are stripped from
     * it, so a configured value can never move the cursor, clear the screen or
     * forge a second line in an operator's log.
     *
     * <p>A shutdown hook stops the listener so that a container stop or a Ctrl-C
     * closes the socket in an orderly way instead of dropping connections in
     * flight. Both failure modes are fatal and fail closed with a non-zero exit
     * status and a one-line diagnostic rather than a stack trace: an address that
     * cannot be bound, and a configured port that is not a port at all.
     *
     * <p>Termination convention. The hook runs to completion, but the JVM still
     * reports the signal that ended it, so this process exits {@code 130} on
     * SIGINT and {@code 143} on SIGTERM. The other two implementations report
     * their own runtime's convention for the same signals - {@code index.js}
     * exits {@code 0} for both, and {@code app.py} exits {@code 0} on SIGINT and
     * is terminated by SIGTERM - so the exit STATUS is the one place these three
     * servers deliberately differ. Everything an orchestrator depends on is
     * identical: the listener is closed, the port is released and stdout stays
     * empty. Overriding a platform convention to align the numbers would buy
     * nothing, so the difference is documented instead.
     */
    private static void serve() {
        Config config;
        try {
            config = loadConfig();
        } catch (IllegalArgumentException unusable) {
            System.err.println(LOG_PREFIX + "refusing to start: " + unusable.getMessage());
            System.exit(EXIT_FAILURE);
            return;
        }
        try {
            HealthServer server = createServer(config);
            Runtime.getRuntime().addShutdownHook(
                    new Thread(server::stop, SHUTDOWN_THREAD_NAME));
            server.start();
            System.err.println(LOG_PREFIX + "health endpoint listening on http://"
                    + sanitiseForLog(config.host()) + ":" + server.port()
                    + sanitiseForLog(config.healthPath()));
        } catch (IOException bindFailure) {
            System.err.println(LOG_PREFIX + "could not bind " + sanitiseForLog(config.host())
                    + ":" + config.port() + ": " + bindFailure);
            System.exit(EXIT_FAILURE);
        }
    }

    /**
     * Removes control characters from a value before it is written to a log.
     *
     * <p>Configuration is an input, and every value in the startup line comes from
     * one. A carriage return or a line feed in a configured path would otherwise
     * forge a second log line, and an escape character would let a configured value
     * drive the operator's terminal. All three implementations neutralise exactly
     * the same character set on exactly the same line - every code point below
     * {@code 0x20} plus DEL - so the security property is identical everywhere;
     * only the presentation differs, because app.py removes those characters as
     * this method does while index.js replaces each of them with {@code "?"}.
     * The banner text itself is deliberately per-language: each program names
     * itself so an operator can tell three concurrent servers apart.
     *
     * @param value the configured value to print
     * @return the value with every character below {@code 0x20}, and DEL, removed
     */
    private static String sanitiseForLog(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current >= ' ' && current != 0x7F) {
                safe.append(current);
            }
        }
        return safe.toString();
    }

    // -------------------------------------------------------------------------
    // Self-check consumed by the container health check
    // -------------------------------------------------------------------------

    /**
     * Self-checks the endpoint described by the effective configuration.
     *
     * @return {@value #EXIT_SUCCESS} when healthy, {@value #EXIT_FAILURE} otherwise
     */
    static int probe() {
        Config config;
        try {
            config = loadConfig();
        } catch (IllegalArgumentException unusable) {
            System.err.println(LOG_PREFIX + "probe cannot run: " + unusable.getMessage());
            return EXIT_FAILURE;
        }
        return probe(config.host(), config.port(), config.healthPath());
    }

    /**
     * Performs one in-process GET of a health endpoint and grades the result.
     *
     * <p>Healthy means both a 200 status and a body that actually reports the
     * status as {@code UP}; a 200 carrying anything else is treated as
     * unhealthy. The check is a substring test rather than a parse, which is
     * deliberate: it needs no JSON parser, and the JDK has none to offer.
     *
     * <p>Everything else - an unreachable port, a timeout, a malformed
     * configured path, an interrupt - is graded unhealthy. The method fails
     * closed, never throws, and never writes to stdout, so it is safe to use as
     * a container health command whose only channel is an exit status.
     *
     * @param host       host the server was bound to; a wildcard is probed over loopback
     * @param port       port the server is listening on
     * @param healthPath path to request
     * @return {@value #EXIT_SUCCESS} when healthy, {@value #EXIT_FAILURE} otherwise
     */
    static int probe(String host, int port, String healthPath) {
        String target = "";
        try {
            target = "http://" + probeAuthority(host) + ":" + port + healthPath;
            HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            try (HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                    .build()) {
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == HTTP_OK && response.body().contains(STATUS_UP_FRAGMENT)) {
                    return EXIT_SUCCESS;
                }
                System.err.println(LOG_PREFIX + "probe rejected " + target
                        + ": status " + response.statusCode());
                return EXIT_FAILURE;
            }
        } catch (InterruptedException interrupted) {
            // Restore the flag so an enclosing caller can still observe it.
            Thread.currentThread().interrupt();
            System.err.println(LOG_PREFIX + "probe interrupted");
            return EXIT_FAILURE;
        } catch (IOException | RuntimeException unreachable) {
            System.err.println(LOG_PREFIX + "probe could not reach " + target + ": " + unreachable);
            return EXIT_FAILURE;
        }
    }

    /**
     * Converts a bind address into an address a client can actually connect to.
     *
     * <p>A wildcard bind address is not a destination, so the probe targets
     * loopback instead - which is also the only interface a container health
     * check needs. An IPv6 literal is wrapped in brackets so that it can carry a
     * port in a URL without the colons being misread.
     *
     * @param host the configured bind address
     * @return an authority suitable for placing in an HTTP URL
     */
    private static String probeAuthority(String host) {
        if (host == null || host.isEmpty()
                || WILDCARD_HOST_V4.equals(host) || WILDCARD_HOST_V6.equals(host)) {
            return LOOPBACK_HOST;
        }
        return (host.indexOf(':') >= 0) ? "[" + host + "]" : host;
    }
}
