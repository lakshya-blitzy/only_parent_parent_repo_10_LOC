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
 * JDK only. com.sun.net.httpserver (module jdk.httpserver) serves and
 * java.net.http (module java.net.http) probes; both are part of the standard
 * module graph - jdk.httpserver exports com.sun.net.httpserver and requires
 * only java.base - so no --add-modules flag, no build tool and no third-party
 * library is needed anywhere, for the application or for its tests. The JDK
 * ships no JSON serializer, and that is the one place this implementation
 * differs in mechanism from its Python and JavaScript siblings: the payload is
 * assembled by hand through an explicit escape helper, and the result is
 * byte-identical to theirs for identical configuration.
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
 * The query string is stripped before matching and one optional trailing slash
 * is accepted, so /health, /health/ and /health?x=1 all reach the endpoint.
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
 * RUNTIME BEHAVIOUR THIS CODE CANNOT CONTROL (both verified by execution)
 * ----------------------------------------------------------------------
 *   * com.sun.net.httpserver writes its own Date response header. Setting or
 *     removing Date from a handler has no effect, so it cannot be suppressed
 *     from application code; RFC 9110 requires it of an origin server that has
 *     a clock in any case. This class adds neither a Date nor a Server header
 *     itself, and no Server header is emitted at all.
 *   * The same server normalises response header NAMES, emitting for instance
 *     "Content-type" rather than "Content-Type". Field names are
 *     case-insensitive per RFC 9110, so this is protocol-legal and is not
 *     correctable from here: every assertion against these headers must
 *     therefore be case-insensitive.
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;

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

    /** Properties file resolved relative to the working directory. */
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

    /** Guards {@link #cachedProperties}. */
    private static final Object CONFIG_LOCK = new Object();

    /**
     * Lazily loaded contents of the properties file, cached for the lifetime of
     * the process. Caching is safe because the other two configuration sources
     * cannot change underneath it: a JVM's environment is fixed at launch and
     * the built-in defaults are compile-time constants. It also keeps the
     * default mode free of any filesystem access, because nothing on that path
     * asks for configuration at all.
     */
    private static Properties cachedProperties;

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

    /** Value of the Allow header on the 405 response. */
    private static final String ALLOWED_METHODS = METHOD_GET;

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_CACHE_CONTROL = "Cache-Control";
    private static final String HEADER_ALLOW = "Allow";

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String CACHE_CONTROL_NO_STORE = "no-cache, no-store, must-revalidate";

    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_INTERNAL_ERROR = 500;

    /** Fixed error bodies: nothing about the request is ever reflected back. */
    private static final String BODY_NOT_FOUND = "{\"error\":\"Not Found\"}";
    private static final String BODY_METHOD_NOT_ALLOWED = "{\"error\":\"Method Not Allowed\"}";
    private static final String BODY_INTERNAL_ERROR = "{\"error\":\"Internal Server Error\"}";

    // -------------------------------------------------------------------------
    // Server, probe and formatting limits
    // -------------------------------------------------------------------------

    /** Root context: every request, including unknown paths, reaches us. */
    private static final String ROOT_CONTEXT = "/";

    /** 0 selects the JDK's default listen backlog. */
    private static final int SERVER_BACKLOG = 0;

    /** Response length that tells the JDK "no response body is being sent". */
    private static final int NO_RESPONSE_BODY = -1;

    /** What {@code HttpExchange.getResponseCode()} returns before headers go out. */
    private static final int RESPONSE_NOT_SENT = -1;

    /** Seconds granted to in-flight exchanges when the process is stopping. */
    private static final int SHUTDOWN_GRACE_SECONDS = 0;

    /** Connect and read budget for the self-check; it must fail fast, not hang. */
    private static final int PROBE_TIMEOUT_SECONDS = 3;

    /** Loopback target used by the self-check. */
    private static final String LOOPBACK_HOST = "127.0.0.1";

    /** IPv4 wildcard, which is not a routable target for the self-check. */
    private static final String WILDCARD_HOST_V4 = "0.0.0.0";

    /** IPv6 wildcard, which is likewise not a routable target. */
    private static final String WILDCARD_HOST_V6 = "::";

    /**
     * Upper bound on request-body bytes drained before the stream is closed.
     * The endpoint never inspects a body, but leaving bytes unread prevents
     * connection reuse; the cap stops an oversized body reaching memory.
     */
    private static final int MAX_REQUEST_DRAIN_BYTES = 8192;

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
     * Returns the parsed properties file, loading it once on first use.
     *
     * <p>The returned instance is empty - never {@code null} - when the file is
     * absent or unreadable, so callers always fall back to their built-in
     * defaults rather than failing.
     *
     * @return the cached file-backed configuration, possibly empty
     */
    static Properties configProperties() {
        synchronized (CONFIG_LOCK) {
            if (cachedProperties == null) {
                cachedProperties = loadProperties();
            }
            return cachedProperties;
        }
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
        String override = System.getenv(CONFIG_FILE_ENV);
        String location = (override == null || override.isEmpty()) ? CONFIG_FILE : override;
        try (BufferedReader reader = Files.newBufferedReader(Path.of(location), StandardCharsets.UTF_8)) {
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
     * top of the standard precedence order and refusing to fail on bad input.
     *
     * <p>A value that is not a number, or that falls outside the legal port
     * range, is discarded in favour of the built-in default: a health endpoint
     * that will not start because of one malformed setting is worse than one
     * listening on its documented default. The substitution is reported on
     * stderr so it is visible to an operator rather than silently surprising.
     *
     * @param props           file-backed configuration, may be {@code null}
     * @param key             properties key holding the language-specific port
     * @param envName         language-specific environment override
     * @param universalEnvName universal override, which outranks all others
     * @param fallback        built-in default port
     * @return a port in the range {@value #MIN_PORT}..{@value #MAX_PORT}
     */
    static int resolvePort(Properties props, String key, String envName,
            String universalEnvName, int fallback) {
        String universal = System.getenv(universalEnvName);
        String raw = (universal != null && !universal.isEmpty())
                ? universal
                : resolve(props, key, envName, Integer.toString(fallback));
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed >= MIN_PORT && parsed <= MAX_PORT) {
                return parsed;
            }
        } catch (NumberFormatException notANumber) {
            // Fall through to the built-in default reported below.
        }
        System.err.println(LOG_PREFIX + "unusable port value; falling back to " + fallback);
        return fallback;
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
        String configured = resolve(configProperties(), KEY_HEALTH_PATH,
                ENV_HEALTH_PATH, DEFAULT_HEALTH_PATH);
        String path = configured.startsWith(ROOT_CONTEXT) ? configured : ROOT_CONTEXT + configured;
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
     */
    static int javaPort() {
        return resolvePort(configProperties(), KEY_JAVA_PORT, ENV_JAVA_PORT,
                ENV_UNIVERSAL_PORT, DEFAULT_JAVA_PORT);
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
        return renderPayload(appName(), appVersion(), timestamp(), STATUS_UP);
    }

    // -------------------------------------------------------------------------
    // Request routing and response writing
    // -------------------------------------------------------------------------

    /**
     * Normalises a request path so that routing is forgiving but exact.
     *
     * <p>Any query string and any fragment are removed and one optional
     * trailing slash is dropped, so {@code /health}, {@code /health/} and
     * {@code /health?probe=1} all normalise to {@code /health}. Only one
     * trailing slash is tolerated: {@code /health//} does not match, because
     * accepting arbitrary slack would make the route ambiguous.
     *
     * @param rawPath path as received, possibly {@code null}
     * @return a normalised path that always starts with {@code /}
     */
    static String normalisePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return ROOT_CONTEXT;
        }
        String path = rawPath;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        if (path.length() > 1 && path.endsWith(ROOT_CONTEXT)) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? ROOT_CONTEXT : path;
    }

    /**
     * Writes one complete JSON response and nothing else.
     *
     * <p>The header set is identical for every status code this endpoint
     * produces, which is what lets a single assertion set be applied to the
     * success and the failure paths alike. {@code Content-Length} is supplied by
     * passing the encoded byte length - not the character count - to
     * {@code sendResponseHeaders}, so a multi-byte character can never desync
     * the length from the body.
     *
     * <p>A HEAD request receives the status line and headers with no body at
     * all, which is both what RFC 9110 requires of a HEAD response and what
     * this server needs in order to close the exchange cleanly.
     *
     * @param exchange the exchange to answer
     * @param status   HTTP status code to send
     * @param body     complete response body, already compact JSON
     * @param allow    value for the Allow header, or {@code null} to omit it
     * @throws IOException if the status line, headers or body cannot be written
     */
    private static void writeJson(HttpExchange exchange, int status, String body, String allow)
            throws IOException {
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON);
        headers.set(HEADER_CACHE_CONTROL, CACHE_CONTROL_NO_STORE);
        if (allow != null) {
            headers.set(HEADER_ALLOW, allow);
        }
        if (METHOD_HEAD.equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, NO_RESPONSE_BODY);
            return;
        }
        exchange.sendResponseHeaders(status, encoded.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(encoded);
        }
    }

    /**
     * Consumes and closes the request body without inspecting it.
     *
     * <p>Nothing this endpoint answers depends on a request body, but bytes left
     * unread keep a persistent connection from being reused, so the stream is
     * drained up to a fixed cap and then closed. The cap is what keeps an
     * oversized body from being pulled into memory by a request that had no
     * business carrying one.
     *
     * @param exchange the exchange whose request body should be discarded
     * @throws IOException if the stream cannot be read or closed
     */
    private static void drainRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream requestBody = exchange.getRequestBody()) {
            requestBody.readNBytes(MAX_REQUEST_DRAIN_BYTES);
        }
    }

    /**
     * Routes one request: the health document, a 404, or a 405.
     *
     * <p>Registered on the root context so that unknown paths are answered by
     * this method rather than by the JDK's own plain-text 404, which would carry
     * neither the JSON body nor the cache directives the contract requires.
     *
     * <p>Method matching is case-sensitive because RFC 9110 defines the method
     * token that way. The exchange is closed on every path, including the
     * failure paths, so no connection is leaked.
     *
     * @param exchange the exchange to answer
     * @throws IOException if the response cannot be written
     */
    private static void handle(HttpExchange exchange) throws IOException {
        try {
            drainRequestBody(exchange);
            if (!METHOD_GET.equals(exchange.getRequestMethod())) {
                writeJson(exchange, HTTP_METHOD_NOT_ALLOWED, BODY_METHOD_NOT_ALLOWED, ALLOWED_METHODS);
                return;
            }
            if (healthPath().equals(normalisePath(exchange.getRequestURI().getPath()))) {
                writeJson(exchange, HTTP_OK, healthPayload(), null);
            } else {
                writeJson(exchange, HTTP_NOT_FOUND, BODY_NOT_FOUND, null);
            }
        } catch (RuntimeException unexpected) {
            reportInternalFailure(exchange, unexpected);
        } finally {
            exchange.close();
        }
    }

    /**
     * Last-resort handler for a fault no request can legitimately provoke.
     *
     * <p>Every contract path above is fully handled, so reaching this method
     * means something genuinely unexpected happened. The caller learns only
     * that the request failed; the detail goes to stderr, never onto the wire.
     * The response is attempted only while the status line is still unsent -
     * {@code getResponseCode()} reports {@value #RESPONSE_NOT_SENT} until then -
     * because writing a second status line would corrupt the connection.
     *
     * @param exchange the exchange that failed
     * @param failure  the fault to report locally
     */
    private static void reportInternalFailure(HttpExchange exchange, RuntimeException failure) {
        System.err.println(LOG_PREFIX + "health request failed unexpectedly: " + failure);
        if (exchange.getResponseCode() != RESPONSE_NOT_SENT) {
            return;
        }
        try {
            writeJson(exchange, HTTP_INTERNAL_ERROR, BODY_INTERNAL_ERROR, null);
        } catch (IOException unwritable) {
            System.err.println(LOG_PREFIX + "error response could not be sent: " + unwritable);
        }
    }

    // -------------------------------------------------------------------------
    // Server lifecycle
    // -------------------------------------------------------------------------

    /**
     * Creates and binds a health server without starting it.
     *
     * <p>Passing {@code 0} as the port binds an ephemeral one, and the chosen
     * port is readable from {@code server.getAddress().getPort()} as soon as
     * this method returns, which is what lets a test run against a port that
     * cannot collide with anything else on the host.
     *
     * <p>The returned server is <strong>not</strong> listening yet: call
     * {@code start()} on it, or use {@link #startServer(String, int)} which does
     * both.
     *
     * @param host bind address; the wildcard address accepts every interface
     * @param port port to bind, or {@code 0} for an ephemeral port
     * @return a bound but not yet started server
     * @throws IOException if the address cannot be bound
     */
    static HttpServer createServer(String host, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), SERVER_BACKLOG);
        server.createContext(ROOT_CONTEXT, User::handle);
        // A null executor selects the JDK default, which runs exchanges on the
        // server's own dispatcher thread. That thread is not a daemon, so it is
        // what keeps the process alive in --serve mode, and stop() releases it,
        // so a test JVM that starts a server still terminates on its own.
        server.setExecutor(null);
        return server;
    }

    /**
     * Creates, binds and starts a health server.
     *
     * @param host bind address; the wildcard address accepts every interface
     * @param port port to bind, or {@code 0} for an ephemeral port
     * @return a server that is already listening
     * @throws IOException if the address cannot be bound
     */
    static HttpServer startServer(String host, int port) throws IOException {
        HttpServer server = createServer(host, port);
        server.start();
        return server;
    }

    /**
     * Serves the health endpoint from the effective configuration.
     *
     * <p>The startup line is written to stderr rather than stdout, and that is
     * not a stylistic choice: the default mode's stdout is asserted byte for
     * byte by the backward-compatibility gate, so this program keeps stdout
     * reserved for that single preserved line.
     *
     * <p>A shutdown hook stops the listener so that a container stop or a Ctrl-C
     * closes the socket in an orderly way instead of leaving the exchange in
     * flight. A bind failure is fatal and deliberately fails closed with a
     * non-zero exit status and a one-line diagnostic rather than a stack trace.
     */
    private static void serve() {
        String host = appHost();
        int port = javaPort();
        try {
            HttpServer server = startServer(host, port);
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> server.stop(SHUTDOWN_GRACE_SECONDS), "health-shutdown"));
            System.err.println(LOG_PREFIX + "health endpoint listening on http://" + host + ":"
                    + server.getAddress().getPort() + healthPath());
        } catch (IOException bindFailure) {
            System.err.println(LOG_PREFIX + "could not bind " + host + ":" + port + ": " + bindFailure);
            System.exit(EXIT_FAILURE);
        }
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
        return probe(appHost(), javaPort(), healthPath());
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
