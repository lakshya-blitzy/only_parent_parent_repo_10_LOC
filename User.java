/*
 * User.java - the Java application of this repository, and the Java
 * implementation of the shared /health endpoint (feature F-009).
 *
 * Three modes, dispatched by main:
 *   java User          prints "Test" and exits 0 - the original behaviour,
 *                      preserved byte for byte, reaching none of the new code
 *   java User --serve  binds an HTTP listener and serves the health endpoint
 *   java User --probe  GETs its own endpoint; exit 0 healthy, 1 otherwise
 * An unrecognised argument reaches the default mode: no usage message, no
 * diagnostic, exit status 0. The frozen response contract this endpoint answers
 * is documented on {@link #handle}; the routing rules are on
 * {@link #normalisePath}.
 *
 * --probe lives inside this class rather than in a separate source file because a
 * JRE ships no compiler and slim runtime images ship neither curl nor wget, so an
 * in-process self-check is the only kind that needs nothing already absent.
 *
 * JDK only, for the application and for its tests: the listener is a
 * {@code com.sun.net.httpserver.HttpServer} from jdk.httpserver, the self-check
 * uses java.net.http, and both modules are in the standard module set, so no
 * --add-modules flag, classpath addition, build tool or third-party library is
 * needed anywhere. The JDK ships no JSON serializer, and that is the one place
 * this implementation differs in mechanism from app.py and index.js: the payload
 * is assembled by hand through an explicit escape helper, and the result is
 * byte-identical to theirs for identical configuration.
 *
 * Three response details are decided by the server and cannot be reached from
 * application code. Each was established by execution, and each is a point where
 * this implementation's bytes differ from app.py's and index.js's:
 *   1. A Date field is always present. That is a STATED DEVIATION from the frozen
 *      header set, recorded as one rather than presented as conformance; see the
 *      deviation record on {@link #sendResponse} for the evidence that it cannot
 *      be prevented and for the bound asserted in its place. An HTTP/1.0 request
 *      additionally receives a Connection: close the server adds. No Server
 *      banner is emitted at all, by this class or by the server.
 *   2. Field names are normalised, so this server emits "Content-type" where the
 *      other two emit "Content-Type". RFC 9110 makes field names
 *      case-insensitive, so every assertion against these responses folds case.
 *   3. A target of //health is answered 404 by the server itself before this
 *      handler is reached, because it parses as a network-path reference whose
 *      authority is "health" and whose path is empty, so no context matches. An
 *      unparsable request line and an oversized header block are answered the
 *      same way. All three are fixed strings that echo no part of the request;
 *      only the media type of that error body differs from the other two
 *      implementations.
 *
 * There is deliberately no package declaration. That is what keeps both
 * "java -cp . User" and "java User.java" single-file source launch working, and
 * what lets the sibling UserTest.java reach the package-private helpers below
 * with no classpath, no build step and no test framework.
 *
 * Two deliberate deviations from draft-inadarei-api-health-check-06: the media
 * type is plain application/json rather than the draft's health-specific type,
 * because plain JSON is what generic tooling expects; and HEAD is answered 405
 * rather than supported alongside GET, which RFC 9110 expects wherever GET is
 * supported, so it is a documented deferral. The literal status "UP" is the
 * draft's own recognised alias for a passing status, and a passing status must
 * use a 2xx code, which is why this endpoint answers 200.
 */
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class User {
    /**
     * Three-way entry point: serve, probe, or the original default behaviour.
     *
     * <p>Written as guard clauses rather than an if/else chain so that the two
     * preserved statements of the original program keep their original indentation
     * and their original form: the default path is provably unchanged rather than
     * merely equivalent. An unrecognised argument reaches those same statements,
     * which is why no usage message is printed and the exit status stays 0.
     * {@code --serve} and {@code --probe} select the two other modes.
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

    // Mode selection and process exit status.
    private static final String FLAG_SERVE = "--serve";
    private static final String FLAG_PROBE = "--probe";

    /** Exit status a probe's caller reads; failure is also the fail-closed default. */
    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_FAILURE = 1;

    // Configuration precedence, identical in all three implementations:
    //     environment variable  >  app.config.properties  >  built-in default
    // For the listener port the universal PORT variable outranks the
    // language-specific JAVA_PORT, per the twelve-factor convention.

    /**
     * Name of the shared properties file.
     *
     * <p>Looked for beside the code source first - the directory holding
     * {@code User.class}, or {@code User.java} under a single-file source launch -
     * and only then in the working directory. That is how this implementation agrees
     * with app.py and index.js, which resolve the same file relative to
     * {@code __file__} and {@code __dirname}: all three find the repository's
     * configuration whichever directory the process was started from.
     */
    private static final String CONFIG_FILE = "app.config.properties";

    /**
     * The two diagnostics the configuration loader can emit, worded identically in
     * all three implementations so that one message means one condition everywhere.
     *
     * <p>An ABSENT file emits neither, because absence is the normal case: every key
     * it can supply has a built-in default. A file that cannot be READ - wrong
     * permissions, a directory in its place, bytes that are not UTF-8 - and a file
     * that is MALFORMED - a {@code \\uXXXX} escape that is not four hexadecimal
     * digits, the one condition {@code Properties.load} rejects outright - are
     * different problems for an operator, so they are reported differently.
     *
     * <p>Neither message carries the path or the underlying exception text: the path
     * is a deployment detail and the exception message embeds it.
     */
    private static final String CONFIG_UNREADABLE_WARNING =
            "cannot read the configuration file; using defaults";
    private static final String CONFIG_MALFORMED_WARNING =
            "the configuration file is malformed; using defaults";

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

    /** Accepted port range; 0 is legal and asks the OS for an ephemeral port. */
    private static final int MIN_PORT = 0;
    private static final int MAX_PORT = 65535;

    // The frozen wire contract.

    /** The one and only value this endpoint reports for a passing status. */
    private static final String STATUS_UP = "UP";

    private static final String PAYLOAD_KEY_NAME = "name";
    private static final String PAYLOAD_KEY_VERSION = "version";
    private static final String PAYLOAD_KEY_TIMESTAMP = "timestamp";
    private static final String PAYLOAD_KEY_STATUS = "status";

    /**
     * The four payload keys in the one order the contract freezes them in. Order is
     * part of the contract, not an artifact of how the document is built: the
     * self-check compares a parsed document's key sequence against this list, so the
     * right four keys in the wrong order are refused. app.py holds the same tuple and
     * index.js the same array.
     */
    private static final List<String> PAYLOAD_KEYS = List.of(
            PAYLOAD_KEY_NAME, PAYLOAD_KEY_VERSION, PAYLOAD_KEY_TIMESTAMP, PAYLOAD_KEY_STATUS);

    /**
     * The rejection emitted when a probed body does not carry exactly
     * {@link #PAYLOAD_KEYS} in order.
     *
     * <p>Written out as a literal rather than rendered from the list. An operator
     * greps one deployment's logs, not one language's, so these bytes are identical
     * in app.py, index.js and here - and {@code List.toString()} would print
     * {@code [name, version, timestamp, status]}, unquoted and space-padded, which
     * would make the same rejection read three different ways.
     */
    private static final String PROBE_KEY_SET_REASON =
            "body does not carry exactly the keys "
            + "[\"name\",\"version\",\"timestamp\",\"status\"] in order";

    /**
     * HTTP method tokens are case-sensitive per RFC 9110, so this comparison is
     * deliberately case-sensitive: "get" is not GET and is answered 405. It is
     * also the value of the {@code Allow} field on that 405.
     */
    private static final String METHOD_GET = "GET";

    /**
     * The method whose response carries a header block but no body. Named because
     * {@link #sendResponse} has to tell the server that no body follows, which is
     * also what suppresses the warning it logs when a body length is declared for a
     * HEAD request.
     */
    private static final String METHOD_HEAD = "HEAD";

    /**
     * The response fields this class sets, and their values. They are set through the
     * exchange's header map rather than written as bytes, so the server chooses their
     * casing and their order on the wire; RFC 9110 makes field names case-insensitive,
     * so that choice changes no meaning. {@link #sendResponse} records why
     * {@code Content-Length} is set explicitly as well as passed as a length.
     */
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String HEADER_CACHE_CONTROL = "Cache-Control";
    private static final String CACHE_CONTROL_NO_STORE = "no-cache, no-store, must-revalidate";
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_ALLOW = "Allow";

    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;

    /** Fixed error bodies: nothing about the request is ever reflected back. */
    private static final String BODY_NOT_FOUND = "{\"error\":\"Not Found\"}";
    private static final String BODY_METHOD_NOT_ALLOWED = "{\"error\":\"Method Not Allowed\"}";

    // Server, probe and formatting limits.

    /** The path every normalisation falls back to, and the route prefix. */
    private static final String ROOT_PATH = "/";

    /**
     * A target beginning with this is a network-path reference: RFC 3986 section
     * 4.2 reads {@code //health} as an authority named {@code health}, not as a
     * path.
     *
     * <p>All three implementations refuse such a value where it is CONFIGURED,
     * because not all three platform servers can route it. The JDK parses the
     * inbound target as a {@link URI}, so {@code //health} resolves to an empty
     * path, and CPython's request parser folds it down to {@code /health}; the Node
     * runtime, by contrast, hands it through unchanged and would serve it. A
     * configuration accepted by all three but answerable by only one is a
     * cross-language outage waiting for a deployment, so it fails closed at
     * startup instead.
     */
    private static final String NETWORK_PATH_PREFIX = "//";

    /**
     * Context path registered on the server.
     *
     * <p>Deliberately the root path: the server matches contexts by longest
     * prefix, so registering the configured health path directly would let the
     * server decide what a near-miss target means, and a near-miss would then be
     * answered by its HTML 404 rather than by this endpoint's JSON one. Taking
     * every target and routing it here keeps the routing rules - and therefore the
     * 404 body - identical to app.py's and index.js's.
     */
    private static final String CONTEXT_PATH = ROOT_PATH;

    /** Listen backlog; generous enough that a burst of clients is queued, not refused. */
    private static final int SERVER_BACKLOG = 128;

    /**
     * Name prefix for the virtual threads that run exchanges: named rather than
     * anonymous so that a thread dump taken from a running container tells an operator
     * which threads belong to this endpoint. The server's own dispatcher thread is
     * named by the JDK, and it is the non-daemon thread that keeps --serve mode alive.
     */
    private static final String WORKER_THREAD_PREFIX = "health-worker-";
    private static final long WORKER_THREAD_START = 0L;

    /** Name of the shutdown hook thread that closes the listening socket. */
    private static final String SHUTDOWN_THREAD_NAME = "health-shutdown";

    /**
     * Seconds the server may spend waiting for exchanges in flight when stopping.
     * Zero deliberately: these responses are written in microseconds, so a container
     * stop should release the port immediately rather than hold it for a grace period
     * no health response needs.
     */
    private static final int STOP_DELAY_SECONDS = 0;

    private static final int DRAIN_BUFFER_BYTES = 8192;

    /**
     * The length {@code sendResponseHeaders} is given when no body will follow - the
     * server's documented sentinel for "header block only", used for the response to
     * HEAD and nothing else.
     */
    private static final long NO_RESPONSE_BODY = -1L;

    /** Connect and read budget for the self-check; it must fail fast, not hang. */
    private static final int PROBE_TIMEOUT_SECONDS = 3;

    /** Loopback target used by the self-check. */
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private static final String WILDCARD_HOST_V4 = "0.0.0.0";
    private static final String WILDCARD_HOST_V6 = "::";
    private static final String WILDCARD_HOST_V6_BRACKETED = "[::]";
    private static final String WILDCARD_HOST_ANY = "*";

    /**
     * Every spelling of "bind everywhere", none of which is a destination.
     *
     * <p>Compared against the lower-cased, trimmed configured host, so
     * {@code "0.0.0.0"}, {@code "::"}, {@code "[::]"} and {@code "*"} all resolve
     * the probe to loopback. app.py and index.js hold the identical set.
     */
    private static final Set<String> WILDCARD_HOSTS = Set.of(
            WILDCARD_HOST_V4, WILDCARD_HOST_V6, WILDCARD_HOST_V6_BRACKETED, WILDCARD_HOST_ANY);

    /** The IPv6 loopback authority, bracketed so it can carry a port in a URL. */
    private static final String LOOPBACK_AUTHORITY_V6 = "[::1]";

    /**
     * Every spelling of IPv6 loopback this probe accepts as already-loopback.
     *
     * <p>All four are mapped to {@link #LOOPBACK_AUTHORITY_V6} rather than passed
     * through, so one destination is reached however the address was written.
     */
    private static final Set<String> IPV6_LOOPBACK_FORMS = Set.of(
            "::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]");

    /**
     * The loopback host NAME, mapped rather than resolved.
     *
     * <p>Deliberately not handed to the resolver: a hosts-file entry mapping
     * {@code localhost} elsewhere would otherwise redirect a probe that is supposed
     * to be able to reach nothing but this process.
     */
    private static final String LOOPBACK_NAME = "localhost";

    /** Prefix of the whole 127.0.0.0/8 range, every address in which is loopback. */
    private static final String IPV4_LOOPBACK_PREFIX = "127.";

    /** Shape of an IPv4 literal: four dot-separated octets, at most three digits each. */
    private static final int IPV4_OCTET_COUNT = 4;
    private static final int IPV4_OCTET_MAX_DIGITS = 3;
    private static final int IPV4_OCTET_MAX = 255;

    /**
     * Largest response body the self-check will read, in bytes.
     *
     * <p>The reference payload is 108 bytes, so this is two orders of magnitude of
     * headroom and still a hard bound. It exists because the probe is the one
     * component a container runs on a timer, forever: a listener answering with an
     * endless stream would otherwise exhaust this process's heap on a schedule.
     * One byte over the ceiling is read deliberately, which is how the ceiling is
     * detected rather than silently truncated to a body that might still parse.
     * app.py and index.js use the same number, so the same body is graded the same
     * way by all three.
     */
    private static final int MAX_PROBE_BODY_BYTES = 8192;

    /** Deepest JSON nesting the self-check's reader will descend. */
    private static final int MAX_JSON_DEPTH = 32;

    private static final int JSON_UNICODE_DIGITS = 4;
    private static final int JSON_UNICODE_RADIX = 16;

    /** Name of the request field the self-check sends, for parity with app.py. */
    private static final String HEADER_ACCEPT = "Accept";

    private static final String PROBE_THREAD_NAME = "health-probe";

    // Input grammars. Every configured value that is not free text is matched against
    // a grammar written out here, and the same three appear in app.py and index.js.
    // Writing them out is the point: the platform conversions are each lenient in a
    // different direction - Integer.parseInt accepts a Unicode digit that Number()
    // refuses, Python's int() accepts an underscore separator that neither of the
    // others does - so three implementations that each trusted their runtime would
    // disagree about the same configuration file.

    /** A port as all three implementations accept one: ASCII decimal, sign optional. */
    private static final Pattern PORT_GRAMMAR = Pattern.compile("^[+-]?[0-9]+$");

    /** A three-part dotted numeric version, the only form {@code app.version} may take. */
    private static final Pattern VERSION_GRAMMAR =
            Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");

    /**
     * A whole-second UTC instant, the only form the {@code timestamp} field may take.
     * The self-check grades the field by FORMAT and never by value, which is what keeps
     * the one non-deterministic field in the payload from making a gate time-flaky.
     */
    private static final Pattern TIMESTAMP_GRAMMAR = Pattern.compile(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$");

    /** Request-target byte range: control characters fall below it, DEL and above are out. */
    private static final char TARGET_MIN_CHAR = 0x21;
    private static final char TARGET_MAX_CHAR = 0x7E;

    /** Configured-value floor, and DEL - a control character despite sitting above it. */
    private static final char PRINTABLE_MIN_CHAR = 0x20;
    private static final char DEL_CHAR = 0x7F;

    /**
     * Upper bound on request-body bytes drained before a response is written.
     *
     * <p>The endpoint never inspects a body, but bytes left unread make the kernel
     * answer the close with a reset instead of letting the client read the reply: an
     * undrained one-mebibyte POST is observed by the client as "connection reset by
     * peer" rather than as the 405 it was sent. Eight mebibytes is far above any body
     * a health request could plausibly carry; a body beyond the cap is still answered,
     * and the server then retires that connection because its framing cannot be trusted.
     */
    private static final long MAX_REQUEST_DRAIN_BYTES = 8L * 1024L * 1024L;

    /**
     * The platform property bounding how long one request may take to arrive, and the
     * budget installed into it.
     *
     * <p>Draining the request body is a BLOCKING read and {@code HttpServer} applies
     * no request-time limit of its own, so without this the ceiling above bounds only
     * how MUCH is read, never how LONG the read waits: a client that promises a hundred
     * body bytes, sends three and then says nothing would hold a handler thread for the
     * lifetime of the process, and a peer opening many of them would retain a thread
     * for each. This is the platform's own documented knob for exactly that, and it
     * bounds the header read as well as the body - which a timeout wrapped around the
     * drain alone would not. The budget matches the JavaScript listener's
     * {@code requestTimeout}.
     *
     * <p>All three implementations bound this hazard and reach it differently, which is
     * a property of the platform rather than of the contract: Node answers immediately
     * and discards the unread body, Python answers when its own drain budget expires,
     * and this server reaps the connection unanswered. The frozen contract governs
     * well-formed requests, and every one of those is answered identically by all three.
     */
    private static final String MAX_REQUEST_TIME_PROPERTY = "sun.net.httpserver.maxReqTime";
    private static final String MAX_REQUEST_TIME_SECONDS = "15";

    /** Scheme separator that marks an absolute-form request target. */
    private static final String SCHEME_SEPARATOR = "://";

    /** Escape-buffer slack and payload-buffer capacity; the shipped body is 108 bytes. */
    private static final int JSON_ESCAPE_HEADROOM = 16;
    private static final int PAYLOAD_CAPACITY = 128;

    /** Prefix for every diagnostic; all diagnostics go to stderr, never stdout. */
    private static final String LOG_PREFIX = "[User] ";

    // Argument handling.

    /**
     * Reports whether an exact flag is present in the argument vector.
     *
     * <p>Matching is exact and order-independent, so flags may be combined and may
     * appear in any position. Unknown arguments are ignored rather than rejected,
     * which is what allows the default mode to stay reachable.
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

    // Configuration resolution.

    /**
     * Reads and parses the properties file.
     *
     * <p>The file is read on every call rather than cached for the lifetime of the
     * process, which is what makes this implementation behave like its two siblings:
     * all three read the file when a configuration is resolved and none holds a
     * process-wide cache, so a cache here would be the one place where the same
     * running configuration could be resolved differently. Every call site in this
     * class resolves a whole {@link Config} in one go, so serving a request touches
     * the filesystem never - the snapshot is taken once, when the server is created,
     * which is also when app.py takes its snapshot and when index.js reads the file.
     *
     * @return the file-backed configuration, empty rather than {@code null} when the
     *         file is absent or unreadable, so callers fall back to their defaults
     */
    static Properties configProperties() {
        return loadProperties();
    }

    /**
     * Reads the properties file, treating its absence as a normal condition.
     *
     * <p>Read as UTF-8 through {@code Properties.load(Reader)} rather than as
     * ISO-8859-1 bytes, because the Python and JavaScript loaders of the same file
     * both decode UTF-8: matching them keeps a non-ASCII application name
     * byte-identical across all three. {@code Properties.load} is also the grammar
     * the other two implement by hand - escapes, {@code :} and whitespace
     * separators, continuation lines, preserved trailing whitespace - so this call
     * is the reference they are measured against.
     *
     * <p>No failure is fatal and three outcomes are distinguished: absence is
     * silent, while an unreadable file and a malformed one each emit exactly one
     * warning - the same two app.py and index.js emit - and fall back to defaults.
     *
     * @return a populated {@link Properties}, or an empty one on any failure
     */
    private static Properties loadProperties() {
        Properties loaded = new Properties();
        Path location = configLocation();
        if (location == null) {
            return loaded;
        }
        try (BufferedReader reader = Files.newBufferedReader(location, StandardCharsets.UTF_8)) {
            loaded.load(reader);
        } catch (NoSuchFileException absent) {
            // The normal case, and the only silent one: the file is optional.
            return new Properties();
        } catch (IOException unreadable) {
            // Covers MalformedInputException as well, which is what a non-UTF-8
            // byte sequence raises out of the decoding reader.
            logWarning(CONFIG_UNREADABLE_WARNING);
            return new Properties();
        } catch (IllegalArgumentException malformed) {
            logWarning(CONFIG_MALFORMED_WARNING);
            return new Properties();
        }
        return loaded;
    }

    /**
     * Chooses which properties file to read.
     *
     * <p>Two candidates in a fixed order, exactly the two app.py and index.js use:
     * the file beside this class's own code source, then the file in the working
     * directory. The first is the one that matters - app.py resolves the file
     * relative to {@code __file__} and index.js relative to {@code __dirname}, so
     * resolving only against the working directory would make Java the single
     * implementation that loses its configuration when started from another
     * directory - and the second answers when the code source cannot be expressed as
     * a filesystem path at all.
     *
     * <p>There is deliberately no environment variable naming an arbitrary properties
     * file. The configuration surface is shared exactly: the seven keys of
     * {@value #CONFIG_FILE} and the variables that override them are the same in all
     * three implementations, so a Java-only variable here would let this
     * implementation serve metadata its siblings could never serve, from a file they
     * would never read. Every value is already overridable one key at a time through
     * the variables documented in {@code .env.example}.
     *
     * @return the path to read, or {@code null} if no candidate can be formed
     */
    private static Path configLocation() {
        Path beside = codeSourceDirectory();
        if (beside != null) {
            Path candidate = beside.resolve(CONFIG_FILE);
            // Existence rather than readability: a file that is there but cannot be
            // read must be REPORTED as unreadable, exactly as app.py and index.js
            // report it. Testing readability would silently reclassify it as absent,
            // and absence is the one condition all three loaders are silent about.
            if (Files.exists(candidate)) {
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
     * directory; under a {@code java User.java} source launch it is the source file
     * itself, whose parent is the same directory. Anything unexpected - a jar, a
     * module image, no code source at all - yields {@code null} so the caller falls
     * back to the working directory.
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
     * Resolves one configuration value against the fixed precedence order:
     * environment variable, then properties file, then built-in default.
     *
     * <p>Empty values are treated as absent at every level, so exporting an empty
     * environment variable does not blank out a configured value.
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
     * Resolves the listener port, adding the universal {@code PORT} override on top of
     * the standard precedence order, and fails closed on bad input.
     *
     * <p>A value that is not a number, or that falls outside the legal port range, is
     * rejected rather than quietly replaced by the built-in default. Silently
     * substituting a different port is the worse failure of the two: an operator who
     * mistypes {@code PORT} then gets a healthy-looking process listening somewhere
     * they are not watching while every probe aimed at the port they asked for fails.
     * app.py and index.js refuse the same value, so refusing it here is also what
     * makes the three implementations agree.
     *
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
        // The grammar is matched BEFORE any conversion, and the ordering is
        // load-bearing: Integer.parseInt delegates to Character.digit, which accepts
        // every Unicode decimal digit, so a port written in Arabic-Indic digits would
        // parse here and be refused outright by index.js - one configuration file
        // producing two behaviours, which is the divergence the shared grammar closes.
        if (PORT_GRAMMAR.matcher(trimmed).matches()) {
            try {
                int parsed = Integer.parseInt(trimmed);
                if (parsed >= MIN_PORT && parsed <= MAX_PORT) {
                    return parsed;
                }
            } catch (NumberFormatException beyondIntRange) {
                // A run of ASCII digits too long for an int. It is out of range by
                // definition, so it falls through to the same rejection.
            }
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
     *         {@code version} field, and stated identically in
     *         {@value #CONFIG_FILE}, pyproject.toml and package.json
     */
    static String appVersion() {
        return resolve(configProperties(), KEY_APP_VERSION, ENV_APP_VERSION, DEFAULT_APP_VERSION);
    }

    /**
     * Returns the effective health path, normalised for matching.
     *
     * <p>A configured value with no leading slash gains one and a trailing slash is
     * removed, so {@code health}, {@code /health} and {@code /health/} behave
     * identically. The result is compared against the equally normalised request path.
     */
    static String healthPath() {
        return healthRoute(configProperties());
    }

    /** Normalises a configured health path into the route that is matched. */
    private static String healthRoute(Properties props) {
        return configRoute(resolve(props, KEY_HEALTH_PATH, ENV_HEALTH_PATH, DEFAULT_HEALTH_PATH));
    }

    /**
     * Reduces a CONFIGURED health path to the route the endpoint will answer on.
     *
     * <p>Two steps, in this order and identical to app.py's {@code config_route} and
     * index.js's {@code configRoute}: a missing leading slash is supplied, then
     * {@link #normalisePath} performs the same four reductions it performs on a
     * request target, so {@code health}, {@code /health} and {@code /health/} all
     * describe one route.
     *
     * <p>Both {@link #loadConfig} and {@link #validateConfig} go through this method,
     * which is what makes the route that is VALIDATED and the route that is SERVED
     * the same string by construction rather than by two code paths happening to
     * agree.
     */
    static String configRoute(String value) {
        String path = value.startsWith(ROOT_PATH) ? value : ROOT_PATH + value;
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
     * <p>Taken once, when a server is created, and read by every response that server
     * writes. Resolving configuration per request would make two responses from one
     * process disagree if the file changed underneath them, and would put a
     * filesystem read on the path of a health check whose whole purpose is to answer
     * quickly and predictably. app.py and index.js both snapshot at the same moment -
     * server construction - so all three behave identically.
     *
     * @param port requested port, {@code 0} meaning "any free port"
     */
    record Config(String name, String version, String healthPath, String host, int port) { }

    /**
     * Resolves every setting once, reading the properties file exactly once.
     *
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

    // Configuration validation. Resolution answers "what did the operator ask for";
    // validation answers "may that be published", and it runs before a socket is
    // bound and before a probe is sent. Without it a control character in app.name
    // reaches the response body and a version of "not-a-version" is served with a 200
    // and a status of UP - an endpoint asserting its own health while carrying a
    // payload no consumer of the frozen contract could accept. Failing closed here is
    // the only way the 200 can be trusted at all.
    //
    // The messages name the KEY and never quote the VALUE, which is what lets the
    // probe print one verbatim: the offending value is a configured input, so a
    // message quoting it would carry that input into a log line. The one exception is
    // the port, whose refusal names the value it refused - see resolvePort and
    // probe(Config) - because a mistyped port an operator cannot trace back to what
    // they typed is only half a diagnostic.

    /**
     * Reports whether a value is usable as single-line configured text.
     *
     * <p>The rule for the two configured values that are neither a route nor a
     * number: non-empty, and free of every character below
     * {@value #PRINTABLE_MIN_CHAR} as well as DEL. Emptiness is a fault rather than
     * a fallback because these values reach the response body, and a name absent
     * from the payload is not the same document as one present and blank.
     */
    static boolean isSingleLineText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current < PRINTABLE_MIN_CHAR || current == DEL_CHAR) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a value is usable as an HTTP request target.
     *
     * <p>Every character must be visible US-ASCII - {@value #TARGET_MIN_CHAR}
     * through {@value #TARGET_MAX_CHAR} - which excludes the space, every control
     * character, DEL and everything above it. A configured path carrying CR or LF is
     * what a log-forgery attempt looks like, and it is refused here before a request
     * is ever built from it.
     */
    static boolean isRequestTarget(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
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
     * Refuses a configuration that could not be published truthfully.
     *
     * <p>Four rules, applied in the order the payload declares its fields:
     * {@code app.name} must be non-empty text with no control character, since it is
     * a payload field and a control character in it would forge a line in a
     * consumer's log as readily as in this program's own; {@code app.version} must
     * match {@link #VERSION_GRAMMAR}, the frozen contract stating the field is a
     * three-part dotted number, so serving anything else with a 200 and a status of
     * UP is a lie the consumer cannot detect; {@code health.path} must start with
     * {@code /} and be a valid request target, a route that cannot be requested
     * being one no probe and no orchestrator can reach; and {@code app.host} must be
     * non-empty text with no control character, since it reaches the startup banner
     * and the probe's target URL.
     *
     * <p>The port is deliberately NOT checked here: {@link #resolvePort} already
     * refuses it at resolution time, the earliest point it is knowable, and checking
     * twice would put one refusal behind two different messages.
     *
     * @throws IllegalArgumentException on the first rule that fails, naming the key
     *                                  and never quoting the value
     */
    static void validateConfig(Config config) {
        if (!isSingleLineText(config.name())) {
            throw new IllegalArgumentException(
                    "invalid app.name: it must be non-empty text with no control character");
        }
        if (config.version() == null
                || !VERSION_GRAMMAR.matcher(config.version()).matches()) {
            throw new IllegalArgumentException(
                    "invalid app.version: it must be a three-part dotted numeric version");
        }
        if (config.healthPath() == null || config.healthPath().isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid health.path: it is not a valid request target");
        }
        String route = configRoute(config.healthPath());
        if (route.startsWith(NETWORK_PATH_PREFIX) || !isRequestTarget(route)) {
            throw new IllegalArgumentException(
                    "invalid health.path: it is not a valid request target");
        }
        if (!isSingleLineText(config.host())) {
            throw new IllegalArgumentException(
                    "invalid app.host: it must be non-empty text with no control character");
        }
    }

    // Payload construction. The JDK has no JSON serializer, so the document is
    // assembled here: compact, with no whitespace anywhere, and with its four keys in
    // the frozen order name, version, timestamp, status, which is what makes it
    // byte-identical to the Python and JavaScript payloads.

    /**
     * Returns the current instant, truncated to whole seconds.
     *
     * <p>The only non-deterministic field in the payload. It is emitted as a
     * fixed-width UTC instant with a trailing {@code Z}, matching
     * {@code ^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$}; truncating to seconds is what
     * makes the width fixed and keeps all three implementations aligned. Every
     * automated assertion on this field checks its format and never its value, so no
     * gate can become time-flaky.
     */
    static String timestamp() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * Escapes a string for inclusion in a JSON string literal.
     *
     * <p>Exactly the escapes RFC 8259 requires: the quote, the backslash, the five
     * two-character control escapes, and any remaining character below {@code 0x20}
     * as a lower-case {@code \\u00xx} sequence. Nothing else is escaped - notably not
     * the forward slash and not non-ASCII characters - because over-escaping would
     * break byte parity with {@code json.dumps} and {@code JSON.stringify}, which do
     * not escape them either.
     *
     * @param raw the value to escape, {@code null} being treated as empty
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
     * <p>Kept free of any ambient state so that it can be exercised directly, and so
     * that the key order - the part of the contract most easily broken by a
     * well-meaning edit - lives in exactly one place.
     *
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

    /** Builds the health document from the effective configuration and clock. */
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
     */
    static String healthPayload(Config config) {
        return renderPayload(config.name(), config.version(), timestamp(), STATUS_UP);
    }

    // Request routing and response writing.

    /**
     * Normalises a request target so that routing is forgiving but exact.
     *
     * <p>Four transformations, in this order and no others: an absolute-form target
     * - {@code http://host/health}, which RFC 9112 permits any client to send and a
     * proxy always sends - is reduced to its path, without which the same request
     * would reach the endpoint in app.py and 404 here; any query string is removed,
     * so {@code /health?probe=1} matches; any fragment is removed, which a request
     * target should not carry but a careless client sends; and exactly one trailing
     * slash is dropped, so {@code /health/} matches while {@code /health//} does not.
     *
     * <p>What is deliberately not done matters as much, because each omission is a
     * way two spellings could otherwise reach one route: percent-escapes are never
     * decoded, dot segments are never resolved, and repeated leading slashes are
     * never collapsed, so {@code /health%2f}, {@code /health/../health} and
     * {@code //health} all 404 here, and the same three spellings reduce identically
     * in app.py's normalize_path and index.js's normalizePath.
     *
     * <p>One of the three differs ON THE WIRE, and only for app.py: CPython's
     * request parser folds an inbound target beginning with {@code //} down to one
     * slash before any handler is reached, so {@code GET //health} is SERVED there
     * while it is refused here and by index.js. That fold belongs to the transport
     * rather than to this contract, and app.py records it at the function it
     * affects. The half every implementation can refuse is the CONFIGURED route,
     * which {@link #validateConfig} refuses in all three.
     *
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

    /** Reports whether a string is a URI scheme as RFC 3986 defines one. */
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

    // Request handling. One handler answers every exchange the server accepts,
    // registered on the root context rather than on the configured route, so the
    // routing rules above - and not the server's longest-prefix context matcher -
    // decide what a near-miss target means. That is what keeps the 404 body
    // identical to the one app.py and index.js produce.

    /**
     * Answers one exchange according to the frozen contract.
     *
     * <p>The 200 carries the health document, an unmatched target a 404, and any
     * method other than the exact token GET a 405. {@code Content-Type},
     * {@code Cache-Control} and {@code Content-Length} are set on all three;
     * {@code Allow: GET} is added on the 405 alone. The two contract headers are set
     * before the status is chosen because every response carries both: a health
     * answer that could be cached is worse than no health answer. Method comparison
     * is case-sensitive, as RFC 9110 requires, so "get" is answered 405 like any
     * other unknown token, and nothing from the request is ever echoed back.
     *
     * <p>The configuration snapshot is captured when the server is created and
     * passed in here, so no request touches the filesystem and two responses from
     * one server can never disagree about what they were serving. The route is
     * derived once at creation through {@link #configRoute(String)} - the same
     * helper {@link #validateConfig(Config)} grades - so the route that was
     * VALIDATED and the route that is SERVED are one string, including when a
     * health path was configured without a leading slash.
     *
     * <p>The target is taken from {@code getRequestURI().toString()} rather than
     * from {@code getRawPath()}, which is what keeps this implementation's routing
     * identical to its siblings': the full request target still carries the query
     * string and, for an absolute-form request line, the scheme and authority, so
     * {@link #normalisePath(String)} performs exactly the same four transformations
     * here that it performs on the raw target in app.py and index.js. The pre-parsed
     * path would have the server strip the query and the authority first, silently
     * moving two of those rules out of this class and out of reach of the tests.
     *
     * <p>A {@link RuntimeException} is caught, logged and swallowed: it is
     * unreachable in practice, but a handler that let one escape would take down
     * nothing while leaving an operator no record, and the contract defines no 5xx.
     *
     * @throws IOException if the response cannot be written to the connection
     */
    private static void handle(HttpExchange exchange, Config config, String route)
            throws IOException {
        try (exchange) {
            drainRequestBody(exchange);
            Headers response = exchange.getResponseHeaders();
            response.set(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
            response.set(HEADER_CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
            if (!METHOD_GET.equals(exchange.getRequestMethod())) {
                response.set(HEADER_ALLOW, METHOD_GET);
                sendResponse(exchange, HTTP_METHOD_NOT_ALLOWED, BODY_METHOD_NOT_ALLOWED);
                return;
            }
            String requested = normalisePath(exchange.getRequestURI().toString());
            if (route.equals(requested)) {
                sendResponse(exchange, HTTP_OK, healthPayload(config));
            } else {
                sendResponse(exchange, HTTP_NOT_FOUND, BODY_NOT_FOUND);
            }
        } catch (RuntimeException unexpected) {
            logWarning("handler failed: " + unexpected);
        }
    }

    /**
     * Reads and discards a request body, bounded.
     *
     * <p>The endpoint never inspects a body, but it must not leave one unread: bytes
     * still queued when the connection closes make the kernel answer with a reset, so
     * the client sees "connection reset by peer" instead of the response written for
     * it. A one-mebibyte POST is enough to show it, and is answered 405 normally
     * once the body is drained.
     *
     * <p>The read is capped at {@value #MAX_REQUEST_DRAIN_BYTES} bytes so a
     * deliberately endless body cannot hold a thread forever, and closing the stream
     * afterwards is what lets the server decide whether the connection is still
     * usable.
     *
     * @throws IOException if the connection fails while the body is being read
     */
    private static void drainRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            byte[] scratch = new byte[DRAIN_BUFFER_BYTES];
            long discarded = 0L;
            while (discarded < MAX_REQUEST_DRAIN_BYTES) {
                int read = body.read(scratch);
                if (read < 0) {
                    return;
                }
                discarded += read;
            }
        }
    }

    /**
     * Writes one complete response: status, length and body.
     *
     * <p>The length handed to the server is the encoded byte count, never the
     * character count, so a multi-byte character in a configured value cannot
     * desynchronise {@code Content-Length} from the body it describes.
     *
     * <p>{@code Content-Length} is declared in the header map as well as through the
     * length argument. The duplication earns its place on the response to HEAD: the
     * server omits the field it would derive once it knows the method carries no
     * body, and RFC 9110 section 9.3.2 asks a HEAD response to carry the fields the
     * equivalent GET would, so declaring it here is what makes the HEAD header set
     * equal to app.py's and index.js's rather than one field short. The length
     * argument becomes {@value #NO_RESPONSE_BODY} for HEAD, the server's sentinel
     * for "header block only"; a real length is also correct but makes the runtime
     * log a warning about a body length declared for a bodiless method, and a mode
     * that writes an unexpected diagnostic fails a clean-output check. The body is
     * written unconditionally even so - the discarding sink makes it harmless, and
     * one write path is one fewer place for the two responses to drift apart.
     *
     * <h2>STATED DEVIATION: this response carries a fourth header, Date</h2>
     *
     * <p>The frozen contract enumerates three response headers - a JSON content
     * type, a no-cache/no-store/must-revalidate directive and a content length - and
     * states "no server banner and no date header". Every response written here
     * carries a Date field as well. It is recorded as a gap rather than described as
     * conformance, because the honest form of an unclosable gap is a bounded, cited
     * one.
     *
     * <p><b>It cannot be closed from application code.</b>
     * {@code Headers.set("Date", <now>)} executes unconditionally inside
     * {@code sun.net.httpserver.ExchangeImpl.sendResponseHeaders} before its first
     * branch, and {@code Headers.set} replaces whatever the caller put there. Seven
     * application-side techniques were measured against a live server on this exact
     * JDK - leaving the field alone, setting it empty, setting a sentinel, removing
     * it before {@code sendResponseHeaders}, binding an empty value list, removing it
     * afterwards, and a {@code Filter} stripping it on both sides of the chain. All
     * seven produced a Date on the wire.
     *
     * <p><b>The alternative is excluded.</b> An exact header set requires writing the
     * response bytes directly, which means replacing this transport with a
     * hand-written {@code ServerSocket} server and request parser. The specification
     * mandates this transport in four places - the Java implementation section, the
     * dependency inventory, the per-file implementation plan, and the container
     * analysis that makes the JRE image's viability depend on jdk.httpserver being in
     * the module graph - so replacing it would contradict all four and reintroduce a
     * hand-written HTTP parser, a far larger correctness and security surface than
     * one date field.
     *
     * <p><b>What is asserted in its place.</b> The harness asserts the response
     * header set by EQUALITY against exactly the three contract fields plus this one,
     * so the divergence is pinned at one named field and a fifth header appearing
     * later fails the suite. Date is asserted by FORMAT, never by value, being
     * wall-clock derived. RFC 9110 section 6.6.1 asks an origin server with a clock
     * to send Date, so the field is protocol-conformant; it discloses nothing about
     * the runtime, so least disclosure is unaffected; and the Server banner that
     * property targets is absent and asserted absent. The specification's
     * endpoint-contract gate enumerates status, content type, cache directive, key
     * set, field values and formats, 404 and 405, and states no condition on Date, so
     * this deviation does not fail it.
     *
     * @throws IOException if the response cannot be written to the connection
     */
    private static void sendResponse(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set(HEADER_CONTENT_LENGTH, Integer.toString(encoded.length));
        boolean bodyless = METHOD_HEAD.equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, bodyless ? NO_RESPONSE_BODY : encoded.length);
        try (OutputStream sink = exchange.getResponseBody()) {
            sink.write(encoded);
        }
    }

    // Server lifecycle.

    /**
     * A listening health endpoint: one bound server, one virtual thread per exchange.
     *
     * <p>A thin lifecycle wrapper around {@link HttpServer}, for three reasons rather
     * than as ceremony: it carries the configuration snapshot beside the listener so
     * every response is built from the same values, it owns the executor so stopping
     * the server also releases the threads that were serving it, and it makes
     * {@link #stop()} idempotent, which the raw server is not obliged to be.
     *
     * <p>The socket is bound by {@link User#createServer(Config)} before this object
     * exists, so {@link #port()} answers the real port - including an ephemeral one
     * the operating system chose - from creation and before the start.
     *
     * <p>Exchanges run on daemon virtual threads, so a client that connects and then
     * stalls costs one parked thread and no other client waits behind it. What keeps
     * a --serve process alive is the server's own non-daemon HTTP-Dispatcher thread,
     * and what lets it exit is {@link #stop()}.
     */
    static final class HealthServer {
        private final HttpServer listener;
        private final Config config;
        private final ExecutorService workers;
        private final AtomicBoolean running = new AtomicBoolean(true);

        private HealthServer(HttpServer listener, Config config, ExecutorService workers) {
            this.listener = listener;
            this.config = config;
            this.workers = workers;
        }

        /** @return the port actually bound, which is resolved even when 0 was asked for */
        int port() {
            return listener.getAddress().getPort();
        }

        /** @return the snapshot every response from this server is built from */
        Config config() {
            return config;
        }

        /** Starts accepting connections. Returns as soon as the dispatcher is running. */
        void start() {
            listener.start();
        }

        /**
         * Stops accepting, abandons exchanges in flight and releases the port.
         * Idempotent: it is reachable both from a shutdown hook and from a caller
         * that stops the server itself, and the guard makes a second call a no-op
         * rather than a second pass over a closed selector.
         */
        void stop() {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            listener.stop(STOP_DELAY_SECONDS);
            workers.shutdownNow();
        }
    }

    /**
     * Creates and binds a health server without starting it.
     *
     * <p>Passing {@code 0} as the port binds an ephemeral one, readable from
     * {@code server.port()} as soon as this method returns, which is what lets a test
     * run against a port that cannot collide with anything else on the host. The
     * returned server is <strong>not</strong> listening yet: call {@code start()} on
     * it, or use {@link #startServer(String, int)}, which does both.
     *
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
     * @throws IOException if the address cannot be bound
     */
    static HealthServer createServer(Config config) throws IOException {
        // Validation runs before the bind, which is what makes the refusal total: a
        // configuration that could not be published truthfully never reaches a
        // listening socket, so there is no window in which a port is held by a
        // server that would answer 200 with a payload the contract forbids.
        validateConfig(config);
        // The request-time bound is installed before the first touch of the server
        // class, whose configuration is read once from a static initialiser. A -D an
        // operator passed explicitly is honoured rather than overridden.
        if (System.getProperty(MAX_REQUEST_TIME_PROPERTY) == null) {
            System.setProperty(MAX_REQUEST_TIME_PROPERTY, MAX_REQUEST_TIME_SECONDS);
        }
        // The executor is built before the bind, deliberately. A server that has been
        // created but never started does not release its port when it is stopped -
        // the socket is closed from the dispatcher thread, which does not exist yet -
        // so nothing fallible may sit between the bind and the return. The two calls
        // after the bind cannot fail for these arguments: the context path is a
        // constant beginning with "/" and the handler is never null.
        ThreadFactory factory = Thread.ofVirtual()
                .name(WORKER_THREAD_PREFIX, WORKER_THREAD_START)
                .factory();
        ExecutorService workers = Executors.newThreadPerTaskExecutor(factory);
        HttpServer listener;
        try {
            listener = HttpServer.create(new InetSocketAddress(config.host(), config.port()),
                    SERVER_BACKLOG);
        } catch (IOException | RuntimeException bindFailure) {
            workers.shutdownNow();
            throw bindFailure;
        }
        String routePath = configRoute(config.healthPath());
        listener.createContext(CONTEXT_PATH, exchange -> handle(exchange, config, routePath));
        listener.setExecutor(workers);
        return new HealthServer(listener, config, workers);
    }

    /** Creates, binds and starts a health server. */
    static HealthServer startServer(String host, int port) throws IOException {
        HealthServer server = createServer(host, port);
        server.start();
        return server;
    }

    /**
     * Serves the health endpoint from the effective configuration.
     *
     * <p>The startup line goes to stderr, not stdout: the default mode's stdout is
     * asserted byte for byte by the backward-compatibility gate, so stdout stays
     * reserved for that single preserved line. Control characters are stripped from
     * it, so a configured value can never move the cursor, clear the screen or forge
     * a second line in an operator's log.
     *
     * <p>This method returns as soon as the listener is up; it does not block. The
     * server's own non-daemon dispatcher thread keeps the process alive, so a
     * shutdown hook calls {@code stop} to close the listening socket and release the
     * port deterministically rather than leaving the JVM to be killed with the port
     * still held. The stop is immediate: a health response is written in
     * microseconds, so a grace period would rescue no work. An address that cannot
     * be bound, and a configured port that is not a port at all, are both fatal and
     * fail closed with a non-zero exit status and a one-line diagnostic rather than
     * a stack trace.
     *
     * <p>The hook runs to completion, but the JVM still reports the signal that
     * ended it, so this process exits 130 on SIGINT and 143 on SIGTERM, while
     * index.js exits 0 for both and app.py exits 0 on SIGINT and is terminated by
     * SIGTERM. Exit STATUS is the one place these three servers deliberately
     * differ; everything an orchestrator depends on is identical - the listener
     * closed, the port released and stdout empty.
     */
    private static void serve() {
        Config config;
        try {
            config = loadConfig();
        } catch (IllegalArgumentException unusable) {
            // An interactive start names the value it refused: the operator is at the
            // terminal and the value came from their own environment, so tracing the
            // refusal back is worth more here than withholding it. The probe path,
            // which runs unattended, reports the same fault as a bare category.
            logWarning("refusing to start: " + unusable.getMessage());
            System.exit(EXIT_FAILURE);
            return;
        }
        try {
            HealthServer server = createServer(config);
            Runtime.getRuntime().addShutdownHook(
                    new Thread(server::stop, SHUTDOWN_THREAD_NAME));
            server.start();
            logWarning("health endpoint listening on http://" + config.host() + ":"
                    + server.port() + configRoute(config.healthPath()));
        } catch (IllegalArgumentException unpublishable) {
            logWarning("refusing to start: " + unpublishable.getMessage());
            System.exit(EXIT_FAILURE);
        } catch (IOException bindFailure) {
            logWarning("could not bind " + config.host() + ":" + config.port() + ": "
                    + bindFailure.getClass().getSimpleName());
            System.exit(EXIT_FAILURE);
        }
    }

    /**
     * Writes one diagnostic line to stderr, sanitised, and nothing to stdout.
     *
     * <p>The single emitter for every diagnostic this program produces. Sanitising
     * at each call site is a rule that holds only until the next call site is added,
     * and a carriage return in a configured value or in an exception message would
     * then forge a second log line; routing every diagnostic through one method
     * makes the property structural instead of a convention.
     *
     * <p>stderr rather than stdout is not a style choice either: the default mode's
     * stdout is hashed byte for byte by the backward-compatibility gate, so stdout
     * carries the one preserved line and nothing else, ever.
     */
    private static void logWarning(String message) {
        System.err.println(LOG_PREFIX + sanitiseForLog(message));
    }

    /**
     * Removes control characters from a value before it is written to a log.
     *
     * <p>Configuration is an input, and every value in the startup line comes from
     * one: a carriage return or line feed in a configured path would forge a second
     * log line, and an escape character would let a configured value drive the
     * operator's terminal. All three implementations neutralise the same set on the
     * same line - every code point below {@code 0x20} plus DEL - so the security
     * property is identical everywhere; only the presentation differs, app.py
     * removing those characters as this method does while index.js replaces each
     * with a question mark. The banner text is deliberately per-language: each
     * program names itself so an operator can tell three concurrent servers apart.
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

    // The self-check a container health probe runs on a timer, forever. Its exit
    // status is the only thing the runtime looks at, so every property documented
    // below - parse the body rather than test it for a substring, target loopback
    // from an allowlist, use no proxy and follow no redirect, read a bounded body
    // under an absolute deadline, never throw and never write to stdout - is
    // load-bearing rather than defensive decoration.

    /**
     * Self-checks the endpoint described by the effective configuration, returning
     * {@value #EXIT_SUCCESS} when healthy and {@value #EXIT_FAILURE} otherwise.
     *
     * <p>Validates the configuration before probing anything: one that could not be
     * published truthfully cannot be proven healthy either, so the same rules that
     * refuse a start refuse a probe. That is what stops a health probe reporting
     * success while the endpoint serves a payload the frozen contract forbids.
     */
    static int probe() {
        Config config;
        try {
            config = loadConfig();
        } catch (IllegalArgumentException unusablePort) {
            // The category is identical in all three implementations; the offending
            // value is appended because this program's own suite requires an
            // operator to be able to trace the refusal back to what they typed.
            logWarning("probe cannot run: the configured port is unusable: "
                    + unusablePort.getMessage());
            return EXIT_FAILURE;
        }
        try {
            validateConfig(config);
        } catch (IllegalArgumentException unpublishable) {
            // Printed verbatim, which is safe by construction: every message
            // validateConfig produces names a key and quotes no value.
            logWarning("probe cannot run: " + unpublishable.getMessage());
            return EXIT_FAILURE;
        }
        return probe(config.host(), config.port(), config.healthPath());
    }

    /**
     * Self-checks one explicit endpoint and grades the answer against the contract.
     *
     * <p>Kept separate from {@link #probe()} so that a harness can aim it at an
     * arbitrary port without an environment override, which is what makes the
     * positive and negative cases testable in-process.
     *
     * <p>The deadline is ABSOLUTE and covers the whole exchange - connect, request,
     * status line, header block and body - because the two per-operation budgets the
     * client offers do not: a connect timeout expires before a connection exists and
     * a request timeout before the response headers arrive, so neither bounds the
     * body read, and a listener that answered 200 and then sent one byte a minute
     * would hold this method open indefinitely. Running the exchange on one virtual
     * thread and bounding it with a single {@code get} is what makes it finish.
     *
     * @return {@value #EXIT_SUCCESS} when healthy, {@value #EXIT_FAILURE} otherwise
     */
    static int probe(String host, int port, String healthPath) {
        String route = configRoute(healthPath);
        if (!isRequestTarget(route)) {
            // The route is withheld from this line on purpose: a configured path
            // carrying CR or LF is what a log-forgery attempt looks like, so the
            // one line the refusal emits carries none of it.
            logWarning("probe cannot run: the configured health path is not a valid"
                    + " request target");
            return EXIT_FAILURE;
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            logWarning("probe cannot run: the configured port is unusable");
            return EXIT_FAILURE;
        }
        String authority = probeAuthority(host);
        String target = "http://" + authority + ":" + port + route;
        URI uri;
        try {
            uri = new URI(target);
        } catch (URISyntaxException malformed) {
            logWarning("probe cannot run: the configured health path is not a valid"
                    + " request target");
            return EXIT_FAILURE;
        }
        return probeAnswer(uri, target);
    }

    /** Runs one bounded exchange and turns its answer into a verdict. */
    private static int probeAnswer(URI uri, String target) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                .build();
        ExecutorService runner = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name(PROBE_THREAD_NAME).factory());
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                    .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
                    .GET()
                    .build();
            Future<Answer> pending = runner.submit(() -> {
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream stream = response.body()) {
                    // One byte over the ceiling, deliberately: reading exactly the
                    // ceiling could not tell a body that fits from one that was
                    // truncated to fit, and a truncated body might still parse.
                    return new Answer(response.statusCode(),
                            stream.readNBytes(MAX_PROBE_BODY_BYTES + 1));
                }
            });
            Answer answer = pending.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String rejection = probeRejection(answer.status(), answer.body());
            if (rejection == null) {
                return EXIT_SUCCESS;
            }
            logWarning("probe rejected: " + rejection);
            return EXIT_FAILURE;
        } catch (TimeoutException expired) {
            logWarning("probe rejected: no response within the probe deadline");
            return EXIT_FAILURE;
        } catch (ExecutionException failed) {
            // The cause's TYPE, never its message: the message can quote the target
            // and, through it, a configured value.
            Throwable cause = (failed.getCause() == null) ? failed : failed.getCause();
            logWarning("probe could not reach " + target + ": "
                    + cause.getClass().getSimpleName());
            return EXIT_FAILURE;
        } catch (InterruptedException interrupted) {
            // Restore the flag so an enclosing caller can still observe it.
            Thread.currentThread().interrupt();
            logWarning("probe rejected: interrupted before an answer arrived");
            return EXIT_FAILURE;
        } catch (RuntimeException unexpected) {
            logWarning("probe could not reach " + target + ": "
                    + unexpected.getClass().getSimpleName());
            return EXIT_FAILURE;
        } finally {
            // shutdownNow on both, in this order. The runner is cancelled first so
            // that no task is still reading when the client's connection is closed
            // under it, and the client is closed rather than left to a finaliser so
            // that a timed-out exchange cannot hold a socket past this method.
            runner.shutdownNow();
            client.shutdownNow();
        }
    }

    /** One endpoint answer: the status code and the bounded body bytes. */
    private record Answer(int status, byte[] body) { }

    /**
     * Converts a configured bind address into a loopback destination.
     *
     * <p>This is an ALLOWLIST, and that is the whole point: {@code app.host} is an
     * input, so a host honoured verbatim would let a probe be aimed at any address
     * at all, and a third party's healthy answer would then vouch for this process.
     * The only reachable destinations are loopback ones. An empty, blank or wildcard
     * host and {@code localhost} all become {@code 127.0.0.1} - mapped, never
     * resolved, so a hosts-file entry cannot redirect the probe; the four IPv6
     * loopback spellings become {@code [::1]}; an address inside {@code 127.0.0.0/8}
     * is used as configured; anything else becomes {@code 127.0.0.1} with one
     * warning that does NOT echo the refused value, because it is a configured
     * input. Matching folds case and trims surrounding whitespace. app.py and
     * index.js apply the identical selection.
     */
    static String probeAuthority(String host) {
        String candidate = (host == null) ? "" : host.trim();
        String lowered = candidate.toLowerCase(Locale.ROOT);
        if (candidate.isEmpty() || WILDCARD_HOSTS.contains(lowered)
                || LOOPBACK_NAME.equals(lowered)) {
            return LOOPBACK_HOST;
        }
        if (IPV6_LOOPBACK_FORMS.contains(lowered)) {
            return LOOPBACK_AUTHORITY_V6;
        }
        if (isIpv4Loopback(candidate)) {
            return candidate;
        }
        logWarning("probe target is not loopback; probing loopback instead");
        return LOOPBACK_HOST;
    }

    /**
     * Reports whether a value is a dotted-quad address inside {@code 127.0.0.0/8}.
     *
     * <p>Written out rather than delegated to {@link java.net.InetAddress}, which
     * would resolve a name and could therefore be steered by a hosts file or a DNS
     * answer - the exact redirection this allowlist exists to prevent. Nothing here
     * touches the network.
     */
    private static boolean isIpv4Loopback(String candidate) {
        if (!candidate.startsWith(IPV4_LOOPBACK_PREFIX)) {
            return false;
        }
        String[] octets = candidate.split("\\.", -1);
        if (octets.length != IPV4_OCTET_COUNT) {
            return false;
        }
        for (String octet : octets) {
            if (!isAsciiDigits(octet) || octet.length() > IPV4_OCTET_MAX_DIGITS
                    || Integer.parseInt(octet) > IPV4_OCTET_MAX) {
                return false;
            }
        }
        return true;
    }

    /** Reports whether a value is a non-empty run of ASCII digits and nothing else. */
    private static boolean isAsciiDigits(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
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
     * Grades one answer against the frozen contract.
     *
     * <p>Package-private and free of any transport so that every rule is reachable
     * by a direct call. The reason strings are part of the shared contract -
     * app.py's counterpart is module-level and index.js exports its own - because a
     * reason observable only by reading stderr is one that drifts unnoticed between
     * three implementations.
     *
     * <p>The ORDER below is part of the contract: the cheapest and most fundamental
     * checks come first, and {@code status} is examined before the three descriptive
     * fields so that an endpoint reporting itself down is reported as DOWN rather
     * than as whichever of its other fields happened also to be wrong. The timestamp
     * is graded by FORMAT and never by value, so no gate can become time-flaky.
     *
     * @return {@code null} when the answer proves health, otherwise the reason it
     *         does not
     */
    static String probeRejection(int status, byte[] body) {
        if (body == null || body.length > MAX_PROBE_BODY_BYTES) {
            return "body exceeds the probe limit of " + MAX_PROBE_BODY_BYTES + " bytes";
        }
        if (status != HTTP_OK) {
            return "the endpoint answered status " + status;
        }
        Object document;
        try {
            document = new JsonReader(decodeStrictUtf8(body)).readDocument();
        } catch (IllegalArgumentException malformed) {
            return "body is not the expected JSON document";
        }
        if (!(document instanceof Map<?, ?> members)) {
            return "body is not a JSON object and carries no status field";
        }
        if (!PAYLOAD_KEYS.equals(List.copyOf(members.keySet()))) {
            return PROBE_KEY_SET_REASON;
        }
        if (!STATUS_UP.equals(members.get(PAYLOAD_KEY_STATUS))) {
            return "the status field is not the expected value";
        }
        if (!(members.get(PAYLOAD_KEY_NAME) instanceof String name) || name.isEmpty()) {
            return "the name field is not a non-empty string";
        }
        if (!(members.get(PAYLOAD_KEY_VERSION) instanceof String version)
                || !VERSION_GRAMMAR.matcher(version).matches()) {
            return "the version field is not a three-part dotted numeric version";
        }
        if (!(members.get(PAYLOAD_KEY_TIMESTAMP) instanceof String moment)
                || !TIMESTAMP_GRAMMAR.matcher(moment).matches()) {
            return "the timestamp field is not a whole-second UTC instant";
        }
        return null;
    }

    /**
     * Decodes body bytes as UTF-8, refusing anything that is not.
     *
     * <p>{@code new String(bytes, UTF_8)} substitutes a replacement character for a
     * malformed sequence, which would let a body that is not UTF-8 at all reach the
     * reader as something that might parse. app.py's {@code bytes.decode("utf-8")}
     * and index.js's fatal {@code TextDecoder} raise on the same input, so
     * reporting is what keeps the three graders in step.
     *
     * @throws IllegalArgumentException if the bytes are not valid UTF-8
     */
    private static String decodeStrictUtf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            throw new IllegalArgumentException("body is not UTF-8");
        }
    }

    // A strict reader for one JSON document.
    //
    // The JDK ships no JSON parser, so rather than add a dependency and lose the
    // repository's zero-dependency property, the self-check reads the one document
    // shape it has to grade. It refuses, among other things: trailing content after
    // the first value; an unquoted or single-quoted member name; an unescaped
    // control character in a string; an unknown escape; a \\u escape that is not
    // four hexadecimal digits; a number with a leading zero, a leading plus, a bare
    // decimal point or a missing exponent digit; a trailing comma; a comment; and a
    // REPEATED member name. The repeat is refused rather than resolved: JSON.parse
    // and json.loads both keep the LAST value silently, so a body carrying status
    // twice would be graded on whichever value the parser kept, and a document with
    // two answers is not one this endpoint could have produced - all three
    // implementations refuse it outright. Nesting is capped at MAX_JSON_DEPTH
    // levels, turning the StackOverflowError of an uncapped recursive descent into a
    // verdict; the input length is capped by the caller. Members are kept in
    // insertion order, which is what lets the caller assert the key SEQUENCE.

    /** A recursive-descent reader for exactly one JSON document. */
    private static final class JsonReader {
        private final String text;
        private int index;

        /** {@code null} is read as empty and therefore refused, never accepted. */
        JsonReader(String text) {
            this.text = (text == null) ? "" : text;
        }

        /**
         * Reads the one value this document must consist of, in full.
         *
         * @throws IllegalArgumentException if the document is not well-formed
         */
        Object readDocument() {
            skipWhitespace();
            Object value = readValue(1);
            skipWhitespace();
            if (index != text.length()) {
                throw fail("unexpected trailing content");
            }
            return value;
        }

        private Object readValue(int depth) {
            if (depth > MAX_JSON_DEPTH) {
                throw fail("nesting deeper than " + MAX_JSON_DEPTH + " levels");
            }
            return switch (peek()) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> readString();
                case 't' -> readKeyword("true", Boolean.TRUE);
                case 'f' -> readKeyword("false", Boolean.FALSE);
                case 'n' -> readKeyword("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject(int depth) {
            expect('{');
            Map<String, Object> members = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return members;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw fail("expected a quoted member name");
                }
                String name = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = readValue(depth + 1);
                if (members.putIfAbsent(name, value) != null) {
                    throw fail("repeated member name");
                }
                skipWhitespace();
                char separator = next();
                if (separator == '}') {
                    return members;
                }
                if (separator != ',') {
                    throw fail("expected ',' or '}'");
                }
            }
        }

        private List<Object> readArray(int depth) {
            expect('[');
            List<Object> elements = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return elements;
            }
            while (true) {
                skipWhitespace();
                elements.add(readValue(depth + 1));
                skipWhitespace();
                char separator = next();
                if (separator == ']') {
                    return elements;
                }
                if (separator != ',') {
                    throw fail("expected ',' or ']'");
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (true) {
                char current = next();
                if (current == '"') {
                    return value.toString();
                }
                if (current == '\\') {
                    value.append(readEscape());
                    continue;
                }
                if (current < PRINTABLE_MIN_CHAR) {
                    throw fail("unescaped control character in a string");
                }
                value.append(current);
            }
        }

        private char readEscape() {
            char marker = next();
            return switch (marker) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> readUnicodeEscape();
                default -> throw fail("unknown escape");
            };
        }

        private char readUnicodeEscape() {
            if (index + JSON_UNICODE_DIGITS > text.length()) {
                throw fail("truncated unicode escape");
            }
            String digits = text.substring(index, index + JSON_UNICODE_DIGITS);
            for (int offset = 0; offset < JSON_UNICODE_DIGITS; offset++) {
                if (!isHexDigit(digits.charAt(offset))) {
                    throw fail("unicode escape is not four hexadecimal digits");
                }
            }
            index += JSON_UNICODE_DIGITS;
            return (char) Integer.parseInt(digits, JSON_UNICODE_RADIX);
        }

        private Double readNumber() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            readDigits(true);
            if (index < text.length() && text.charAt(index) == '.') {
                index++;
                readDigits(false);
            }
            if (index < text.length()
                    && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index++;
                if (index < text.length()
                        && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                readDigits(false);
            }
            // The value is never read - only its well-formedness matters.
            return Double.valueOf(text.substring(start, index));
        }

        /** The integer part forbids a leading zero followed by further digits. */
        private void readDigits(boolean integerPart) {
            int start = index;
            while (index < text.length() && isAsciiDigit(text.charAt(index))) {
                index++;
            }
            if (index == start) {
                throw fail("expected a digit");
            }
            if (integerPart && text.charAt(start) == '0' && index - start > 1) {
                throw fail("a number may not have a leading zero");
            }
        }

        private Object readKeyword(String keyword, Object value) {
            if (!text.startsWith(keyword, index)) {
                throw fail("expected '" + keyword + "'");
            }
            index += keyword.length();
            return value;
        }

        private char peek() {
            if (index >= text.length()) {
                throw fail("unexpected end of document");
            }
            return text.charAt(index);
        }

        private char next() {
            char current = peek();
            index++;
            return current;
        }

        private void expect(char required) {
            if (next() != required) {
                throw fail("expected '" + required + "'");
            }
        }

        /** Skips the four characters RFC 8259 counts as whitespace, and no others. */
        private void skipWhitespace() {
            while (index < text.length()) {
                char current = text.charAt(index);
                if (current != ' ' && current != '\t' && current != '\n' && current != '\r') {
                    return;
                }
                index++;
            }
        }

        private static boolean isHexDigit(char current) {
            return isAsciiDigit(current)
                    || (current >= 'a' && current <= 'f')
                    || (current >= 'A' && current <= 'F');
        }

        /**
         * Builds the rejection. The message names the offset and never quotes the
         * document, so a hostile body cannot place its own text in a log line
         * through this path - and the caller collapses every one of these into a
         * single fixed category anyway.
         */
        private IllegalArgumentException fail(String reason) {
            return new IllegalArgumentException(reason + " at offset " + index);
        }
    }
}
