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
 * JDK only. The listener is a {@code com.sun.net.httpserver.HttpServer} from the
 * jdk.httpserver module and the self-check uses java.net.http, and both modules
 * belong to the standard module set - jdk.httpserver requires nothing but
 * java.base and exports com.sun.net.httpserver - so no --add-modules flag, no
 * classpath addition, no build tool and no third-party library is needed
 * anywhere, for the application or for its tests. The JDK ships no JSON
 * serializer, and that is the one place this implementation differs in mechanism
 * from its Python and JavaScript siblings: the payload is assembled by hand
 * through an explicit escape helper, and the result is byte-identical to theirs
 * for identical configuration.
 *
 * THE LISTENER, AND THE THREE DETAILS THE JDK SERVER DECIDES FOR ITSELF
 * --------------------------------------------------------------------
 * The listener is com.sun.net.httpserver.HttpServer, registered with a single
 * root context so that every request reaches this class's own handler and is
 * routed by the shared normalisation rules below rather than by the server's
 * longest-prefix context matching. Its default executor would run every exchange
 * on the single dispatcher thread, letting one stalled client block every other,
 * so it is replaced with a virtual-thread-per-task executor.
 *
 * Three response details are written by the server and cannot be reached from
 * application code. Each was established by execution rather than by reading,
 * and each is recorded here because these are the only points where this
 * implementation's bytes differ from app.py's and index.js's:
 *   1. A Date response header is always present. Setting the field to a sentinel
 *      value has it overwritten with the real date, and removing it after
 *      sendResponseHeaders still sends it, because the server writes the field
 *      itself immediately before the header block reaches the wire. RFC 9110
 *      section 6.6.1 says an origin server SHOULD send Date, so the extra field
 *      is conformant, and it discloses nothing about the runtime, so the least
 *      disclosure property below is unaffected.
 *   2. Response field names are normalised, so this server emits "Content-type"
 *      where the other two emit "Content-Type". RFC 9110 makes field names
 *      case-insensitive, so this is a difference in bytes and not in meaning,
 *      which is why every assertion against these responses folds case.
 *   3. A target of //health is answered 404 by the server itself, with an HTML
 *      body, before this handler is reached: it parses //health as a network-path
 *      reference whose authority is "health" and whose path is empty, so no
 *      context matches. The status is the 404 the contract requires and the body
 *      is a fixed string that echoes nothing from the request; only the media
 *      type of that one error body differs from the other two implementations.
 * The same server also answers a request line it cannot parse, and a request
 * carrying more header fields than it accepts, without reaching this handler.
 * Those answers are equally fixed strings that reflect no part of the request, so
 * they disclose nothing; they are outside the contract because the contract
 * enumerates three statuses and none of them is a transport rejection.
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
 *   Headers set here      Content-Type: application/json
 *                         Cache-Control: no-cache, no-store, must-revalidate
 *                         Content-Length, from the encoded byte length
 *                         Allow: GET, on the 405 response only
 *
 * Those three statuses are the only ones this endpoint produces. Every response
 * additionally carries the Date field described above, and an HTTP/1.0 request
 * additionally receives the Connection: close the server adds because it will not
 * hold such a connection open; neither field is set here and neither can be
 * suppressed. No Server banner is emitted at all, by this class or by the server.
 * A response to HEAD carries the same four fields as the equivalent response to
 * any other refused method - Content-Length included, which is why that field is
 * set explicitly below rather than left to the server - and no body.
 *
 * The query string is stripped before matching and one optional trailing slash
 * is accepted, so /health, /health/ and /health?x=1 all reach the endpoint.
 * Nothing else is forgiven: the request target is matched verbatim, with no
 * percent-decoding, no dot-segment resolution and no collapsing of repeated
 * leading slashes, so //health, /health%2f and /health/../health are all 404
 * exactly as they are in the other two implementations - with the single caveat
 * that //health receives the server's own 404 rather than this endpoint's, as
 * detail 3 above records.
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
 * Every request that reaches this class's handler is graded in exactly this
 * order, and the first rule that fires decides the response. The order is what
 * makes the three implementations agree on requests that break more than one rule
 * at once.
 *   1. Any method other than the exact token GET -> 405 with Allow: GET. This is
 *      total: HEAD, OPTIONS, CONNECT, TRACE, PROPFIND and any unknown token all
 *      reach it, and none of them is ever echoed back to the caller.
 *   2. Otherwise the normalised target either equals the configured route -> 200
 *      with the health document, or it does not -> 404.
 * Method comparison is case-sensitive, as RFC 9110 requires: "get" is not GET and
 * is answered 405 like any other unknown token.
 *
 * CONCURRENCY AND CONNECTION LIFETIME
 * -----------------------------------
 * One named virtual thread per exchange, handed out by the executor installed on
 * the server, dispatched by the server's own non-daemon HTTP-Dispatcher thread.
 * That thread is also what keeps the process alive in --serve mode: main returns
 * as soon as the listener is up, and the JVM stays running until the dispatcher
 * stops. A stalled or half-open connection therefore costs one parked virtual
 * thread and nothing else; it cannot delay any other client. Every request body
 * is drained, bounded at {@value #MAX_REQUEST_DRAIN_BYTES} bytes, before the
 * response is written: bytes left unread make the kernel answer the close with a
 * reset instead of letting the client read the reply, which was reproduced with a
 * one-mebibyte POST and disappears once the body is drained.
 *
 * LEAST DISCLOSURE
 * ----------------
 * The payload carries exactly the four required fields and nothing else. Error
 * bodies are fixed strings: the requested path, the request method and any
 * exception detail are never echoed to a caller, only to stderr. No
 * interpreter, framework or server banner is exposed.
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

    /** The payload's first key, and the first key the self-check requires. */
    private static final String PAYLOAD_KEY_NAME = "name";

    /** The payload's second key. */
    private static final String PAYLOAD_KEY_VERSION = "version";

    /** The payload's third key, the only non-deterministic one. */
    private static final String PAYLOAD_KEY_TIMESTAMP = "timestamp";

    /** The payload's fourth key, whose value must be {@value #STATUS_UP}. */
    private static final String PAYLOAD_KEY_STATUS = "status";

    /**
     * The four payload keys, in the one order the contract freezes them in.
     *
     * <p>Order is part of the contract, not an artifact of how the document is
     * built: the self-check compares a parsed document's key sequence against this
     * list, so a body carrying the right four keys in the wrong order is refused.
     * app.py holds the same tuple and index.js the same array.
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
     * The method whose response carries a header block but no body.
     *
     * <p>Named because {@link #sendResponse} has to recognise it: the server needs
     * to be told that no body follows, and telling it that is also what suppresses
     * the warning it logs when a body length is declared for a HEAD request.
     */
    private static final String METHOD_HEAD = "HEAD";

    /**
     * The four response fields this class sets, and their values.
     *
     * <p>They are set through the exchange's header map rather than written as
     * bytes, so the server chooses their casing and their order on the wire; RFC
     * 9110 makes field names case-insensitive, so that choice changes no meaning.
     *
     * <p>{@code Content-Length} is set explicitly even though the server derives
     * the same value from the byte count handed to {@code sendResponseHeaders}. It
     * has to be, for one response: on a HEAD request the server omits the field
     * entirely unless it is already in the map, and RFC 9110 section 9.3.2 asks a
     * HEAD response to carry the same fields a GET response would. Setting it here
     * is what keeps this implementation's HEAD header set equal to app.py's and
     * index.js's instead of one field short.
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

    // -------------------------------------------------------------------------
    // Server, probe and formatting limits
    // -------------------------------------------------------------------------

    /** The path every normalisation falls back to, and the route prefix. */
    private static final String ROOT_PATH = "/";

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
     * Name prefix for the virtual threads that run exchanges.
     *
     * <p>Named rather than anonymous so that a thread dump taken from a running
     * container tells an operator which threads belong to this endpoint. The
     * server's own dispatcher thread is named by the JDK, not here, and it is the
     * non-daemon thread that keeps --serve mode alive.
     */
    private static final String WORKER_THREAD_PREFIX = "health-worker-";

    /** First index handed to the worker thread factory. */
    private static final long WORKER_THREAD_START = 0L;

    /** Name of the shutdown hook thread that closes the listening socket. */
    private static final String SHUTDOWN_THREAD_NAME = "health-shutdown";

    /**
     * Seconds the server may spend waiting for exchanges in flight when stopping.
     *
     * <p>Zero, deliberately: the endpoint's own responses are written in
     * microseconds, so there is nothing worth waiting for, and a container stop
     * should release the port immediately rather than hold it for a grace period
     * no health response needs.
     */
    private static final int STOP_DELAY_SECONDS = 0;

    /** Buffer size used when draining a request body. */
    private static final int DRAIN_BUFFER_BYTES = 8192;

    /**
     * The length {@code sendResponseHeaders} is given when no body will follow.
     *
     * <p>The server's documented sentinel for "header block only". It is used for
     * exactly one case, a response to HEAD, and using it there is what keeps the
     * runtime from logging a warning about a declared body length on a method that
     * carries none.
     */
    private static final long NO_RESPONSE_BODY = -1L;

    /** Connect and read budget for the self-check; it must fail fast, not hang. */
    private static final int PROBE_TIMEOUT_SECONDS = 3;

    /** Loopback target used by the self-check. */
    private static final String LOOPBACK_HOST = "127.0.0.1";

    /** IPv4 wildcard, which is not a routable target for the self-check. */
    private static final String WILDCARD_HOST_V4 = "0.0.0.0";

    /** IPv6 wildcard, which is likewise not a routable target. */
    private static final String WILDCARD_HOST_V6 = "::";

    /** Bracketed IPv6 wildcard, the form an authority carries it in. */
    private static final String WILDCARD_HOST_V6_BRACKETED = "[::]";

    /** The shorthand some tooling writes for "every interface". */
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

    /** Number of dot-separated octets in an IPv4 literal. */
    private static final int IPV4_OCTET_COUNT = 4;

    /** Most decimal digits an IPv4 octet may carry. */
    private static final int IPV4_OCTET_MAX_DIGITS = 3;

    /** Largest value an IPv4 octet may hold. */
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

    /** Hexadecimal digits in a JSON {@code \\uXXXX} escape. */
    private static final int JSON_UNICODE_DIGITS = 4;

    /** Radix of a JSON {@code \\uXXXX} escape. */
    private static final int JSON_UNICODE_RADIX = 16;

    /** Name of the request field the self-check sends, for parity with app.py. */
    private static final String HEADER_ACCEPT = "Accept";

    /** Thread name prefix for the bounded exchange the self-check runs. */
    private static final String PROBE_THREAD_NAME = "health-probe";

    // -------------------------------------------------------------------------
    // Input grammars
    //
    // Every configured value that is not free text is matched against a grammar
    // written out here, and the same three grammars appear in app.py and index.js.
    // Writing them out is the point: the platform's own conversions are each
    // lenient in a different direction - Integer.parseInt accepts a Unicode digit
    // that Number() refuses, Python's int() accepts an underscore separator that
    // neither of the others does - so three implementations that each trusted their
    // runtime would disagree about the same configuration file.
    // -------------------------------------------------------------------------

    /**
     * A port as all three implementations accept one: ASCII decimal, sign optional.
     *
     * <p>This gate is what makes {@link Integer#parseInt(String)} safe to use here.
     * That method delegates to {@link Character#digit(char, int)}, which accepts
     * every Unicode decimal digit, so {@code JAVA_PORT} written in Arabic-Indic
     * digits would otherwise bind a port that {@code index.js} refuses outright -
     * one configuration file, two behaviours. Matched before any conversion runs.
     */
    private static final Pattern PORT_GRAMMAR = Pattern.compile("^[+-]?[0-9]+$");

    /** A three-part dotted numeric version, the only form {@code app.version} may take. */
    private static final Pattern VERSION_GRAMMAR =
            Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");

    /**
     * A whole-second UTC instant, the only form the {@code timestamp} field may take.
     *
     * <p>Used by the self-check to grade the field by FORMAT and never by value,
     * which is what keeps the one non-deterministic field in the payload from
     * making a gate time-flaky.
     */
    private static final Pattern TIMESTAMP_GRAMMAR = Pattern.compile(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$");

    /** Lowest byte a request target may carry: every control character is below it. */
    private static final char TARGET_MIN_CHAR = 0x21;

    /** Highest byte a request target may carry: DEL and everything above are out. */
    private static final char TARGET_MAX_CHAR = 0x7E;

    /** Lowest character a configured value may carry; below this is a control character. */
    private static final char PRINTABLE_MIN_CHAR = 0x20;

    /** DEL, a control character despite sitting above the printable range. */
    private static final char DEL_CHAR = 0x7F;

    /**
     * Upper bound on request-body bytes drained before a response is written.
     *
     * <p>The endpoint never inspects a body, but bytes left unread make the kernel
     * answer the close with a reset instead of letting the client read the reply:
     * a one-mebibyte POST that is not drained is observed by the client as
     * "connection reset by peer" rather than as the 405 it was sent. Eight
     * mebibytes is far above any body a health request could plausibly carry, so
     * every realistic request is drained in full and answered normally; a body
     * beyond the cap is still answered, and the server retires that connection
     * itself because its framing can no longer be trusted.
     */
    private static final long MAX_REQUEST_DRAIN_BYTES = 8L * 1024L * 1024L;

    /**
     * The platform property bounding how long one request may take to arrive, and
     * the budget installed into it.
     *
     * <p>Draining the request body is a BLOCKING read and {@code HttpServer}
     * applies no request-time limit of its own, so without this the ceiling above
     * bounds only how MUCH is read, never how LONG the read waits. A client that
     * promises a hundred body bytes, sends three and then says nothing holds a
     * handler thread for the lifetime of the process; verified by execution, such a
     * connection was still unanswered forty seconds later. A peer that opens many
     * of them retains a thread for each.
     *
     * <p>This is the platform's own documented knob for exactly that, listed among
     * the configurable properties of {@code com.sun.net.httpserver}, and it bounds
     * the header read as well as the body - which a timeout wrapped around the
     * drain alone would not. The budget matches the JavaScript listener's
     * {@code requestTimeout}, so one number governs the same behaviour in both.
     *
     * <p>The three implementations bound the same hazard and reach it differently,
     * which is a property of the platform rather than of the contract: Node answers
     * immediately and discards the unread body afterwards, Python answers when its
     * own drain budget expires, and this server reaps the connection unanswered.
     * The frozen contract governs well-formed requests, and every one of those is
     * answered identically by all three.
     */
    private static final String MAX_REQUEST_TIME_PROPERTY = "sun.net.httpserver.maxReqTime";
    private static final String MAX_REQUEST_TIME_SECONDS = "15";

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
     * <p>Two candidates are tried in a fixed order, and they are exactly the two
     * candidates app.py and index.js use: the file sitting beside this class's own
     * code source, then the file in the working directory. The first is the one
     * that matters - app.py resolves the file relative to {@code __file__} and
     * index.js relative to {@code __dirname}, so resolving it only against the
     * working directory would make Java the single implementation that loses its
     * configuration when the process is started from another directory - and the
     * second is what answers when the code source cannot be expressed as a
     * filesystem path at all.
     *
     * <p>There is deliberately <em>no</em> environment variable that names an
     * arbitrary properties file. The configuration surface is shared, and it is
     * shared exactly: the seven keys of {@value #CONFIG_FILE} and the environment
     * variables that override them are the same in all three implementations, so
     * adding a Java-only variable here would let this implementation serve
     * metadata its siblings could never serve, from a file they would never read.
     * Every value in that file is already overridable one key at a time through the
     * variables documented in {@code .env.example}, which is the portable way to
     * change what this endpoint reports.
     *
     * @return the path to read, or {@code null} if no candidate can be formed
     */
    private static Path configLocation() {
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
        // The grammar is matched BEFORE any conversion, and that ordering is the
        // fix rather than a formality. Integer.parseInt delegates to
        // Character.digit, which accepts every Unicode decimal digit, so a port
        // written in Arabic-Indic digits parses here and is refused outright by
        // index.js - one configuration file producing two behaviours, which is
        // exactly the class of divergence the shared grammar exists to close.
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
    // Configuration validation
    //
    // Resolution answers "what did the operator ask for". Validation answers "may
    // that be published", and it runs before a socket is bound and before a probe
    // is sent. Without it a control character in app.name reached the response body
    // and a version of "not-a-version" was served with a 200 and a status of UP -
    // an endpoint asserting its own health while carrying a payload no consumer of
    // the frozen contract could accept. Failing closed here is the only way the
    // 200 can be trusted at all.
    //
    // The messages name the KEY and never quote the VALUE. That is deliberate and
    // it is what lets the probe print one of these messages verbatim: the offending
    // value is a configured input, so a message that quoted it would carry that
    // input into a log line. The one exception is the port, whose refusal names the
    // value it refused - see resolvePort and probe(Config) - because a mistyped port
    // an operator cannot trace back to what they typed is only half a diagnostic.
    // -------------------------------------------------------------------------

    /**
     * Reports whether a value is usable as single-line configured text.
     *
     * <p>The rule for the two configured values that are neither a route nor a
     * number: non-empty, and free of every character below
     * {@value #PRINTABLE_MIN_CHAR} as well as DEL. Emptiness is a fault rather than
     * a fallback because these values reach the response body, and a name that is
     * absent from the payload is not the same document as one that is present and
     * blank.
     *
     * @param text the configured value to test; {@code null} is not usable
     * @return {@code true} when the value may be published
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
     * character, DEL and everything above it. A configured path carrying CR or LF
     * is what a log-forgery attempt looks like, and it is refused here before a
     * request is ever built from it.
     *
     * @param candidate the configured route to test; {@code null} is not usable
     * @return {@code true} when the value may be requested
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
     * <ul>
     *   <li>{@code app.name} is non-empty text with no control character - it is a
     *       payload field, and a control character in it would forge a line in a
     *       consumer's log as readily as in this program's own.</li>
     *   <li>{@code app.version} matches {@link #VERSION_GRAMMAR} - the frozen
     *       contract states the field is a three-part dotted number, so serving
     *       anything else with a 200 and a status of UP is a lie the consumer
     *       cannot detect.</li>
     *   <li>{@code health.path} starts with {@code /} and is a valid request
     *       target - a route that cannot be requested is a route no probe and no
     *       orchestrator can ever reach.</li>
     *   <li>{@code app.host} is non-empty text with no control character - it
     *       reaches the startup banner and the probe's target URL.</li>
     * </ul>
     *
     * <p>The port is deliberately NOT checked here: it is already refused at
     * resolution time by {@link #resolvePort}, which is the earliest point it is
     * knowable, and checking it twice would put the same refusal behind two
     * different messages.
     *
     * @param config the resolved snapshot to check
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
        String route = config.healthPath();
        if (route == null || !route.startsWith(ROOT_PATH) || !isRequestTarget(route)) {
            throw new IllegalArgumentException(
                    "invalid health.path: it is not a valid request target");
        }
        if (!isSingleLineText(config.host())) {
            throw new IllegalArgumentException(
                    "invalid app.host: it must be non-empty text with no control character");
        }
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

    // -------------------------------------------------------------------------
    // Request handling
    //
    // One handler answers every exchange the server accepts. It is registered on
    // the root context rather than on the configured route, so the routing rules
    // above - and not the server's longest-prefix context matcher - decide what a
    // near-miss target means, which is what keeps the 404 body identical to the
    // one app.py and index.js produce.
    // -------------------------------------------------------------------------

    /**
     * Answers one exchange according to the frozen contract.
     *
     * <p>The configuration snapshot is captured when the server is created and
     * passed in here, so no request ever touches the filesystem and two responses
     * from one server can never disagree about what they were serving.
     *
     * <p>The two contract headers are set before the status is chosen, because
     * every one of the three responses carries both: a health answer that could be
     * cached is worse than no health answer at all, so the no-store directive is
     * never conditional. {@code Allow: GET} is added on the 405 alone.
     *
     * <p>The target is taken from {@code getRequestURI().toString()} rather than
     * from {@code getRawPath()}. That is deliberate and it is what keeps this
     * implementation's routing identical to its siblings': the full request target
     * still carries the query string and, for an absolute-form request line, the
     * scheme and authority, so {@link #normalisePath(String)} performs exactly the
     * same four transformations here that it performs on the raw target in app.py
     * and index.js. Taking the pre-parsed path instead would have the server strip
     * the query and the authority first, silently moving two of those rules out of
     * this class and out of reach of the tests that cover them.
     *
     * <p>A {@link RuntimeException} is caught, logged to stderr and swallowed. It
     * is unreachable in practice - the payload builder and the path normaliser are
     * total functions over their inputs - but a handler that let one escape would
     * take down nothing at all while leaving an operator no record of it, and the
     * contract defines no 5xx response to send instead.
     *
     * @param exchange the exchange to answer; always closed before this returns
     * @param config   the snapshot every response from this server is built from
     * @throws IOException if the response cannot be written to the connection
     */
    private static void handle(HttpExchange exchange, Config config) throws IOException {
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
            if (config.healthPath().equals(requested)) {
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
     * <p>The endpoint never inspects a body, but it must not leave one unread:
     * bytes still queued when the connection closes make the kernel answer with a
     * reset, so the client sees "connection reset by peer" instead of the response
     * that was written for it. Reproduced with a one-mebibyte POST, which is
     * answered 405 normally once the body is drained and reset without this method.
     *
     * <p>The read is capped at {@value #MAX_REQUEST_DRAIN_BYTES} bytes so that a
     * deliberately endless body cannot hold a thread forever. Closing the stream
     * afterwards is what lets the server decide whether the connection is still
     * usable, and it is the reason a body beyond the cap costs nothing but that one
     * connection.
     *
     * @param exchange the exchange whose request body is to be discarded
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
     * <p>{@code Content-Length} is declared in the header map as well as through
     * the length argument, and the two carry the same value. The duplication earns
     * its place on the response to HEAD: the server omits the field it would
     * otherwise derive once it knows the method carries no body, and RFC 9110
     * section 9.3.2 asks a HEAD response to carry the same fields the equivalent
     * GET response would. A pre-set field is honoured, so declaring it here is what
     * makes the HEAD header set equal to app.py's and index.js's rather than one
     * field short of them.
     *
     * <p>The length argument, in contrast, becomes {@value #NO_RESPONSE_BODY} for a
     * HEAD request, which is the server's sentinel for "header block only". Passing
     * a real length instead still produces a correct response - the server
     * substitutes a discarding sink - but it also makes the runtime log a warning
     * about a body length declared for a method that has no body, and a mode of
     * this program that writes an unexpected diagnostic is a mode that fails a
     * clean-output check.
     *
     * <p>Writing the body is unconditional even so. The discarding sink makes it
     * harmless, and one write path rather than two is one fewer place for the
     * bodied and bodiless responses to drift apart.
     *
     * @param exchange the exchange to respond on
     * @param status   HTTP status code
     * @param body     complete response body, already compact JSON
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

    // -------------------------------------------------------------------------
    // Server lifecycle
    // -------------------------------------------------------------------------

    /**
     * A listening health endpoint: one bound server, one virtual thread per exchange.
     *
     * <p>A thin lifecycle wrapper around {@link HttpServer}. It exists for three
     * reasons rather than as ceremony: it carries the configuration snapshot beside
     * the listener so that every response is built from the same values, it owns the
     * executor so that stopping the server also releases the threads that were
     * serving it, and it makes {@link #stop()} idempotent, which the raw server is
     * not obliged to be and which matters because stop is reachable both from a
     * shutdown hook and from a caller that stops the server itself.
     *
     * <p>The socket is bound by {@link User#createServer(Config)} before this object
     * exists, so {@link #port()} answers the real port - including the one the
     * operating system chose for an ephemeral bind - from the moment the server is
     * created and before it is started.
     *
     * <p>Exchanges run on virtual threads: a client that connects and then stalls
     * costs one parked virtual thread, a few hundred bytes, and no other client
     * waits behind it. Those threads are daemon threads and never delay shutdown.
     * What keeps a --serve process alive is the server's own non-daemon
     * HTTP-Dispatcher thread, and what lets that process exit is {@link #stop()},
     * which ends it.
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
         *
         * <p>Idempotent, because it is reachable both from a shutdown hook and from
         * a caller that stops the server itself; the guard is what makes a second
         * call a no-op rather than a second pass over a closed selector.
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
        // Validation runs before the bind, which is what makes the refusal total: a
        // configuration that could not be published truthfully never reaches a
        // listening socket, so there is no window in which a port is held by a
        // server that would answer 200 with a payload the contract forbids.
        validateConfig(config);
        // The request-time bound is installed before the first touch of the server
        // class, whose configuration is read once from a static initialiser: set it
        // afterwards and it is ignored. An operator who passed -D explicitly is
        // honoured rather than overridden.
        if (System.getProperty(MAX_REQUEST_TIME_PROPERTY) == null) {
            System.setProperty(MAX_REQUEST_TIME_PROPERTY, MAX_REQUEST_TIME_SECONDS);
        }
        // The executor is built before the bind, deliberately. A server that has
        // been created but never started does not release its port when it is
        // stopped - the socket is closed from the dispatcher thread, which does not
        // exist yet, and that was confirmed by execution - so there must be no step
        // between the bind and the return that could throw and leave a bound socket
        // with no owner. Everything fallible therefore happens first, and the two
        // calls that follow the bind cannot fail for these arguments: the context
        // path is a compile-time constant beginning with "/", the handler is never
        // null, and the executor is installed before the server is started.
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
        listener.createContext(CONTEXT_PATH, exchange -> handle(exchange, config));
        listener.setExecutor(workers);
        return new HealthServer(listener, config, workers);
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
     * <p>This method returns as soon as the listener is up; it does not block. What
     * keeps the process running afterwards is the server's own non-daemon dispatcher
     * thread, which is also what a stop has to end. A shutdown hook therefore calls
     * {@code stop} so that a container stop or a Ctrl-C closes the listening socket
     * and releases the port deterministically rather than leaving the JVM to be
     * killed with the port still held. The stop is immediate and does not wait for
     * exchanges in flight, which costs nothing worth having: a health response is
     * written in microseconds, so there is no work a grace period would rescue, and
     * an orchestrator that has decided to stop this container wants the port back.
     * Both failure modes are fatal and fail closed with a non-zero exit status and a
     * one-line diagnostic rather than a stack trace: an address that cannot be
     * bound, and a configured port that is not a port at all.
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
            // An interactive start names the value it refused. The operator is at
            // the terminal and the value came from their own environment, so
            // tracing the refusal back to what they typed is worth more here than
            // withholding it - the probe path, which runs unattended, is the one
            // that reports the same fault as a bare category.
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
                    + server.port() + config.healthPath());
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
     * <p>The single emitter for every diagnostic this program produces. It exists
     * because sanitising at each call site is a rule that holds only until the next
     * call site is added: before this method every {@code LOG_PREFIX} write but one
     * bypassed the sanitiser, so a carriage return in a configured value or in an
     * exception message could forge a second log line. Routing all of them through
     * one method makes the property structural instead of a convention.
     *
     * <p>stderr rather than stdout is not a style choice either: the default mode's
     * stdout is hashed byte for byte by the backward-compatibility gate, so stdout
     * carries the one preserved line and nothing else, ever.
     *
     * @param message the diagnostic text, already prefix-free
     */
    private static void logWarning(String message) {
        System.err.println(LOG_PREFIX + sanitiseForLog(message));
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
    //
    // This is the mode a container runs on a timer, forever, and its exit status is
    // the only thing the runtime looks at, so every property below is load-bearing
    // rather than defensive decoration.
    //
    //   * It is a PARSE, not a substring test. The defect this replaces graded a
    //     body healthy whenever it contained the bytes "status":"UP" anywhere, so a
    //     truncated document with no closing brace, a document with the fragment
    //     buried inside another field's value, and any unrelated body that happened
    //     to carry those bytes all reported a broken application as healthy - to a
    //     runtime whose only remedy is a restart it would then never perform.
    //   * It targets LOOPBACK ONLY, from an allowlist. app.host is an input; a
    //     probe that honoured it verbatim could be aimed at a third party, whose
    //     healthy answer would then vouch for this process.
    //   * It uses NO PROXY and follows NO REDIRECT, so nothing ambient can put
    //     another party between this process and its own endpoint.
    //   * It reads a BOUNDED body under an ABSOLUTE deadline, so neither an endless
    //     stream nor a listener that accepts and then trickles can exhaust or wedge
    //     the one component that runs forever on a schedule.
    //   * It NEVER throws and never writes to stdout. Every fault is a verdict.
    //
    // The rejection wording is identical in app.py, index.js and here, because an
    // operator greps one deployment's logs and not one language's.
    // -------------------------------------------------------------------------

    /**
     * Self-checks the endpoint described by the effective configuration.
     *
     * <p>Validates the configuration before it probes anything. A configuration
     * that could not be published truthfully cannot be proven healthy either, so
     * the same rules that refuse a start refuse a probe - which is what stops a
     * container from reporting itself healthy while serving a payload the frozen
     * contract forbids.
     *
     * @return {@value #EXIT_SUCCESS} when healthy, {@value #EXIT_FAILURE} otherwise
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
     * client offers do not. A connect timeout expires before a connection exists and
     * a request timeout expires before the response headers arrive; neither bounds
     * the body read that follows, so a listener that accepted the connection,
     * answered 200 and then sent one byte a minute would hold this method open for
     * as long as it liked. Running the exchange on one virtual thread and bounding
     * it with a single {@code get} is what makes the whole thing finish.
     *
     * @param host       host the server was bound to; a wildcard is probed over loopback
     * @param port       port the server is listening on
     * @param healthPath route to request
     * @return {@value #EXIT_SUCCESS} when healthy, {@value #EXIT_FAILURE} otherwise
     */
    static int probe(String host, int port, String healthPath) {
        String route = normalisePath(healthPath);
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

    /**
     * Runs one bounded exchange and turns its answer into a verdict.
     *
     * @param uri    the loopback URL to request
     * @param target the same URL as text, for the one diagnostic that names it
     * @return {@value #EXIT_SUCCESS} when healthy, {@value #EXIT_FAILURE} otherwise
     */
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

    /**
     * One endpoint answer: the status code and the bounded body bytes.
     *
     * @param status HTTP status code the endpoint answered with
     * @param body   the body as read, at most one byte past the probe ceiling
     */
    private record Answer(int status, byte[] body) { }

    /**
     * Converts a configured bind address into a loopback destination.
     *
     * <p>This is an ALLOWLIST, and that is the whole point. {@code app.host} is an
     * input: before this method a configured host was honoured verbatim, so a probe
     * could be aimed at any address at all and a third party's healthy answer would
     * vouch for this process. The only destinations reachable now are the loopback
     * interface and nothing else.
     *
     * <table border="1">
     * <caption>The complete mapping</caption>
     * <tr><th>Configured {@code app.host}</th><th>Probe destination</th></tr>
     * <tr><td>{@code null}, empty, blank</td><td>{@code 127.0.0.1}</td></tr>
     * <tr><td>{@code 0.0.0.0}, {@code ::}, {@code [::]}, {@code *}</td>
     *     <td>{@code 127.0.0.1}</td></tr>
     * <tr><td>{@code localhost}</td><td>{@code 127.0.0.1} - MAPPED, never resolved,
     *     so a hosts-file entry cannot redirect the probe</td></tr>
     * <tr><td>{@code ::1}, {@code [::1]}, {@code 0:0:0:0:0:0:0:1},
     *     {@code [0:0:0:0:0:0:0:1]}</td><td>{@code [::1]}</td></tr>
     * <tr><td>any address in {@code 127.0.0.0/8}</td><td>itself, as configured</td></tr>
     * <tr><td>anything else</td><td>{@code 127.0.0.1}, with one warning that does
     *     NOT echo the refused value</td></tr>
     * </table>
     *
     * <p>Matching is case-insensitive and trims surrounding whitespace, so the same
     * destination is reached however the value was written. The warning withholds
     * the refused host because it is a configured input, and a diagnostic that
     * quoted it would carry that input into a log line.
     *
     * @param host the configured bind address; {@code null} is accepted
     * @return {@code 127.0.0.1}, {@code [::1]}, or a 127.0.0.0/8 address as configured
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
     *
     * @param candidate the trimmed configured host
     * @return {@code true} only for {@code 127.b.c.d} with four octets in 0-255
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

    /**
     * Reports whether a value is a non-empty run of ASCII digits and nothing else.
     *
     * @param candidate the text to test
     * @return {@code true} for one or more characters, all of them 0-9
     */
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
     * <p>Package-private and free of any transport so that every rule below is
     * reachable by a direct call. The reason strings are part of the shared
     * contract - app.py's counterpart is module-level and index.js exports its own
     * for the same reason - because a reason observable only by reading stderr is a
     * reason that drifts unnoticed between three implementations.
     *
     * <p>The rules, in the order they are applied. The ORDER is part of the
     * contract: the cheapest and most fundamental checks come first, and the
     * {@code status} field is examined before the three descriptive fields so that
     * an endpoint reporting itself down is reported as DOWN rather than as whichever
     * of its other fields happened also to be wrong.
     * <ol>
     *   <li>the body fits inside {@value #MAX_PROBE_BODY_BYTES} bytes;</li>
     *   <li>the status is exactly 200 - the IETF health-check draft couples a
     *       passing status to a 2xx code and this contract narrows that to one;</li>
     *   <li>the body is UTF-8, is well-formed JSON, carries no repeated key and has
     *       nothing trailing it;</li>
     *   <li>the body is a JSON OBJECT;</li>
     *   <li>it carries exactly {@link #PAYLOAD_KEYS}, in that order;</li>
     *   <li>{@code status} equals {@value #STATUS_UP};</li>
     *   <li>{@code name} is a non-empty string, {@code version} matches
     *       {@link #VERSION_GRAMMAR}, and {@code timestamp} matches
     *       {@link #TIMESTAMP_GRAMMAR} - the timestamp by FORMAT, never by
     *       value, so no gate can become time-flaky.</li>
     * </ol>
     *
     * @param status HTTP status code the endpoint answered with
     * @param body   the body as read, at most one byte past the probe ceiling
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
     * <p>{@code new String(bytes, UTF_8)} silently substitutes a replacement
     * character for a malformed sequence, which would let a body that is not UTF-8
     * at all reach the reader as something that might parse. app.py's
     * {@code bytes.decode("utf-8")} raises on the same input, so reporting is what
     * keeps the two graders in step.
     *
     * @param body the bytes as read from the endpoint
     * @return the decoded text
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

    // -------------------------------------------------------------------------
    // A strict reader for one JSON document
    //
    // The JDK ships no JSON parser, so rather than add a dependency and lose the
    // repository's zero-dependency property, the self-check reads the one document
    // shape it has to grade. It is strict where a lenient reader would let a
    // hostile body through, and it refuses, among other things: a document with
    // trailing content after the first value; an unquoted or single-quoted member
    // name; an unescaped control character inside a string; an unknown escape; a
    // \\u escape that is not four hexadecimal digits; a number with a leading zero,
    // a leading plus, a bare decimal point or a missing exponent digit; a trailing
    // comma; a comment; and a REPEATED member name.
    //
    // The repeated name is refused rather than resolved, and that is a deliberate
    // divergence from both sibling runtimes' parsers. JSON.parse and json.loads
    // both keep the LAST value for a duplicated key and say nothing, so a body
    // carrying status twice would be graded on whichever value the parser kept -
    // a document with two answers is not a document this endpoint could ever have
    // produced, so all three implementations refuse it outright instead.
    //
    // Nesting is capped at MAX_JSON_DEPTH levels. A recursive-descent reader with
    // no cap answers ten thousand opening brackets with a StackOverflowError, which
    // is an error rather than a verdict; the cap turns it into a rejection. The
    // input length is capped by the caller before this reader ever sees it.
    //
    // Members are kept in insertion order, which is what lets the caller assert the
    // key SEQUENCE and not merely the key set.
    // -------------------------------------------------------------------------

    /** A recursive-descent reader for exactly one JSON document. */
    private static final class JsonReader {
        private final String text;
        private int index;

        /**
         * @param text the document to read; {@code null} is read as empty and
         *             therefore refused, never accepted by default
         */
        JsonReader(String text) {
            this.text = (text == null) ? "" : text;
        }

        /**
         * Reads the one value this document must consist of, in full.
         *
         * @return the parsed value: a {@code Map}, {@code List}, {@code String},
         *         {@code Double}, {@code Boolean} or {@code null}
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
            // The value is never read - only its well-formedness matters - but
            // producing it keeps the reader honest about what it accepted.
            return Double.valueOf(text.substring(start, index));
        }

        /**
         * @param integerPart {@code true} for the integer part, where the grammar
         *                    forbids a leading zero followed by further digits
         */
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
         *
         * @param reason what was expected or found
         * @return the exception for the caller to throw
         */
        private IllegalArgumentException fail(String reason) {
            return new IllegalArgumentException(reason + " at offset " + index);
        }
    }
}
