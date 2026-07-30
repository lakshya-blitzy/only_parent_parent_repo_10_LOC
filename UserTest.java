/*
 * UserTest.java - the assertion harness for User.java (feature F-009).
 *
 * HOW TO RUN
 *   java UserTest.java              From the repository root, with no classpath,
 *                                   no build step and no test framework: the
 *                                   JDK's multi-file source launcher resolves
 *                                   and compiles the sibling User.java.
 *   javac -Xlint:all -d /tmp/out User.java UserTest.java
 *   java -cp /tmp/out UserTest      The compiled equivalent.
 *
 * Exit status is the whole contract: 0 when every check passed, 1 when any check
 * failed or anything unexpected was thrown. The final line reports how many
 * checks executed, so a caller can require that number to exceed zero - a
 * harness that silently runs nothing while exiting 0 is worse than none.
 *
 * ZERO DEPENDENCIES. JDK only, with no JUnit, TestNG, AssertJ or Hamcrest and no
 * Maven, Gradle or wrapper anywhere in the repository: a runner tree would
 * destroy the zero-dependency property the application itself preserves, so the
 * assertion engine below is roughly forty lines of plain Java. The HTTP client
 * the live-routing section uses is java.net.http from the standard module graph,
 * exactly as the application's own self-check uses it.
 *
 * DEFAULT UNNAMED PACKAGE - DELIBERATE. There is no package declaration, which
 * is what lets "java UserTest.java" resolve the sibling User class from source
 * and what makes User's package-private members reachable from here without any
 * of them being widened to public for testing.
 *
 * WHY A CHILD JVM, FOR SECTIONS A, E AND G. Three properties can only be
 * observed from outside the process under test. An EXIT STATUS is one: were the
 * default path ever to call System.exit(0), that call would terminate an
 * in-process assertion from inside it, printing no summary and reporting status
 * 0 - the worst failure mode a test can have. An ENVIRONMENT is the second: a
 * JVM's environment is fixed at launch and this harness will not use reflection
 * to forge one, so the environment layer of the precedence chain is exercised by
 * a child, whose environment is an argument. A PROCESS LIFECYCLE is the third:
 * "the default mode starts no listener" is a claim about a whole process, and a
 * child that bound a socket would not exit. A child is launched from source when
 * User.java can be located and from compiled classes otherwise; when NEITHER can
 * be located the harness records a counted FAILURE rather than skipping.
 *
 * TWO RULES THIS HARNESS IMPOSES ON ITSELF
 *   1. The timestamp is asserted by FORMAT and never by VALUE. It is the only
 *      non-deterministic field in the payload.
 *   2. Nothing here mutates the environment of THIS process, and no reflection is
 *      used to try. An override is passed to a child process instead.
 *
 * ENVIRONMENT VALUES AND DISCLOSURE. A process environment routinely carries
 * credentials, so the variables read here are named explicitly and never
 * discovered by scanning: a scan ordered by name could just as easily select an
 * API key. Only the application's own settings are read - APP_NAME, APP_VERSION,
 * HEALTH_PATH, APP_HOST, PORT and JAVA_PORT - and the first two are published in
 * the health response itself, so they may appear in a diagnostic. Every child
 * environment is built EMPTY and populated explicitly, so not even PATH is
 * inherited and nothing this harness starts can pick up a credential from it.
 *
 * EXPECTED OUTPUT THAT IS NOT A FAILURE. Several checks deliberately drive User
 * down a fail-closed path, and User reports each on stderr by design:
 *   [User] probe could not reach http://...            (sections F and G)
 *   [User] probe rejected http://...: status 404       (section G)
 *   [User] refusing to start: invalid port value: ...  (section G)
 *   [User] could not bind ...: java.net.BindException  (section G)
 * The first is announced on stdout immediately before it is provoked, so a reader
 * does not mistake an expected diagnostic for a real fault. The other three come
 * from CHILD processes whose streams this harness captures and asserts on, so
 * they never reach the log. Section E's unusable-port path is refused by an
 * exception rather than reported on a stream. See checkRejects.
 */
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assertion harness for {@link User}, run as a program rather than by a test
 * framework.
 *
 * <p>Exposes exactly one entry point, {@link #main(String[])}. The class holds no
 * instance state and is never instantiated; the file header above documents the
 * invocations, the exit-status contract and what each group of checks asserts.
 */
public class UserTest {
    /**
     * Not instantiable: this harness is a program, and every member it owns is
     * static. Declared explicitly so that the class cannot be constructed by
     * accident and so that it publishes no implicit no-argument constructor.
     */
    private UserTest() {
    }

    /**
     * Runs every section, prints a summary, and exits 0 only if nothing failed.
     *
     * <p>Each section is invoked through {@link #runSection(String, Section)} so
     * that an unexpected exception inside one section is recorded as a failure
     * and the remaining sections still run: a harness that abandons the run on
     * the first surprise hides the very information its operator needs. An outer
     * guard catches anything that escapes even that, so the process can never
     * end with an ambiguous status.
     *
     * @param args ignored; the harness takes no options, which keeps the single
     *             documented invocation {@code java UserTest.java} the only one
     */
    public static void main(String[] args) {
        try {
            runSection("A preserved legacy behaviour", UserTest::verifyPreservedBehaviour);
            runSection("B health payload contract", UserTest::verifyPayloadContract);
            runSection("C JSON escaping", UserTest::verifyJsonEscaping);
            runSection("D path normalisation", UserTest::verifyPathNormalisation);
            runSection("E configuration precedence", UserTest::verifyConfigurationPrecedence);
            runSection("F live routing over a socket", UserTest::verifyLiveRouting);
            runSection("G entry-point dispatch in a child JVM", UserTest::verifyEntrypointDispatch);
            runSection("H transport behaviour over a raw socket", UserTest::verifyRawTransport);
            runSection("I configuration validation and the port grammar",
                    UserTest::verifyConfigurationValidation);
            runSection("J probe answer validation and connection reuse",
                    UserTest::verifyProbeValidationAndReuse);
            runSection("K the shared properties grammar and the failure policy",
                    UserTest::verifySharedPropertiesGrammar);
            runSection("L probe identity and media-type verification",
                    UserTest::verifyProbeIdentity);
        } catch (RuntimeException unexpected) {
            // Defensive: runSection already contains every section, so reaching
            // this point means the harness itself misbehaved. It is still turned
            // into a counted failure rather than a bare stack trace, because an
            // exit status is the only machine-readable outcome a caller gets.
            checksFailed++;
            System.err.println("FAIL: harness aborted unexpectedly: " + unexpected);
        }
        System.out.println(SEPARATOR);
        System.out.println("UserTest summary: " + checksExecuted + " checks executed, "
                + checksFailed + " failed");
        System.out.println("RESULT: " + (checksFailed == 0 ? "PASS" : "FAIL"));
        System.exit(checksFailed == 0 ? EXIT_SUCCESS : EXIT_FAILURE);
    }

    // The frozen expectations. User keeps its configuration keys, environment
    // names and built-in defaults private, so this harness restates them as its
    // own literals: a test that imported the constants it is checking would
    // assert only that a value equals itself.

    /** Standard-output bytes the original program produced, and must still produce. */
    private static final String LEGACY_STDOUT_TEXT = "Test";

    /**
     * Byte length of the preserved default output, {@code Test} plus one newline.
     *
     * <p>It assumes a single-byte line separator, which every target of this
     * project uses. A platform with a two-byte separator would legitimately fail
     * this check, because it would also break the byte-for-byte output contract.
     */
    private static final int LEGACY_STDOUT_BYTE_LENGTH = 5;

    private static final String STATUS_UP = "UP";

    private static final String STATUS_UP_FRAGMENT = "\"status\":\"UP\"";

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
    private static final String DEFAULT_APP_HOST = "0.0.0.0";

    private static final String KEY_JAVA_PORT = "java.port";
    private static final String ENV_JAVA_PORT = "JAVA_PORT";
    private static final String ENV_UNIVERSAL_PORT = "PORT";
    private static final int DEFAULT_JAVA_PORT = 8002;

    /** Lowest legal port; 0 additionally means "ask the OS for an ephemeral one". */
    private static final int MIN_PORT = 0;

    private static final int MAX_PORT = 65535;

    /**
     * The reference body from the specification, at exactly
     * {@value #REFERENCE_BODY_BYTE_LENGTH} bytes. Its timestamp is a fixed
     * literal rather than a live clock reading, which is what makes the parity
     * assertion deterministic: the byte length of the real payload varies only
     * with the configured name and version, never with the time of day.
     */
    private static final String REFERENCE_TIMESTAMP = "2026-07-28T13:47:08Z";

    /**
     * Byte length of the rendered document for the shipped name and version.
     * All three language implementations must agree on this number; it is the
     * cross-language parity constant.
     */
    private static final int REFERENCE_BODY_BYTE_LENGTH = 108;

    private static final String REFERENCE_BODY =
            "{\"name\":\"only_parent_parent_repo_10_LOC\",\"version\":\"1.1.0\""
            + ",\"timestamp\":\"2026-07-28T13:47:08Z\",\"status\":\"UP\"}";

    // Patterns

    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    /**
     * Fixed-width UTC instant truncated to whole seconds. Format only: this
     * harness never asserts what the clock said.
     */
    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");

    /**
     * The whole document in one expression. Anchored at both ends and allowing
     * no whitespace, it pins the key set to exactly four members and pins their
     * order, so a reordered or extended payload cannot slip through.
     */
    private static final Pattern PAYLOAD_SHAPE_PATTERN = Pattern.compile(
            "^\\{\"name\":\"[^\"]*\",\"version\":\"[^\"]*\""
            + ",\"timestamp\":\"[^\"]*\",\"status\":\"[^\"]*\"\\}$");

    /** Any whitespace at all, which a compact document must not contain. */
    private static final Pattern ANY_WHITESPACE_PATTERN = Pattern.compile("\\s");

    /**
     * The IMF-fixdate form RFC 9110 section 5.6.7 makes preferred for a
     * {@code Date} field: day name, two-digit day, month name, four-digit year,
     * a fixed-width time, and the literal GMT.
     *
     * <p>Format only, and for the same reason the payload timestamp is checked by
     * format only: the field is a clock reading written by the runtime, so an
     * assertion on its value would fail for a reason unrelated to correctness. The
     * shape is worth asserting even so - a malformed date is a field a proxy or a
     * monitoring agent may reject outright.
     */
    private static final Pattern HTTP_DATE_PATTERN = Pattern.compile(
            "^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{2} "
            + "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4} "
            + "\\d{2}:\\d{2}:\\d{2} GMT$");

    // Harness mechanics

    private static final int EXIT_SUCCESS = 0;

    private static final int EXIT_FAILURE = 1;

    private static final String SEPARATOR =
            "------------------------------------------------------------";

    /**
     * An environment variable name chosen so that it can never be set, used
     * wherever a check needs the environment layer of the precedence chain to be
     * provably absent. Passing a name like this is what makes those checks
     * deterministic without touching the real environment.
     */
    private static final String ABSENT_ENV_PRIMARY = "USERTEST_ABSENT_ENV_DO_NOT_SET_A";

    /** A second guaranteed-absent name, for the universal-port parameter. */
    private static final String ABSENT_ENV_SECONDARY = "USERTEST_ABSENT_ENV_DO_NOT_SET_B";

    /** Loopback, so nothing this harness binds is reachable off the host. */
    private static final String TEST_HOST = "127.0.0.1";

    /** Port 0 asks the OS for a free port, so no run can collide with another. */
    private static final int EPHEMERAL_PORT = 0;

    /** Budget for every request the live section issues; it must not hang. */
    private static final int CLIENT_TIMEOUT_SECONDS = 5;

    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;

    /** Fixed error bodies the endpoint returns; nothing about a request is echoed. */
    private static final String BODY_NOT_FOUND = "{\"error\":\"Not Found\"}";
    private static final String BODY_METHOD_NOT_ALLOWED = "{\"error\":\"Method Not Allowed\"}";

    /**
     * Name of the non-daemon thread the listener dispatches accepted connections on.
     *
     * <p>Chosen by the JDK rather than by {@link User}: {@code HttpServer} names its
     * own dispatcher, and application code has no way to rename it. The name is
     * still worth asserting, and asserting by NAME rather than by count, because
     * the thread being non-daemon is what keeps {@code --serve} alive after
     * {@code main} returns and what must therefore disappear when the server is
     * stopped. A leaked one would keep this harness's JVM alive past its own
     * summary line. Confirmed by execution on OpenJDK 25; the server's other
     * thread, {@code idle-timeout-task}, is a daemon and does not match this name.
     */
    private static final String DISPATCHER_THREAD_NAME = "HTTP-Dispatcher";

    /** Total number of checks executed; the summary line reports it. */
    private static int checksExecuted;

    /** Number of checks that failed; the process exit status is derived from it. */
    private static int checksFailed;

    /** A path that is deliberately not the configured route. */
    private static final String UNKNOWN_PATH = "/__usertest_unknown_route__";

    /** The root path, which is what an empty or null request path normalises to. */
    private static final String ROOT_PATH = "/";

    private static final int DISPATCHER_SHUTDOWN_WAIT_SECONDS = 5;

    private static final long DISPATCHER_POLL_MILLIS = 20L;

    // Child-process expectations - sections A, E and G

    /**
     * Budget for a child JVM that is expected to finish, in seconds.
     *
     * <p>Generous, because it must cover the source launcher compiling User.java
     * on a cold and possibly loaded CI machine - locally that costs well under a
     * second. It is a bound rather than a delay: every wait returns as soon as the
     * child exits, so the budget is only ever spent when something is wrong. What
     * it must never be is absent, because a child that hangs would otherwise hang
     * the harness, and a harness that hangs reports nothing at all.
     */
    private static final int CHILD_TIMEOUT_SECONDS = 90;

    /** Budget for a serving child to announce its port, in seconds. */
    private static final int SERVER_START_TIMEOUT_SECONDS = 90;

    private static final int CHILD_STOP_TIMEOUT_SECONDS = 20;

    /**
     * The startup line {@code --serve} writes to standard error, with the bound
     * host, port and route captured.
     *
     * <p>Parsing the real port out of this line is what removes the only race a
     * child-process test would otherwise have: choosing a port in the parent,
     * hoping it is still free by the time the child binds it. Here the child is
     * given port 0, the OS chooses, and the child reports what it got.
     */
    private static final Pattern BANNER_PATTERN = Pattern.compile(
            "^\\[User\\] health endpoint listening on http://([^\\s:/]+):(\\d+)(\\S*)$");

    private static final String APPLICATION_SOURCE_FILE = "User.java";

    private static final String APPLICATION_CLASS_FILE = "User.class";

    private static final String APPLICATION_CLASS_NAME = "User";

    /**
     * The shared configuration file name, which is the same in all three
     * implementations and is resolved relative to each one's own source file.
     *
     * <p>Restated here rather than read from {@link User} for the same reason every
     * other expectation is restated: a test that read the constant it is checking
     * would assert only that a value equals itself.
     */
    private static final String CONFIG_FILE_NAME = "app.config.properties";

    /**
     * The system property the source launcher sets to the file it was given.
     *
     * <p>Present only under {@code java UserTest.java}; absent when this harness
     * runs from compiled classes, which is exactly what makes it usable as the
     * first candidate in the resolver rather than the only one.
     */
    private static final String SOURCE_LAUNCHER_PROPERTY = "jdk.launcher.sourcefile";

    private static final String FLAG_SERVE = "--serve";

    private static final String FLAG_PROBE = "--probe";

    /** An argument the dispatcher must not recognise, so it selects the default. */
    private static final String UNRECOGNISED_FLAG = "--usertest-not-a-mode";

    /** A port value no configuration may accept, used to prove fail-closed starts. */
    private static final String UNUSABLE_PORT_VALUE = "not-a-port";

    /** A numerically valid but out-of-range port, refused for a different reason. */
    private static final String OUT_OF_RANGE_PORT_VALUE = "70000";

    /**
     * The bare category an unattended refusal reports for an unusable port.
     *
     * <p>Byte-identical after the log prefix in all three implementations, and the
     * text is the whole assertion: {@code --probe} reports this CATEGORY and stops,
     * where {@code --serve} reports the same fault and names the value. The split is
     * deliberate - an interactive start has an operator who typed the value, while a
     * probe on a container health-check timer has only a log collector, and a
     * configured value is an input.
     */
    private static final String PORT_REFUSAL_CATEGORY = "the configured port is unusable";

    /**
     * The prefix of the diagnostic a probe writes once it has actually connected.
     *
     * <p>Used as a NEGATIVE assertion: its absence proves a refusal happened before
     * any socket was opened, which is what makes a configuration check deterministic
     * and network-free rather than merely usually fast.
     */
    private static final String PROBE_REACH_PREFIX = "probe could not reach";

    // Transport expectations - section H. The exact header sets, the runtime's
    // one observable ceiling, and the sizes used to probe it. There is no 400,
    // 414, 431 or 505 expectation because this endpoint produces no such
    // response; section H records the reasoning in full.

    private static final int HTTP_CONTINUE = 100;

    private static final String CONTENT_TYPE_JSON = "application/json";

    /**
     * The media type the RUNTIME's own transport rejections declare.
     *
     * <p>Not this endpoint's media type, and not a defect: a request line the
     * server cannot parse never reaches a handler, so the answer is composed by the
     * server. It is named here only so that the disclosure assertions can tell a
     * runtime-written rejection apart from an endpoint-written response.
     */
    private static final String CONTENT_TYPE_HTML = "text/html";

    /**
     * The three directives the cache header must carry, all of them.
     *
     * <p>Asserting the whole set matters because they do different jobs: a stale
     * health answer is worse than none, and a response merely marked no-cache may
     * still be written to a shared store.
     */
    private static final List<String> CACHE_DIRECTIVES =
            List.of("no-cache", "no-store", "must-revalidate");

    /**
     * The THREE response header names the frozen contract actually specifies, and
     * the three that app.py and index.js send - exactly these and nothing else.
     *
     * <p>This is the set the contract states: a JSON content type, a no-store cache
     * directive, and a content length. It is defined separately from what this
     * implementation sends so that the difference between the two is a named,
     * asserted quantity rather than an assumption folded invisibly into one list.
     */
    private static final Set<String> SPECIFIED_HEADER_NAMES =
            Set.of("content-type", "cache-control", "content-length");

    /**
     * The header fields this transport adds that the contract does not specify.
     *
     * <p>Exactly one: {@code date}. This is a STATED DEVIATION, not a contract
     * field. The contract says "no server banner and no date header", and this
     * implementation sends a date header because {@code Headers.set("Date", <now>)}
     * runs unconditionally inside {@code ExchangeImpl.sendResponseHeaders} before
     * its first branch and replaces whatever the caller put there. The full record -
     * the seven application-side techniques measured against a live server on this
     * JDK, and the four places the specification mandates this transport - is on
     * {@code User.sendResponse}.
     *
     * <p>Naming the deviation in its own constant is what keeps the harness from
     * quietly ratifying it: {@link #checkDeviationIsBounded} asserts that the sets
     * below are the specified three plus PRECISELY these, so the divergence is
     * pinned at one named field. A second unspecified field appearing later - a
     * {@code Server} banner, a {@code Keep-Alive} advertising the idle timeout -
     * fails the suite rather than being absorbed into an expectation.
     */
    private static final Set<String> DEVIATION_HEADER_NAMES = Set.of("date");

    /**
     * Exactly the field names a 200 or a 404 carries, lower-cased: the specified
     * three plus the one stated deviation.
     *
     * <p>Asserted by set EQUALITY rather than by presence, which is what makes it a
     * disclosure check as well as a contract check: it proves in one assertion that
     * these are present AND that nothing else is. Presence checks can only ever prove
     * the first half, and the half they miss is the half that leaks.
     */
    private static final Set<String> CONTRACT_HEADER_NAMES =
            union(SPECIFIED_HEADER_NAMES, DEVIATION_HEADER_NAMES);

    /**
     * Exactly the field names a 405 carries: the set above plus Allow.
     *
     * <p>Every refused method carries this same set, HEAD included. HEAD's response
     * body is empty, as RFC 9110 requires, but its {@code Content-Length} still
     * declares the length the body would have had - which is why the set is shared
     * rather than split, and why {@link User} sets that field explicitly instead of
     * letting the server derive it and then drop it.
     */
    private static final Set<String> REFUSAL_HEADER_NAMES =
            union(CONTRACT_HEADER_NAMES, Set.of("allow"));

    /**
     * Returns the union of two header-name sets.
     *
     * <p>Used so the expectation sets above are BUILT from the specified contract
     * plus a named deviation, rather than written out as flat literals in which the
     * two are indistinguishable.
     */
    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> combined = new TreeSet<>(first);
        combined.addAll(second);
        return Collections.unmodifiableSet(combined);
    }

    /**
     * Exactly the field names an HTTP/1.0 answer carries: the contract four plus
     * Connection.
     *
     * <p>HTTP/1.0 defaults to closing, and the server says so rather than leaving
     * the client to infer it from the FIN. The field is added by the runtime, not by
     * {@link User}, which is why it appears in this set and in no other.
     */
    private static final Set<String> LEGACY_ANSWER_HEADER_NAMES =
            Set.of("date", "content-type", "cache-control", "content-length", "connection");

    /**
     * Exactly the field names an HTTP/1.0 answer carries when the client opted into
     * keep-alive: the legacy five plus Keep-Alive.
     *
     * <p>The extra field is the runtime advertising its idle timeout, which it does
     * only for a 1.0 client that asked to persist. It is named here rather than
     * tolerated by a loosened assertion, so that the one response in the whole
     * surface that discloses a runtime parameter is the one response documented to.
     */
    private static final Set<String> LEGACY_KEEP_ALIVE_HEADER_NAMES = Set.of("date",
            "content-type", "cache-control", "content-length", "connection", "keep-alive");

    /**
     * Exactly the field names the RUNTIME's own transport rejection carries.
     *
     * <p>Three fields, written by the server before any handler is reached: it
     * declares HTML, declares an accurate length and announces the close. Notably
     * it carries no {@code Date} and no {@code Cache-Control}, so this set is not a
     * subset of the contract set - which is precisely why a rejection is asserted
     * against its own expectation rather than against the contract one.
     */
    private static final Set<String> RUNTIME_REJECTION_HEADER_NAMES =
            Set.of("connection", "content-length", "content-type");

    /**
     * Every transport-level rejection body the runtime emits, keyed by the shape
     * that triggers it, as a CHANGE DETECTOR for a recorded security position.
     *
     * <p>These strings are the PLATFORM's, not this program's, and this is the only
     * place in the harness that pins a body no line of {@link User} composes. They
     * earn that exception because the class documentation on {@link User} records
     * them as an ACCEPTED RISK - two of the eight name a Java exception class to an
     * unauthenticated client, and closing that is impossible from inside this
     * process. An accepted risk is only honestly accepted while its description is
     * accurate, so the description is pinned: if a JDK upgrade adds, removes or
     * reworks any of these, the suite fails here and the record is corrected,
     * instead of the drift being discovered from a production packet capture.
     *
     * <p>This does NOT weaken the position stated in section H's preamble. Nothing
     * here asserts that the ENDPOINT produces a 400, a 404 with an HTML body or a
     * 501 - it produces exactly 200, 404 and 405 with JSON. What is asserted is what
     * the listener beneath it does with a request the endpoint never sees, which is a
     * different subject with a different reason to be asserted.
     */
    private static final Map<String, String> RUNTIME_REJECTION_BODIES = Map.of(
            "an unparsable target",
            "<h1>400 Bad Request</h1>URISyntaxException thrown",
            "a non-numeric Content-Length",
            "<h1>400 Bad Request</h1>NumberFormatException thrown",
            "a negative Content-Length",
            "<h1>400 Bad Request</h1>Illegal Content-Length value",
            "conflicting framing fields",
            "<h1>400 Bad Request</h1>Conflicting or malformed headers detected",
            "whitespace before a field colon",
            "<h1>400 Bad Request</h1>Header key contains illegal characters",
            "a request line that is not three tokens",
            "<h1>400 Bad Request</h1>Bad request line",
            "a target no context matches",
            "<h1>404 Not Found</h1>No context found for request",
            "an unsupported transfer coding",
            "<h1>501 Not Implemented</h1>Unsupported Transfer-Encoding value");

    /**
     * A marker planted in every part of a request a rejection body could echo.
     *
     * <p>The "no reflection" half of the documented position is the half that would
     * matter if it were false: a fixed platform string discloses a runtime, while an
     * echoed one is a reflected-input channel. It is asserted rather than assumed.
     */
    private static final String REFLECTION_MARKER = "UserTestReflectionMarker";

    /**
     * The only request ceiling the runtime enforces observably: more DISTINCT header
     * field names than this and the connection is dropped without a response.
     *
     * <p>The server's own limit, not this endpoint's, and established by execution:
     * at this many entries a request is served normally, and at one more the
     * connection closes with no bytes written and the handler is never entered. It
     * is asserted because it is a denial-of-service control that is reachable from
     * the network, and a control nobody tests is a control nobody has.
     *
     * <p>The count is of PARSED ENTRIES, which is why {@code headerFieldBlock} gives
     * every field a distinct name. Repeated occurrences of one name collapse into a
     * single entry, so a thousand copies of the same field are served and would
     * measure this ceiling as absent - a block built from one repeated name would
     * make this assertion pass for the wrong reason.
     */
    private static final int RUNTIME_HEADER_FIELD_CEILING = 200;

    /**
     * A request target far longer than any real client sends, used as a control.
     *
     * <p>The runtime imposes no target ceiling, so this must be ROUTED rather than
     * rejected - and routed to 404, never truncated into a route match. A target cut
     * short could normalise to the health path and be answered 200 on the strength
     * of bytes that were never received, which is the one failure mode a long target
     * could produce that would actually matter.
     */
    private static final int LARGE_TARGET_BYTES = 65_537;

    /** A single header field far larger than any real client sends, and still served. */
    private static final int LARGE_FIELD_BYTES = 16_385;

    private static final int LARGE_BLOCK_FIELD_COUNT = 20;

    private static final int LARGE_BLOCK_FIELD_BYTES = 1_000;

    /**
     * A request body large enough to fill the socket buffers, in bytes.
     *
     * <p>One mebibyte, and the size matters. A body this large left unread makes the
     * kernel answer the connection close with a reset, so the client reads
     * "connection reset by peer" instead of the response that was already written
     * for it - which is exactly what happened before {@link User} drained request
     * bodies, and is why the drain is a requirement rather than a courtesy. A small
     * body fits in the buffers and never reproduces it, so a small body would leave
     * the drain untested.
     */
    private static final int LARGE_BODY_BYTES = 1024 * 1024;

    private static final int RAW_READ_TIMEOUT_MILLIS = 10_000;

    private static final int RAW_CLOSE_TIMEOUT_MILLIS = 2_000;

    /**
     * Socket timeout for one read attempt, in milliseconds.
     *
     * <p>Short on purpose. It is not a budget but a heartbeat: it returns control to
     * each read loop often enough that the loop can check its own deadline, so a
     * quiet peer produces a diagnosable failure rather than a blocked thread.
     */
    private static final int RAW_POLL_TIMEOUT_MILLIS = 250;

    private static final int RAW_BUFFER_BYTES = 8192;

    /** The end of a header block, and the separator before any body. */
    private static final String HEAD_TERMINATOR = "\r\n\r\n";

    private static final String CRLF = "\r\n";

    // The assertion engine. Three primitives, one counter pair and a section
    // runner. Every failure names the check that failed on standard error and is
    // counted, because the exit status is derived from the counter pair alone.

    /**
     * One group of related checks.
     *
     * <p>Declared to throw {@code Exception} so that a section may use the
     * blocking, checked-exception APIs of the JDK's HTTP client and file
     * utilities without each one needing its own wrapper.
     */
    private interface Section {
        void run() throws Exception;
    }

    /**
     * Runs one section, converting an escaped exception into a counted failure.
     *
     * <p>Isolating sections matters: if the live-routing section cannot bind a
     * socket, the payload and configuration findings are still reported instead
     * of being lost behind the first stack trace. The aborted section is counted
     * as one executed and one failed check so that the summary can never claim a
     * pass for work that did not run.
     */
    private static void runSection(String label, Section section) {
        System.out.println(SEPARATOR);
        System.out.println("SECTION " + label);
        try {
            section.run();
        } catch (Exception failure) {
            checksExecuted++;
            checksFailed++;
            System.err.println("FAIL: section " + label + " aborted with " + failure);
        }
    }

    /**
     * Records the outcome of one boolean condition.
     *
     * <p>This is the form used wherever the compared values must not be printed,
     * which is how the environment-precedence checks avoid echoing an
     * environment value into a log.
     */
    private static void check(String name, boolean condition) {
        checksExecuted++;
        if (condition) {
            System.out.println("PASS: " + name);
            return;
        }
        checksFailed++;
        System.err.println("FAIL: " + name);
    }

    /**
     * Records the outcome of an equality comparison, reporting both values when
     * they differ so that a failure is diagnosable from the log alone.
     *
     * @param expected the required value; {@code null} is compared safely
     */
    private static void checkEquals(String name, Object expected, Object actual) {
        checksExecuted++;
        if (Objects.equals(expected, actual)) {
            System.out.println("PASS: " + name);
            return;
        }
        checksFailed++;
        System.err.println("FAIL: " + name + " - expected [" + expected + "] but was [" + actual + "]");
    }

    /**
     * Records the outcome of a byte-for-byte comparison.
     *
     * <p>Separate from {@link #checkEquals} out of necessity rather than taste:
     * that method compares with {@code Objects.equals}, which on arrays is
     * reference identity, so two distinct arrays holding identical bytes would
     * compare unequal and two identical references would compare equal without
     * looking at a single byte. Either outcome is worse than no check.
     *
     * <p>A failure prints both arrays as unsigned decimal, because the differences
     * that matter here are invisible when rendered as text: a trailing carriage
     * return, a byte-order mark or a re-encoded character all look correct in a
     * log and all break the hash the committed baseline records.
     */
    private static void checkBytesEqual(String name, byte[] expected, byte[] actual) {
        checksExecuted++;
        if (Arrays.equals(expected, actual)) {
            System.out.println("PASS: " + name);
            return;
        }
        checksFailed++;
        System.err.println("FAIL: " + name + " - expected " + Arrays.toString(expected)
                + " but was " + Arrays.toString(actual));
    }

    /**
     * Records that a set of values is exactly the expected set.
     *
     * <p>Used for header field names, where equality is the whole assertion:
     * presence checks prove only that what should be there is there, and say
     * nothing about what else arrived. A failure names the two differences
     * separately, because "missing" and "unexpected" have different causes and a
     * combined message sends the reader looking in the wrong place.
     */
    private static void checkSetEquals(String name, Set<String> expected, Set<String> actual) {
        checksExecuted++;
        if (expected.equals(actual)) {
            System.out.println("PASS: " + name);
            return;
        }
        checksFailed++;
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);
        System.err.println("FAIL: " + name + " - missing " + missing
                + ", unexpected " + unexpected + " (observed " + new TreeSet<>(actual) + ")");
    }

    /**
     * Runs {@code work} with stderr captured, returning everything it wrote.
     *
     * <p>Several assertions below concern a diagnostic the application writes
     * DELIBERATELY. Letting those lines through would interleave them with this
     * harness's own PASS and FAIL output, and it would also leave the diagnostic
     * unasserted - the interesting part is usually that exactly ONE line was
     * written and that it withheld the value it was complaining about.
     *
     * <p>The original stream is restored on every path, including an exception.
     */
    private static String withStderr(Runnable work) {
        java.io.PrintStream original = System.err;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            work.run();
        } finally {
            System.err.flush();
            System.setErr(original);
        }
        return sink.toString(StandardCharsets.UTF_8);
    }

    /**
     * Records the outcome of a refusal, requiring the message to be exact.
     *
     * <p>{@link #checkRejects} asserts that a message CONTAINS a value, which is
     * the right question for the port because the offending value is named there.
     * The configuration refusals are the opposite case: the message names the KEY
     * and withholds the VALUE, and it must match the other two implementations
     * byte for byte, so it is compared in full rather than searched.
     */
    private static void checkRefusalMessage(String name, String expected, Runnable call) {
        checksExecuted++;
        try {
            call.run();
        } catch (IllegalArgumentException refused) {
            if (expected.equals(refused.getMessage())) {
                System.out.println("PASS: " + name);
                return;
            }
            checksFailed++;
            System.err.println("FAIL: " + name + " - expected [" + expected
                    + "] but was [" + refused.getMessage() + "]");
            return;
        }
        checksFailed++;
        System.err.println("FAIL: " + name + " - the value was accepted instead of rejected");
    }

    /**
     * Records that a call rejects an unusable input instead of returning a value.
     *
     * <p>This is the fail-closed form. A configuration value that cannot be used
     * must be refused with the offending value named, never quietly replaced by a
     * different one, so the assertion has to be "this throws" rather than "this
     * returns something". The rejected value is expected to appear in the
     * exception message, and that is asserted too: a rejection an operator cannot
     * trace back to the setting they mistyped is only half a diagnostic.
     */
    private static void checkRejects(String name, String offending, Runnable call) {
        checksExecuted++;
        try {
            call.run();
        } catch (IllegalArgumentException rejected) {
            String message = rejected.getMessage();
            if (message != null && message.contains(offending)) {
                System.out.println("PASS: " + name);
                return;
            }
            checksFailed++;
            System.err.println("FAIL: " + name + " - rejected, but the message [" + message
                    + "] does not name [" + offending + "]");
            return;
        }
        checksFailed++;
        System.err.println("FAIL: " + name + " - the value was accepted instead of rejected");
    }

    /**
     * Records the outcome of a format check.
     *
     * <p>Every assertion about the timestamp goes through this method rather than
     * through {@link #checkEquals}, which is what keeps the harness immune to the
     * clock: a format is stable, a value is not.
     *
     * @param pattern the pattern the value must match in full
     * @param actual  the observed value; {@code null} always fails
     */
    private static void checkMatches(String name, Pattern pattern, String actual) {
        checksExecuted++;
        if (actual != null && pattern.matcher(actual).matches()) {
            System.out.println("PASS: " + name);
            return;
        }
        checksFailed++;
        System.err.println("FAIL: " + name + " - [" + actual + "] does not match " + pattern.pattern());
    }

    /**
     * Announces a diagnostic that the next check will deliberately provoke.
     *
     * <p>Two checks drive User down a fail-closed path on purpose, and User
     * reports those on standard error by design. Announcing them first is what
     * stops a reader of the log from mistaking correct behaviour for a fault.
     *
     * @param expected the diagnostic that is about to appear on standard error
     */
    private static void announceExpectedDiagnostic(String expected) {
        System.out.println("NOTE: an expected diagnostic follows on standard error: " + expected);
    }

    // Section A - preserved legacy behaviour. The original program printed
    // "Test" and exited 0, and it still must: the backward-compatibility
    // requirement outranks the new feature.

    /**
     * Asserts that the default invocation is unchanged and side-effect free,
     * observed from outside the process that performs it.
     *
     * <p>The isolation is the point. Capturing streams around an in-process
     * {@code User.main} call reads the same bytes but cannot see the exit status,
     * and the exit status is half the contract: a default path that regressed to
     * {@code System.exit(0)} would kill this JVM mid-assertion, print no summary
     * and still hand the shell a zero - a passing result for a run that asserted
     * nothing.
     *
     * <p>Both streams are compared as RAW BYTES. The committed baseline hashes
     * bytes, so a text comparison would silently accept a changed encoding or a
     * normalised line ending, either of which breaks the hash while passing.
     *
     * <p>Prompt termination carries the listener assertion: a dual-mode program
     * that accidentally bound a socket in its default mode would print exactly the
     * right line and then never exit, because the acceptor thread is not a daemon.
     */
    private static void verifyPreservedBehaviour() {
        byte[] expectedStdout = (LEGACY_STDOUT_TEXT + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        ChildOutcome defaultMode = runApplication(List.of(), Map.of());

        check("the default invocation terminated on its own within its budget",
                defaultMode.exited());
        checkEquals("default mode exits 0", EXIT_SUCCESS, defaultMode.status());
        checkBytesEqual("default mode standard output is preserved byte for byte",
                expectedStdout, defaultMode.standardOutput());
        checkEquals("default mode standard output byte length",
                LEGACY_STDOUT_BYTE_LENGTH, defaultMode.standardOutput().length);
        checkBytesEqual("default mode writes nothing at all to standard error",
                new byte[0], defaultMode.standardError());

        // Termination is the listener assertion: a bound acceptor thread is not a
        // daemon, so a process that started one could not have reached this point.
        check("default mode starts no HTTP listener, so the process ends by itself",
                defaultMode.exited() && defaultMode.status() == EXIT_SUCCESS);
    }

    /**
     * Counts the live threads {@link User.HealthServer} uses to accept connections.
     *
     * @return the number of acceptor threads currently alive in this JVM
     */
    private static int dispatcherThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().contains(DISPATCHER_THREAD_NAME)) {
                count++;
            }
        }
        return count;
    }

    // Child-process machinery, shared by sections A, E and G. Three details in
    // it are load-bearing, each because omitting it produces a harness that
    // hangs or lies. Both output streams are drained on their own threads: a
    // child writing more than a pipe buffer to a stream nobody reads BLOCKS, and
    // a server child writes to stderr, so draining only stdout would wedge the
    // very process under test. Every wait is bounded and every child is
    // destroyed on every path, because an orphaned server would hold its port
    // and outlive the run. Every child environment starts EMPTY - not even PATH
    // is inherited - so a child sees exactly the variables a check names, which
    // is what makes the environment layer provable rather than merely probable.

    /**
     * What one completed child invocation produced.
     *
     * @param status         exit status, or {@code -1} when the child had to be killed
     * @param exited         whether the child ended on its own inside its budget
     */
    private record ChildOutcome(int status, byte[] standardOutput, byte[] standardError,
            boolean exited) {

        String errorText() {
            return new String(standardError, StandardCharsets.UTF_8);
        }

        /**
         * How many lines the child wrote to standard error.
         *
         * <p>A count rather than a substring check, because the property a refusal
         * needs is that it is ONE record: a diagnostic that carries an input can be
         * made to look like two, and counting is what detects that no matter which
         * character was used to attempt it.
         */
        int errorLineCount() {
            return (int) errorText().lines().count();
        }
    }

    /**
     * The {@code java} launcher of the JVM running this harness.
     *
     * <p>Derived from {@code java.home} rather than found on {@code PATH}, which
     * guarantees the child runs on the same runtime as the parent - so a result
     * can never be explained by two different Java versions - and lets the child
     * environment be built empty, since an empty environment has no PATH to search.
     */
    private static String javaLauncher() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * Builds the command prefix that starts the application in a child JVM.
     *
     * <p>Source launch is preferred because it is the documented invocation and
     * needs no build step; a compiled classpath launch is the fallback, which is
     * what makes this work under {@code java -cp out UserTest} as well. If neither
     * artifact can be found the method THROWS, which {@link #runSection} records
     * as a counted failure. That is deliberate: silently skipping would reproduce
     * exactly the false green that moving these checks into a child JVM exists to
     * eliminate.
     */
    private static List<String> applicationLaunchPrefix() {
        Path source = locateBeside(APPLICATION_SOURCE_FILE);
        if (source != null) {
            return List.of(javaLauncher(), source.toString());
        }
        Path compiled = locateBeside(APPLICATION_CLASS_FILE);
        if (compiled != null) {
            return List.of(javaLauncher(), "-cp", compiled.getParent().toString(),
                    APPLICATION_CLASS_NAME);
        }
        throw new IllegalStateException("cannot locate " + APPLICATION_SOURCE_FILE + " or "
                + APPLICATION_CLASS_FILE + "; searched the launched source file's directory, "
                + "the working directory and this class's code source");
    }

    /**
     * Finds a file sitting beside this harness, trying three locations in order.
     *
     * <p>The launched source file's own directory comes first and is the one that
     * matters: under {@code java UserTest.java} it is authoritative regardless of
     * which directory the run started from. The working directory covers the
     * documented compiled invocation, and this class's code source covers a
     * compiled run started from somewhere else entirely.
     *
     * @return the located file, or {@code null} if no candidate holds it
     */
    private static Path locateBeside(String fileName) {
        List<Path> candidates = new ArrayList<>();
        String launched = System.getProperty(SOURCE_LAUNCHER_PROPERTY);
        if (launched != null && !launched.isBlank()) {
            Path parent = Path.of(launched).toAbsolutePath().getParent();
            if (parent != null) {
                candidates.add(parent);
            }
        }
        candidates.add(Path.of("").toAbsolutePath());
        Path codeSource = codeSourceDirectory();
        if (codeSource != null) {
            candidates.add(codeSource);
        }
        for (Path candidate : candidates) {
            Path resolved = candidate.resolve(fileName);
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    /**
     * The directory this class was loaded from, however it was loaded.
     *
     * <p>Under the source launcher the code source is the {@code .java} file
     * itself, so the directory is its parent; under a classpath launch it is
     * already a directory. Both shapes are handled rather than assumed.
     *
     * @return the directory, or {@code null} if it cannot be determined
     */
    private static Path codeSourceDirectory() {
        try {
            var source = UserTest.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path location = Path.of(source.getLocation().toURI());
            return Files.isDirectory(location) ? location : location.getParent();
        } catch (RuntimeException | java.net.URISyntaxException unavailable) {
            // A code source is optional information, and its absence is not a
            // defect: the other two candidates cover every documented invocation.
            return null;
        }
    }

    /**
     * Copies the application into a directory of its own and returns the command
     * that launches the copy.
     *
     * <p>This exists to place a properties file BESIDE the code the child will
     * run. All three implementations resolve configuration relative to their own
     * code source first and the working directory second, and none of them
     * accepts an environment variable naming an arbitrary properties file, so
     * copying the artifact is the only way to hand a child a configuration of the
     * harness's choosing - and it exercises the real resolution rule rather than
     * an override that bypasses it.
     *
     * <p>The working directory is deliberately NOT changed, so the repository's
     * own properties file remains the second candidate: if code-source resolution
     * ever regressed, the child would serve the repository's values, which differ
     * from every value written into the temporary file.
     */
    private static List<String> isolatedLaunchPrefix(Path directory) throws IOException {
        Path source = locateBeside(APPLICATION_SOURCE_FILE);
        if (source != null) {
            Path copy = directory.resolve(APPLICATION_SOURCE_FILE);
            Files.copy(source, copy);
            return List.of(javaLauncher(), copy.toString());
        }
        Path compiled = locateBeside(APPLICATION_CLASS_FILE);
        if (compiled != null) {
            Files.copy(compiled, directory.resolve(APPLICATION_CLASS_FILE));
            return List.of(javaLauncher(), "-cp", directory.toString(),
                    APPLICATION_CLASS_NAME);
        }
        throw new IllegalStateException("cannot locate " + APPLICATION_SOURCE_FILE + " or "
                + APPLICATION_CLASS_FILE + " to isolate; searched the launched source file's "
                + "directory, the working directory and this class's code source");
    }

    /**
     * Starts a child JVM running the application, with an explicit environment.
     *
     * @param environment the child's COMPLETE environment; nothing is inherited
     * @return the started process, with its standard input already closed
     */
    private static Process startApplication(Map<String, String> environment, String... args)
            throws IOException {
        return startApplication(applicationLaunchPrefix(), environment, args);
    }

    /**
     * Starts a child JVM from an explicit launch command, with an explicit
     * environment.
     *
     * @param environment the child's COMPLETE environment; nothing is inherited
     * @return the started process, with its standard input already closed
     */
    private static Process startApplication(List<String> prefix,
            Map<String, String> environment, String... args) throws IOException {
        return startApplication(prefix, null, environment, args);
    }

    /**
     * Starts a child JVM from an explicit launch command, environment and
     * working directory.
     *
     * <p>The working directory is a parameter because the application's second
     * configuration candidate is {@code app.config.properties} in the working
     * directory. A child left pointing at the repository would find the
     * repository's own file, so a test about a MISSING configuration file can
     * only be honest if it can also move the child away from that file.
     *
     * @param workingDirectory the child's working directory, or {@code null} to
     *                         inherit this process's
     * @param environment      the child's COMPLETE environment; nothing is inherited
     * @return the started process, with its standard input already closed
     */
    private static Process startApplication(List<String> prefix, Path workingDirectory,
            Map<String, String> environment, String... args) throws IOException {
        List<String> command = new ArrayList<>(prefix);
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process child = builder.start();
        // The application reads no input in any mode. Closing the pipe now means a
        // child that ever tried would see end-of-stream immediately instead of
        // waiting for input that is never coming.
        child.getOutputStream().close();
        return child;
    }

    /**
     * Runs the application to completion in a child JVM and collects everything.
     *
     * @param environment the child's COMPLETE environment
     */
    private static ChildOutcome runApplication(List<String> args,
            Map<String, String> environment) {
        return runApplication(null, null, args, environment);
    }

    /**
     * Runs the application to completion in a child JVM, from an explicit launch
     * command and working directory, and collects everything.
     *
     * @param prefix           launcher and target, or {@code null} for the
     *                         repository's own copy
     * @param workingDirectory the child's working directory, or {@code null} to
     *                         inherit this process's
     * @param environment      the child's COMPLETE environment
     */
    private static ChildOutcome runApplication(List<String> prefix, Path workingDirectory,
            List<String> args, Map<String, String> environment) {
        Process child = null;
        try {
            List<String> launch = prefix != null ? prefix : applicationLaunchPrefix();
            child = startApplication(launch, workingDirectory, environment,
                    args.toArray(new String[0]));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread outPump = drain(child.getInputStream(), out, "child-stdout");
            Thread errPump = drain(child.getErrorStream(), err, "child-stderr");
            boolean exited = child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                child.destroyForcibly();
                child.waitFor(CHILD_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            outPump.join(Duration.ofSeconds(CHILD_STOP_TIMEOUT_SECONDS).toMillis());
            errPump.join(Duration.ofSeconds(CHILD_STOP_TIMEOUT_SECONDS).toMillis());
            return new ChildOutcome(exited ? child.exitValue() : -1,
                    out.toByteArray(), err.toByteArray(), exited);
        } catch (IOException cannotStart) {
            throw new IllegalStateException("could not run the application in a child JVM",
                    cannotStart);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running a child JVM", interrupted);
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    /**
     * Copies a child stream into a buffer on a daemon thread.
     *
     * <p>A daemon thread so that a stream which never reaches end-of-file cannot
     * keep this JVM alive after the summary has been printed.
     *
     * @param name   thread name, so a stack dump is readable
     */
    private static Thread drain(InputStream source, ByteArrayOutputStream sink, String name) {
        Thread pump = new Thread(() -> {
            try (InputStream stream = source) {
                stream.transferTo(sink);
            } catch (IOException closed) {
                // Expected when the child is destroyed mid-read; there is nothing
                // useful left to copy and nothing to report.
            }
        }, name);
        pump.setDaemon(true);
        pump.start();
        return pump;
    }

    /**
     * A running {@code --serve} child, with its announced port read back from it.
     *
     * <p>The port is discovered rather than chosen. Picking one in the parent and
     * hoping it is still free when the child binds it is a race that fails rarely
     * and confusingly; giving the child port 0 and reading what the OS gave it
     * cannot race at all. It also lets this run beside the real server, beside a
     * sibling run, and on a busy machine.
     */
    private static final class ServeChild implements AutoCloseable {
        private final Process process;
        private final ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
        private final StringBuilder diagnostics = new StringBuilder();
        private volatile String host;
        private volatile String route;
        private volatile int port = -1;

        private ServeChild(Process process) {
            this.process = process;
            drain(process.getInputStream(), standardOutput, "serve-child-stdout");
            Thread watcher = new Thread(this::readDiagnostics, "serve-child-stderr");
            watcher.setDaemon(true);
            watcher.start();
        }

        /** Reads stderr line by line, capturing everything and parsing the banner. */
        private void readDiagnostics() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (diagnostics) {
                        diagnostics.append(line).append('\n');
                    }
                    Matcher banner = BANNER_PATTERN.matcher(line);
                    if (banner.matches()) {
                        host = banner.group(1);
                        route = banner.group(3);
                        // Written last: it is the field every waiter polls, so
                        // publishing it after the other two makes them visible too.
                        port = Integer.parseInt(banner.group(2));
                    }
                }
            } catch (IOException closed) {
                // Expected when the child is stopped; the banner, if it ever
                // arrived, has already been recorded.
            }
        }

        /**
         * Waits for the child to announce its port.
         */
        boolean awaitBanner() {
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(SERVER_START_TIMEOUT_SECONDS).toNanos();
            while (port < 0 && System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    // The child died. One more poll, in case the banner and the
                    // death raced, then give up rather than wait out the budget.
                    return port >= 0;
                }
                sleepBriefly();
            }
            return port >= 0;
        }

        int port() {
            return port;
        }

        String route() {
            return route;
        }

        String host() {
            return host;
        }

        byte[] standardOutputBytes() {
            return standardOutput.toByteArray();
        }

        String diagnostics() {
            synchronized (diagnostics) {
                return diagnostics.toString();
            }
        }

        boolean alive() {
            return process.isAlive();
        }

        /**
         * Stops the child and waits for it to go. Idempotent, and safe to call on
         * a child that has already exited.
         *
         * <p>The exit STATUS of a terminated child is deliberately never asserted
         * anywhere in this harness: a JVM killed by a signal reports a
         * platform-dependent status, and the three language implementations of this
         * feature legitimately differ there. What matters is that it stopped.
         */
        @Override
        public void close() {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(CHILD_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(CHILD_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    /**
     * Starts a serving child on an OS-chosen port and waits for its banner.
     *
     * @param overrides environment variables for the child, added to a bind on
     *                  loopback and an ephemeral port
     */
    private static ServeChild startServeChild(Map<String, String> overrides) {
        return startServeChild(applicationLaunchPrefix(), overrides);
    }

    /**
     * Starts a serving child from an explicit launch command, on an OS-chosen port.
     *
     * @param overrides environment variables for the child, added to a bind on
     *                  loopback and an ephemeral port
     */
    private static ServeChild startServeChild(List<String> prefix,
            Map<String, String> overrides) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(ENV_APP_HOST, TEST_HOST);
        environment.put(ENV_JAVA_PORT, Integer.toString(EPHEMERAL_PORT));
        environment.putAll(overrides);
        try {
            ServeChild child = new ServeChild(startApplication(prefix, environment, FLAG_SERVE));
            if (!child.awaitBanner()) {
                String diagnostics = child.diagnostics();
                child.close();
                throw new IllegalStateException("the serving child never announced a port: "
                        + diagnostics);
            }
            return child;
        } catch (IOException cannotStart) {
            throw new IllegalStateException("could not start a serving child JVM", cannotStart);
        }
    }

    /** Sleeps one poll interval, preserving the interrupt flag. */
    private static void sleepBriefly() {
        try {
            Thread.sleep(DISPATCHER_POLL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // Section B - the frozen response contract. Four keys, in the order name,
    // version, timestamp, status, compact, with the literal status value UP.
    // Monitoring tools, deployment scripts and humans all come to depend on this
    // shape, so it is pinned here rather than merely described.

    /**
     * Asserts the served document's shape, field formats and byte parity.
     *
     * <p>Two different documents are examined on purpose. The live payload is
     * checked for shape, wiring and field formats, all of which hold whatever the
     * configuration says. The byte-parity constant is checked against a document
     * rendered from the built-in defaults and a fixed timestamp literal, because
     * a length assertion against the live payload would depend on both the clock
     * and the deployed configuration, and would then fail for reasons that have
     * nothing to do with the contract.
     */
    private static void verifyPayloadContract() {
        String body = User.healthPayload();
        String nameField = jsonField(body, "name");
        String versionField = jsonField(body, "version");
        String timestampField = jsonField(body, "timestamp");

        check("the document opens with the name key", body.startsWith("{\"name\":\""));
        checkMatches("the document matches the frozen four-key shape in order",
                PAYLOAD_SHAPE_PATTERN, body);
        check("the key positions increase strictly: name, version, timestamp, status",
                keyOrderIsFrozen(body));
        check("the document carries the literal status UP", body.contains(STATUS_UP_FRAGMENT));
        checkEquals("the status field value", STATUS_UP, jsonField(body, "status"));
        check("the document closes with the status member",
                body.endsWith(",\"status\":\"" + STATUS_UP + "\"}"));
        check("the document contains no whitespace",
                !ANY_WHITESPACE_PATTERN.matcher(body).find());
        check("the document carries no trailing newline", !body.endsWith("\n"));

        check("the name field is non-empty", nameField != null && !nameField.isEmpty());
        checkEquals("the name field is the effective configured name",
                expectedAppName(), nameField);
        checkMatches("the version field is a three-part dotted number",
                VERSION_PATTERN, versionField);
        checkEquals("the version field is the effective configured version",
                expectedAppVersion(), versionField);

        // Format, never value. This is the only non-deterministic field in the
        // document and the first wall-clock dependence in this repository.
        checkMatches("the timestamp field is a fixed-width UTC instant (format only)",
                TIMESTAMP_PATTERN, timestampField);
        checkMatches("the timestamp helper produces the same format (format only)",
                TIMESTAMP_PATTERN, User.timestamp());

        String reference = User.renderPayload(DEFAULT_APP_NAME, DEFAULT_APP_VERSION,
                REFERENCE_TIMESTAMP, STATUS_UP);
        checkEquals("the reference document is byte-identical to the specification",
                REFERENCE_BODY, reference);
        checkEquals("the reference document byte length, the cross-language parity constant",
                REFERENCE_BODY_BYTE_LENGTH,
                reference.getBytes(StandardCharsets.UTF_8).length);

        checkEquals("the renderer keeps the key order with arbitrary values",
                "{\"name\":\"n\",\"version\":\"v\",\"timestamp\":\"t\",\"status\":\"s\"}",
                User.renderPayload("n", "v", "t", "s"));
        check("the served document is wired from the effective name and version",
                body.startsWith("{\"name\":\"" + User.jsonEscape(expectedAppName())
                        + "\",\"version\":\"" + User.jsonEscape(expectedAppVersion())
                        + "\",\"timestamp\":\""));
    }

    /**
     * Reports whether the four keys appear in the one order the contract allows.
     */
    private static boolean keyOrderIsFrozen(String document) {
        int nameAt = document.indexOf("\"name\"");
        int versionAt = document.indexOf("\"version\"");
        int timestampAt = document.indexOf("\"timestamp\"");
        int statusAt = document.indexOf("\"status\"");
        return nameAt >= 0 && nameAt < versionAt && versionAt < timestampAt
                && timestampAt < statusAt;
    }

    /**
     * Extracts one string field from a compact JSON document.
     *
     * <p>A deliberately small reader rather than a parser: the JDK ships no JSON
     * parser, adding one would break the zero-dependency property, and the
     * documents involved are four flat string members produced by this very
     * repository. It reads the first match of {@code "key":"value"} and therefore
     * assumes the value contains no escaped quote, which holds for every
     * configuration this project ships; the escaping of a value that does contain
     * a quote is asserted directly against the escape helper instead.
     *
     * @return the raw field value, or {@code null} when the member is absent
     */
    private static String jsonField(String document, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\":\"([^\"]*)\"")
                .matcher(document);
        return matcher.find() ? matcher.group(1) : null;
    }

    // Section C - JSON escaping. The JDK has no JSON serializer, so User assembles
    // the document by hand, which makes its escape helper load-bearing in a way its
    // Python and JavaScript siblings' json.dumps and JSON.stringify calls are not.
    // The two categories of character it deliberately leaves alone are tested too:
    // over-escaping would break byte parity with those siblings just as surely as
    // under-escaping would break the JSON.

    /** Asserts every escape the contract requires, and every one it forbids. */
    private static void verifyJsonEscaping() {
        checkEquals("a quote is escaped", "a\\\"b", User.jsonEscape("a\"b"));
        checkEquals("a backslash is escaped", "a\\\\b", User.jsonEscape("a\\b"));
        checkEquals("a newline becomes a two-character escape", "a\\nb", User.jsonEscape("a\nb"));
        checkEquals("the newline escape is exactly two characters",
                2, User.jsonEscape("\n").length());
        checkEquals("carriage return, tab, backspace and form feed are escaped",
                "\\r\\t\\b\\f", User.jsonEscape("\r\t\b\f"));
        checkEquals("another control character becomes a unicode escape",
                "a\\u0001b", User.jsonEscape("a\u0001b"));
        checkEquals("the unicode escape is exactly six characters",
                6, User.jsonEscape("\u0001").length());
        checkEquals("a null value is treated as empty", "", User.jsonEscape(null));
        checkEquals("plain text passes through unchanged",
                "only_parent_parent_repo_10_LOC", User.jsonEscape(DEFAULT_APP_NAME));
        checkEquals("a forward slash is deliberately not escaped, for byte parity",
                "a/b", User.jsonEscape("a/b"));
        checkEquals("non-ASCII text is deliberately not escaped, for byte parity",
                "caf\u00e9", User.jsonEscape("caf\u00e9"));
        checkEquals("an escaped value embeds correctly in the rendered document",
                "{\"name\":\"a\\\"b\",\"version\":\"" + DEFAULT_APP_VERSION
                        + "\",\"timestamp\":\"" + REFERENCE_TIMESTAMP
                        + "\",\"status\":\"" + STATUS_UP + "\"}",
                User.renderPayload("a\"b", DEFAULT_APP_VERSION, REFERENCE_TIMESTAMP, STATUS_UP));
    }

    // Section D - path normalisation, which IS the routing decision, so these cases
    // are the route's specification. They are expressed relative to the effective
    // route rather than to the literal /health, so the section keeps testing real
    // behaviour when the route is reconfigured.

    /** Asserts which request paths reach the endpoint and which do not. */
    private static void verifyPathNormalisation() {
        String route = User.healthPath();

        checkEquals("the effective route is the expected configured route",
                expectedHealthPath(), route);
        check("the effective route starts with a slash", route.startsWith(ROOT_PATH));
        checkEquals("the plain route normalises to itself", route, User.normalisePath(route));
        checkEquals("one trailing slash is dropped", route, User.normalisePath(route + "/"));
        checkEquals("a query string is stripped", route, User.normalisePath(route + "?x=1"));
        checkEquals("a fragment is stripped", route, User.normalisePath(route + "#section"));
        checkEquals("a query string and a trailing slash together are handled",
                route, User.normalisePath(route + "/?probe=1"));
        check("two trailing slashes do not match the route",
                !route.equals(User.normalisePath(route + "//")));
        check("the unknown-path fixture genuinely differs from the route",
                !route.equals(UNKNOWN_PATH));
        check("an unknown path does not match the route",
                !route.equals(User.normalisePath(UNKNOWN_PATH)));
        checkEquals("a null path normalises to the root", ROOT_PATH, User.normalisePath(null));
        checkEquals("an empty path normalises to the root", ROOT_PATH, User.normalisePath(""));

        verifyConfiguredRouteReduction();
    }

    /**
     * Asserts the reduction from a CONFIGURED health path to the route served.
     *
     * <p>The same eleven-row table appears in {@code test_app.py} and
     * {@code index.test.js}. It is a cross-language contract rather than three
     * per-runtime opinions: {@code configRoute} is the one function both
     * {@link User#loadConfig} and {@link User#validateConfig} go through, so this
     * table is simultaneously the routing contract and the validation contract and
     * the two cannot drift apart, because they are the same call.
     */
    private static void verifyConfiguredRouteReduction() {
        Map<String, String> reductions = new LinkedHashMap<>();
        reductions.put("/health", "/health");
        reductions.put("health", "/health");
        reductions.put("healthz", "/healthz");
        reductions.put("/health/", "/health");
        reductions.put("/health?probe=1", "/health");
        reductions.put("/health#part", "/health");
        // The leading slash is supplied BEFORE normalisation, so a configured value
        // that looks like an absolute URL is no longer in absolute form by the time
        // the authority would be stripped. All three implementations apply the two
        // steps in this order, which is the part that matters: the value is nonsense
        // either way, and it stays nonsense identically.
        reductions.put("http://host:8000/health", "/http://host:8000/health");
        reductions.put("/", "/");
        reductions.put("//", "/");
        reductions.put("//health", "//health");
        reductions.put("/health//", "/health/");

        for (Map.Entry<String, String> reduction : reductions.entrySet()) {
            checkEquals("a configured path of " + describe(reduction.getKey())
                            + " reduces to " + describe(reduction.getValue()),
                    reduction.getValue(), User.configRoute(reduction.getKey()));
        }
    }

    // Section E - configuration precedence. Environment variable beats properties
    // file beats built-in default, and for the listener port the universal PORT
    // variable beats even the language-specific JAVA_PORT.
    //
    // A JVM's environment is fixed at launch and this harness will not use
    // reflection to forge one, so both proofs run in child JVMs whose variables the
    // harness supplies: one end to end through the served payload, one at resolver
    // level. Neither can be skipped for want of a variable.

    /** Properties key used only to hold a deliberately malformed port value. */
    private static final String KEY_MALFORMED_PORT = "malformed.port";

    /** Properties key used only to hold a deliberately out-of-range port value. */
    private static final String KEY_OUT_OF_RANGE_PORT = "out.of.range.port";

    /**
     * Asserts the precedence chain, the accessors, and the fail-closed port rules.
     */
    private static void verifyConfigurationPrecedence()
            throws IOException, InterruptedException {
        Properties empty = new Properties();
        check("the file-backed configuration is never null", User.configProperties() != null);

        // The accessors, against an independently derived expectation.
        // expectedEffective re-implements the precedence order rather than calling
        // User.resolve, so these are genuine second opinions and not a value being
        // compared with itself.
        checkEquals("appName reports the effective name", expectedAppName(), User.appName());
        checkEquals("appVersion reports the effective version",
                expectedAppVersion(), User.appVersion());
        checkEquals("appHost reports the effective bind address",
                expectedAppHost(), User.appHost());
        Integer expectedPort = expectedJavaPort();
        if (expectedPort == null) {
            // The ambient configuration supplies a port this application must
            // refuse, so there is no effective value for the accessor to report and
            // the assertion is that it refuses. The boolean form is used because the
            // offending value came from the environment and must not be printed.
            boolean refusedAmbientPort;
            try {
                User.javaPort();
                refusedAmbientPort = false;
            } catch (IllegalArgumentException rejected) {
                refusedAmbientPort = true;
            }
            check("javaPort refuses an unusable configured port", refusedAmbientPort);
        } else {
            checkEquals("javaPort reports the effective listener port",
                    expectedPort, User.javaPort());
        }

        // Built-in defaults, with both other layers provably absent.
        checkEquals("the built-in default applies with an empty file and no override",
                DEFAULT_APP_NAME,
                User.resolve(empty, KEY_APP_NAME, ABSENT_ENV_PRIMARY, DEFAULT_APP_NAME));
        checkEquals("the built-in default applies with a null file configuration",
                DEFAULT_APP_VERSION,
                User.resolve(null, KEY_APP_VERSION, ABSENT_ENV_PRIMARY, DEFAULT_APP_VERSION));
        checkEquals("the built-in default host is the wildcard address",
                DEFAULT_APP_HOST,
                User.resolve(empty, KEY_APP_HOST, ABSENT_ENV_PRIMARY, DEFAULT_APP_HOST));
        checkEquals("the built-in default route is the documented one",
                DEFAULT_HEALTH_PATH,
                User.resolve(empty, KEY_HEALTH_PATH, ABSENT_ENV_PRIMARY, DEFAULT_HEALTH_PATH));
        checkEquals("the built-in default port applies with an empty file",
                DEFAULT_JAVA_PORT, User.resolvePort(empty, KEY_JAVA_PORT,
                        ABSENT_ENV_PRIMARY, ABSENT_ENV_SECONDARY, DEFAULT_JAVA_PORT));

        // An empty value must count as absent at every layer, so that exporting
        // an empty variable cannot blank out a configured value.
        Properties blank = new Properties();
        blank.setProperty(KEY_APP_NAME, "");
        checkEquals("an empty file value is treated as absent",
                DEFAULT_APP_NAME,
                User.resolve(blank, KEY_APP_NAME, ABSENT_ENV_PRIMARY, DEFAULT_APP_NAME));

        // The shipped file agrees with the built-in defaults.
        verifyShippedConfigurationAgrees();

        // File over default, and the port rules, through a real file.
        verifyFileBackedPrecedence();
    }

    /**
     * Asserts that the shipped configuration file and the built-in defaults do
     * not disagree, so that the single source of truth really is single.
     *
     * <p>The file is located with {@link #locateBeside}, which tries the launched
     * source file's own directory first, so the assertions below run from any
     * working directory and a run started from elsewhere cannot skip them. A file
     * that cannot be located at all is a counted failure rather than a note: this
     * file is the single source of truth the version-agreement gate rests on, so
     * its absence is a defect in the repository, not a property of the run.
     */
    private static void verifyShippedConfigurationAgrees() throws IOException {
        Path location = locateBeside(CONFIG_FILE_NAME);
        check("the shipped configuration file was located", location != null);
        if (location == null) {
            return;
        }
        Properties shipped = loadProperties(location);
        checkEquals("the shipped file agrees with the built-in default name",
                DEFAULT_APP_NAME, shipped.getProperty(KEY_APP_NAME));
        checkEquals("the shipped file agrees with the built-in default version",
                DEFAULT_APP_VERSION, shipped.getProperty(KEY_APP_VERSION));
        checkEquals("the shipped file agrees with the built-in default route",
                DEFAULT_HEALTH_PATH, shipped.getProperty(KEY_HEALTH_PATH));
        checkEquals("the shipped file agrees with the built-in default host",
                DEFAULT_APP_HOST, shipped.getProperty(KEY_APP_HOST));
        checkEquals("the shipped file agrees with the built-in default port",
                Integer.toString(DEFAULT_JAVA_PORT), shipped.getProperty(KEY_JAVA_PORT));
    }

    /**
     * Writes a real properties file, loads it, and asserts the layers above the
     * built-in defaults.
     *
     * <p>The file is created under the JVM's temporary directory - never inside
     * the repository - and is deleted in a {@code finally} block, because the
     * working tree must be clean including untracked files after a full test
     * cycle. Its removal is itself asserted.
     */
    private static void verifyFileBackedPrecedence() throws IOException, InterruptedException {
        String fileName = "name-from-temporary-file";
        int filePort = 19002;
        Path tempConfig = Files.createTempFile("usertest-config-", ".properties");
        try {
            try (Writer writer = Files.newBufferedWriter(tempConfig, StandardCharsets.UTF_8)) {
                writer.write(KEY_APP_NAME + "=" + fileName + "\n");
                writer.write(KEY_JAVA_PORT + "=" + filePort + "\n");
                writer.write(KEY_MALFORMED_PORT + "=not-a-port\n");
                writer.write(KEY_OUT_OF_RANGE_PORT + "=" + (MAX_PORT + 1) + "\n");
            }
            Properties fromFile = loadProperties(tempConfig);

            checkEquals("the temporary file was parsed", fileName,
                    fromFile.getProperty(KEY_APP_NAME));
            checkEquals("a file value overrides the built-in default", fileName,
                    User.resolve(fromFile, KEY_APP_NAME, ABSENT_ENV_PRIMARY, DEFAULT_APP_NAME));
            checkEquals("a file port overrides the built-in default port", filePort,
                    User.resolvePort(fromFile, KEY_JAVA_PORT, ABSENT_ENV_PRIMARY,
                            ABSENT_ENV_SECONDARY, DEFAULT_JAVA_PORT));

            // Fail-closed port handling: an unusable setting is REFUSED, naming the
            // offending value, rather than silently replaced by the documented
            // default. Substituting a port is the worse of the two failures - an
            // operator who mistypes it would get a healthy-looking process
            // listening somewhere nobody is watching - and refusing it is also what
            // makes this implementation agree with app.py, which raises on the same
            // input.
            checkRejects("a malformed port is refused, naming the offending value",
                    "not-a-port", () -> User.resolvePort(fromFile, KEY_MALFORMED_PORT,
                            ABSENT_ENV_PRIMARY, ABSENT_ENV_SECONDARY, DEFAULT_JAVA_PORT));
            checkRejects("an out-of-range port is refused, naming the offending value",
                    Integer.toString(MAX_PORT + 1),
                    () -> User.resolvePort(fromFile, KEY_OUT_OF_RANGE_PORT,
                            ABSENT_ENV_PRIMARY, ABSENT_ENV_SECONDARY, DEFAULT_JAVA_PORT));

            verifyEnvironmentBeatsFile(fromFile, filePort);
        } finally {
            Files.deleteIfExists(tempConfig);
        }
        check("the temporary configuration file was removed", !Files.exists(tempConfig));
    }

    /**
     * Loads a properties file as UTF-8, matching how the application reads it.
     */
    private static Properties loadProperties(Path location) throws IOException {
        Properties parsed = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(location, StandardCharsets.UTF_8)) {
            parsed.load(reader);
        }
        return parsed;
    }

    /**
     * Proves environment-over-file precedence end to end, in a child JVM.
     *
     * <p>A properties file is written saying one thing, a child is started with
     * environment variables saying another, and the SERVED PAYLOAD decides which
     * won - so the assertion is about the application's real configuration path,
     * not about a resolver method called in isolation.
     *
     * <ul>
     *   <li>Name and version come from the environment, and the file's values
     *       appear nowhere in the response.</li>
     *   <li>The route comes from the environment: the environment's path answers
     *       200 and the FILE's path answers 404. Asserting both directions is what
     *       distinguishes "the environment won" from "both paths happen to work".</li>
     *   <li>The universal PORT variable outranks the language-specific one, proven
     *       with an UNBINDABLE {@code JAVA_PORT}: if that were consulted first the
     *       child would refuse to start, so the fact that it binds is the proof. No
     *       port has to be reserved and nothing can race.</li>
     * </ul>
     *
     * <p>A second child, given the file but NO overriding variables, closes the
     * chain from the other end by serving the file's values - which proves the file
     * layer is genuinely being read, and therefore that the first child's result is
     * precedence rather than the file simply being ignored.
     *
     * <p>The file is delivered by copying the application into a temporary directory
     * and writing the properties file beside the copy, because that is how the file
     * layer is actually reached. See {@link #isolatedLaunchPrefix}. The child's
     * working directory is left pointing at the repository, so a regression in
     * code-source resolution would serve the repository's values and fail every
     * assertion below rather than quietly passing on a different file.
     */
    private static void verifyEnvironmentBeatsFileInChild() throws IOException,
            InterruptedException {
        String fileName = "name-from-the-file-layer";
        String fileVersion = "9.9.9";
        String filePath = "/route-from-the-file-layer";
        int filePort = 19003;
        String environmentName = "name-from-the-environment-layer";
        String environmentVersion = "2.34.5";
        String environmentPath = "/route-from-the-environment-layer";

        Path enclosure = Files.createTempDirectory("usertest-precedence-");
        try {
            List<String> launch = isolatedLaunchPrefix(enclosure);
            Path config = enclosure.resolve(CONFIG_FILE_NAME);
            try (Writer writer = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) {
                writer.write(KEY_APP_NAME + "=" + fileName + "\n");
                writer.write(KEY_APP_VERSION + "=" + fileVersion + "\n");
                writer.write(KEY_HEALTH_PATH + "=" + filePath + "\n");
                writer.write(KEY_APP_HOST + "=" + TEST_HOST + "\n");
                writer.write(KEY_JAVA_PORT + "=" + filePort + "\n");
            }
            check("the isolated configuration file sits beside the isolated application",
                    Files.isReadable(config));

            // The environment layer, with an unbindable language port present so
            // that the universal override has something to demonstrably outrank.
            Map<String, String> overrides = new LinkedHashMap<>();
            overrides.put(ENV_APP_NAME, environmentName);
            overrides.put(ENV_APP_VERSION, environmentVersion);
            overrides.put(ENV_HEALTH_PATH, environmentPath);
            overrides.put(ENV_UNIVERSAL_PORT, Integer.toString(EPHEMERAL_PORT));
            overrides.put(ENV_JAVA_PORT, OUT_OF_RANGE_PORT_VALUE);

            try (ServeChild child = startServeChild(launch, overrides)) {
                check("the universal PORT override outranks an unusable language port",
                        child.port() > MIN_PORT && child.port() <= MAX_PORT);
                checkEquals("the environment route is the one the child serves",
                        environmentPath, child.route());
                checkEquals("the file port was outranked and never bound",
                        false, child.port() == filePort);

                HttpResponse<String> served = fetch(child.port(), environmentPath);
                checkEquals("the environment route answers 200", HTTP_OK, served.statusCode());
                checkEquals("an environment name outranks a file name",
                        environmentName, jsonField(served.body(), "name"));
                checkEquals("an environment version outranks a file version",
                        environmentVersion, jsonField(served.body(), "version"));
                check("no value from the file layer appears in the response",
                        !served.body().contains(fileName)
                                && !served.body().contains(fileVersion));

                // The other direction. Without this, "the environment won" and
                // "both routes answer" would be indistinguishable.
                checkEquals("the outranked file route answers 404",
                        HTTP_NOT_FOUND, fetch(child.port(), filePath).statusCode());
            }

            // The file layer, proven to be read at all - which is what makes the
            // result above precedence rather than the file being ignored.
            Map<String, String> fileOnly = new LinkedHashMap<>();
            fileOnly.put(ENV_JAVA_PORT, Integer.toString(EPHEMERAL_PORT));
            try (ServeChild child = startServeChild(launch, fileOnly)) {
                checkEquals("with no override the file route is served", filePath, child.route());
                HttpResponse<String> served = fetch(child.port(), filePath);
                checkEquals("the file route answers 200", HTTP_OK, served.statusCode());
                checkEquals("a file name outranks the built-in default name",
                        fileName, jsonField(served.body(), "name"));
                checkEquals("a file version outranks the built-in default version",
                        fileVersion, jsonField(served.body(), "version"));
                checkEquals("the built-in default route is outranked by the file route",
                        HTTP_NOT_FOUND, fetch(child.port(), DEFAULT_HEALTH_PATH).statusCode());
            }
        } finally {
            deleteRecursively(enclosure);
        }
        check("the precedence enclosure was removed", !Files.exists(enclosure));
    }

    /**
     * Source of the throwaway class that exercises {@code User}'s resolvers directly
     * in a child JVM whose environment this harness supplies.
     *
     * <p>It is compiled from source beside a copy of {@code User.java}, so the source
     * launcher resolves the sibling class with no classpath and no build step - the
     * same mechanism that lets {@code java UserTest.java} run at all. Its output is
     * {@code key=value} lines, so the parent asserts values it computed itself rather
     * than trusting a self-report of success.
     *
     * <p>Argument order is fixed and documented at the call site.
     */
    private static final String RESOLVER_PROBE_SOURCE = String.join("\n",
            "import java.util.Properties;",
            "",
            "public class ResolverProbe {",
            "    public static void main(String[] args) {",
            "        Properties fromFile = new Properties();",
            "        fromFile.setProperty(args[0], args[1]);",
            "        fromFile.setProperty(args[2], args[3]);",
            "        System.out.println(\"resolve.withEnvironment=\"",
            "                + User.resolve(fromFile, args[0], args[4], \"BUILT-IN\"));",
            "        System.out.println(\"resolve.withoutEnvironment=\"",
            "                + User.resolve(fromFile, args[0], \"" + ABSENT_ENV_PRIMARY + "\","
                    + " \"BUILT-IN\"));",
            "        System.out.println(\"resolvePort.universalWins=\"",
            "                + User.resolvePort(fromFile, args[2], \"" + ABSENT_ENV_PRIMARY + "\",",
            "                        args[5], 1));",
            "        System.out.println(\"resolvePort.fileWinsAlone=\"",
            "                + User.resolvePort(fromFile, args[2], \"" + ABSENT_ENV_PRIMARY + "\",",
            "                        \"" + ABSENT_ENV_SECONDARY + "\", 1));",
            "        String refused;",
            "        try {",
            "            User.resolvePort(fromFile, args[2], \"" + ABSENT_ENV_PRIMARY + "\",",
            "                    args[6], 1);",
            "            refused = \"false\";",
            "        } catch (IllegalArgumentException rejected) {",
            "            refused = \"true\";",
            "        }",
            "        System.out.println(\"resolvePort.refusesUnusableUniversal=\" + refused);",
            "    }",
            "}",
            "");

    /**
     * Proves, unconditionally, that {@code User}'s resolvers consult the environment
     * before the file - and that they fall back to the file when it is absent.
     *
     * <p>The variables are supplied by this harness to a child, so their presence is
     * guaranteed by construction and their values are this harness's own invented
     * strings, which are safe to report in a failure.
     *
     * <p>Five properties are asserted from one child: an environment value outranks a
     * file value; the same call falls back to the file value when the variable is
     * absent, which is what makes the first result precedence rather than the file
     * being ignored; a usable universal port outranks the file port; the file port is
     * used when no universal variable is supplied; and an unusable universal port is
     * REFUSED rather than silently falling through to the file, which proves the
     * universal variable was consulted first.
     *
     * @param fromFile the file-backed configuration the caller built, used only for its
     *                 key names so the child asserts the same keys the parent does
     */
    private static void verifyResolverPrefersEnvironmentInChild(Properties fromFile, int filePort)
            throws IOException {
        String fileName = "name-from-the-file-layer";
        String environmentName = "name-from-the-environment-layer";
        int universalPort = 19004;
        String nameVariable = "USERTEST_PROBE_NAME";
        String portVariable = "USERTEST_PROBE_PORT";
        String badPortVariable = "USERTEST_PROBE_BAD_PORT";

        check("the caller's file configuration supplies the keys this child resolves",
                fromFile.getProperty(KEY_APP_NAME) != null
                        && fromFile.getProperty(KEY_JAVA_PORT) != null);

        Path enclosure = Files.createTempDirectory("usertest-resolver-");
        try {
            Path application = locateBeside(APPLICATION_SOURCE_FILE);
            if (application == null) {
                // Fail closed rather than skip: the whole point of this method is that
                // it cannot be skipped, so an unlocatable source is a counted failure.
                check("the application source can be located for the resolver child", false);
                return;
            }
            Files.copy(application, enclosure.resolve(APPLICATION_SOURCE_FILE));
            Path probe = enclosure.resolve("ResolverProbe.java");
            Files.writeString(probe, RESOLVER_PROBE_SOURCE, StandardCharsets.UTF_8);

            Map<String, String> environment = new LinkedHashMap<>();
            environment.put(nameVariable, environmentName);
            environment.put(portVariable, Integer.toString(universalPort));
            environment.put(badPortVariable, OUT_OF_RANGE_PORT_VALUE);

            ChildOutcome outcome = runApplication(
                    List.of(javaLauncher(), probe.toString()), enclosure,
                    List.of(KEY_APP_NAME, fileName, KEY_JAVA_PORT, Integer.toString(filePort),
                            nameVariable, portVariable, badPortVariable),
                    environment);
            checkEquals("the resolver child exited 0", 0, outcome.status());

            Map<String, String> reported = new LinkedHashMap<>();
            for (String line : new String(outcome.standardOutput(), StandardCharsets.UTF_8)
                    .split("\n")) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    reported.put(line.substring(0, separator), line.substring(separator + 1).trim());
                }
            }

            checkEquals("an environment value outranks a file value",
                    environmentName, reported.get("resolve.withEnvironment"));
            checkEquals("the same call falls back to the file value when the variable is absent",
                    fileName, reported.get("resolve.withoutEnvironment"));
            checkEquals("a usable universal port outranks a file port",
                    Integer.toString(universalPort), reported.get("resolvePort.universalWins"));
            checkEquals("the file port is used when no universal variable is supplied",
                    Integer.toString(filePort), reported.get("resolvePort.fileWinsAlone"));
            checkEquals("an unusable universal port is refused rather than falling through",
                    "true", reported.get("resolvePort.refusesUnusableUniversal"));
        } finally {
            deleteRecursively(enclosure);
        }
        check("the resolver enclosure was removed", !Files.exists(enclosure));
    }

    /**
     * Removes a directory and everything in it, reporting nothing.
     *
     * <p>Used only to clean up a temporary enclosure this harness created itself, so
     * a failure to remove a file is not a test failure: the next run creates a fresh
     * directory with a fresh name and the operating system reclaims the rest. The
     * assertion that the enclosure is gone is made by the caller, which is where a
     * genuine leak would matter.
     */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var entries = Files.walk(root)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException stillInUse) {
                    // A file the operating system has not released yet. The
                    // enclosure name is unique per run, so nothing later depends
                    // on this one being gone.
                }
            }
        }
    }

    /**
     * Issues a GET against a child server on loopback, with its own client.
     *
     * <p>A fresh client per call, closed immediately: a client held open would keep
     * its connection pool - and the child's connection - alive past the point where
     * the child is expected to have gone.
     */
    private static HttpResponse<String> fetch(int port, String path) throws IOException,
            InterruptedException {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CLIENT_TIMEOUT_SECONDS))
                .build()) {
            return send(client, "GET", port, path);
        }
    }

    /**
     * Asserts that the environment layer outranks the file layer, without mutating
     * the environment of this process and without reflection.
     *
     * <p>Two independent and unconditional proofs, and the split is the whole design.
     * {@link #verifyEnvironmentBeatsFileInChild} is end to end: it starts a child JVM
     * with a properties file saying one thing and environment variables saying
     * another, and reads the served payload to see which won.
     * {@link #verifyResolverPrefersEnvironmentInChild} is unit-level: it exercises
     * {@code User.resolve} and {@code User.resolvePort} directly in a child JVM whose
     * variables this harness supplies. Because the harness supplies both environments,
     * neither proof depends on anything the host happens to have exported, neither can
     * be skipped, and every value involved is safe to report.
     */
    private static void verifyEnvironmentBeatsFile(Properties fromFile, int filePort)
            throws IOException, InterruptedException {
        // Unconditional, and therefore the proof this section relies on.
        verifyEnvironmentBeatsFileInChild();

        // The resolver-level proof, and it is UNCONDITIONAL: the variables are
        // supplied by this harness to a child JVM, so there is nothing to skip.
        verifyResolverPrefersEnvironmentInChild(fromFile, filePort);

    }

    // Expectation helpers. These re-derive the precedence order independently of
    // User.resolve: an expectation computed by calling the method under test would
    // assert only that a value equals itself.

    /**
     * Re-derives one effective value from the documented precedence order.
     *
     * <p>Reads the environment but never writes it.
     */
    private static String expectedEffective(String environmentName, String propertiesKey,
            String builtInDefault) {
        String fromEnvironment = System.getenv(environmentName);
        if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
            return fromEnvironment;
        }
        String fromFile = User.configProperties().getProperty(propertiesKey);
        if (fromFile != null && !fromFile.isEmpty()) {
            return fromFile;
        }
        return builtInDefault;
    }

    private static String expectedAppName() {
        return expectedEffective(ENV_APP_NAME, KEY_APP_NAME, DEFAULT_APP_NAME);
    }

    private static String expectedAppVersion() {
        return expectedEffective(ENV_APP_VERSION, KEY_APP_VERSION, DEFAULT_APP_VERSION);
    }

    private static String expectedAppHost() {
        return expectedEffective(ENV_APP_HOST, KEY_APP_HOST, DEFAULT_APP_HOST);
    }

    /**
     * Re-derives the effective route, including the normalisation the application
     * applies to a configured value.
     *
     * <p>Deliberately hand-written rather than delegating to
     * {@code User.normalisePath}, so that the route assertion is an independent
     * opinion about both the precedence order and the normalisation.
     */
    private static String expectedHealthPath() {
        String configured = expectedEffective(ENV_HEALTH_PATH, KEY_HEALTH_PATH,
                DEFAULT_HEALTH_PATH);
        String path = configured.startsWith(ROOT_PATH) ? configured : ROOT_PATH + configured;
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
     * Re-derives the effective listener port, including the universal override.
     *
     * <p>Port resolution is fail-closed: a configured value that is not a legal
     * port is refused rather than replaced by the built-in default, so there is no
     * effective port to re-derive in that case. {@code null} reports exactly that,
     * and the caller then asserts a refusal instead of a value.
     *
     * @return the port the listener must bind, or {@code null} when the ambient
     *         configuration supplies a value the application must refuse
     */
    private static Integer expectedJavaPort() {
        String universal = System.getenv(ENV_UNIVERSAL_PORT);
        String raw = (universal != null && !universal.isEmpty())
                ? universal
                : expectedEffective(ENV_JAVA_PORT, KEY_JAVA_PORT,
                        Integer.toString(DEFAULT_JAVA_PORT));
        return isParsablePort(raw) ? Integer.valueOf(raw.trim()) : null;
    }

    /**
     * Reports whether a raw value is a usable port number.
     *
     * @param raw the value to test, possibly {@code null}
     */
    private static boolean isParsablePort(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed >= MIN_PORT && parsed <= MAX_PORT;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /**
     * Re-derives the byte length of the document a LIVE listener in this process
     * will serve, under the configuration this process actually resolved.
     *
     * <p>{@link #REFERENCE_BODY_BYTE_LENGTH} is the cross-language parity constant
     * and it belongs to the document rendered from the SHIPPED name and version,
     * which is where section B asserts it from explicit arguments. Using it as the
     * expectation for a served answer as well silently adds a second claim - that
     * the effective configuration IS the shipped one - and that claim is false the
     * moment anything in the environment overrides {@code APP_NAME} or
     * {@code APP_VERSION} with a value of a different length. A JVM cannot unset
     * its own environment the way {@code test_app.py} and {@code index.test.js}
     * can, so the expectation is re-derived here instead: same precedence order,
     * same renderer, no ambient assumption. A CI runner, a container or a
     * developer shell that exports either variable then changes the answer and the
     * expectation together, and the framing assertion keeps testing framing.
     *
     * <p>Deterministic despite naming a live payload: {@code timestamp} is the only
     * non-deterministic field and it is always the same fixed width, so
     * {@link #REFERENCE_TIMESTAMP} stands in for the clock reading exactly. Length
     * is still asserted as an absolute number rather than against the body that
     * came back - a response whose declared length disagreed with the bytes it sent
     * is precisely what these checks exist to catch.
     *
     * @return the exact number of UTF-8 bytes a contract answer must carry here
     */
    private static int expectedServedBodyByteLength() {
        return User.renderPayload(expectedAppName(), expectedAppVersion(),
                        REFERENCE_TIMESTAMP, STATUS_UP)
                .getBytes(StandardCharsets.UTF_8).length;
    }

    // Section F - live routing over a real socket. Unit checks prove the pieces;
    // only a real request proves the wiring. The server is bound on loopback with
    // port 0, so the OS chooses a free port: the documented default 8002 is never
    // bound here, which is what lets this harness run twice at once, run beside a
    // real server, and run on a busy machine without a bind collision. The chosen
    // port is read back from the server rather than assumed.

    /**
     * Asserts the success path, both negative paths and the self-check, live.
     *
     * <p>Every header assertion is case-insensitive, and that is a correctness
     * requirement rather than caution: RFC 9110 makes response field names
     * case-insensitive, so an implementation is free to normalise their casing and
     * a case-sensitive assertion would be asserting something the protocol does not
     * promise. {@code HttpHeaders.firstValue} performs a case-insensitive lookup,
     * so it is used for every header read below; that keeps this harness asserting
     * the same contract the Python and JavaScript suites assert, and keeps it
     * correct if the header block is ever emitted by a different writer.
     */
    private static void verifyLiveRouting() throws IOException, InterruptedException {
        // The expectation sets themselves are asserted before any response is read,
        // so every direct set-equality check further down inherits the guarantee:
        // the set this implementation is held to is the three the contract specifies
        // plus exactly one named, documented deviation - never a fourth field that
        // was quietly appended to an expectation to make a suite go green.
        checkSetEquals("the specified header set is the three fields the contract names",
                Set.of("content-type", "cache-control", "content-length"),
                SPECIFIED_HEADER_NAMES);
        checkSetEquals("the stated deviation is exactly one field, and it is date",
                Set.of("date"), DEVIATION_HEADER_NAMES);
        checkSetEquals("the 200/404 expectation is the specified set plus the deviation",
                union(SPECIFIED_HEADER_NAMES, DEVIATION_HEADER_NAMES), CONTRACT_HEADER_NAMES);
        Set<String> unspecified = new TreeSet<>(CONTRACT_HEADER_NAMES);
        unspecified.removeAll(SPECIFIED_HEADER_NAMES);
        checkSetEquals("the 200/404 expectation departs from the contract in one place only",
                DEVIATION_HEADER_NAMES, unspecified);
        Set<String> refusalExtra = new TreeSet<>(REFUSAL_HEADER_NAMES);
        refusalExtra.removeAll(CONTRACT_HEADER_NAMES);
        checkSetEquals("a refusal adds Allow and nothing else",
                Set.of("allow"), refusalExtra);

        String route = User.healthPath();
        User.HealthServer server = User.startServer(TEST_HOST, EPHEMERAL_PORT);
        int port = server.port();
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CLIENT_TIMEOUT_SECONDS))
                .build()) {
            check("the test server bound a usable port", port > MIN_PORT && port <= MAX_PORT);
            check("the test server bound an ephemeral port, not the documented default",
                    port != DEFAULT_JAVA_PORT);
            check("the running server has a dispatcher thread", dispatcherThreadCount() > 0);

            HttpResponse<String> healthy = get(client, port, route);
            checkEquals("GET the route answers 200", HTTP_OK, healthy.statusCode());
            checkMatches("the served document matches the frozen four-key shape",
                    PAYLOAD_SHAPE_PATTERN, healthy.body());
            check("the served document reports status UP",
                    healthy.body().contains(STATUS_UP_FRAGMENT));
            checkMatches("the served timestamp matches the format (format only)",
                    TIMESTAMP_PATTERN, jsonField(healthy.body(), "timestamp"));
            checkFrozenHeaders(healthy, CONTRACT_HEADER_NAMES,
                    healthy.body().getBytes(StandardCharsets.UTF_8).length, "200");

            checkEquals("GET the route with one trailing slash answers 200",
                    HTTP_OK, get(client, port, route + "/").statusCode());
            checkEquals("GET the route with a query string answers 200",
                    HTTP_OK, get(client, port, route + "?probe=1").statusCode());
            checkEquals("GET the route with two trailing slashes answers 404",
                    HTTP_NOT_FOUND, get(client, port, route + "//").statusCode());

            HttpResponse<String> missing = get(client, port, UNKNOWN_PATH);
            checkEquals("GET an unknown path answers 404",
                    HTTP_NOT_FOUND, missing.statusCode());
            checkEquals("the 404 body is the fixed error document",
                    BODY_NOT_FOUND, missing.body());
            // The negative paths get the SAME complete treatment as the success
            // path. An error response is the one most likely to be written by a
            // different code path and the one least likely to be inspected, which
            // is precisely why disclosure and cache regressions surface there
            // first if nobody is asserting them.
            checkFrozenHeaders(missing, CONTRACT_HEADER_NAMES,
                    BODY_NOT_FOUND.getBytes(StandardCharsets.UTF_8).length, "404");

            HttpResponse<String> posted = send(client, "POST", port, route);
            checkEquals("POST the route answers 405",
                    HTTP_METHOD_NOT_ALLOWED, posted.statusCode());
            checkEquals("the 405 body is the fixed error document",
                    BODY_METHOD_NOT_ALLOWED, posted.body());
            checkEquals("the 405 names GET as the only allowed method",
                    Optional.of("GET"), posted.headers().firstValue("allow"));
            checkFrozenHeaders(posted, REFUSAL_HEADER_NAMES,
                    BODY_METHOD_NOT_ALLOWED.getBytes(StandardCharsets.UTF_8).length, "405");

            // Every other method is refused identically, and the refusal carries
            // the same header set each time. Sampling one verb would leave the
            // rest free to answer differently.
            for (String method : new String[] {"PUT", "DELETE", "PATCH", "OPTIONS"}) {
                HttpResponse<String> refused = send(client, method, port, route);
                checkEquals(method + " the route answers 405",
                        HTTP_METHOD_NOT_ALLOWED, refused.statusCode());
                checkEquals("the 405 answer to " + method + " names GET as allowed",
                        Optional.of("GET"), refused.headers().firstValue("allow"));
                checkSetEquals("the 405 answer to " + method + " carries exactly the refusal set",
                        REFUSAL_HEADER_NAMES, foldedHeaderNames(refused));
            }

            // HEAD answering 405 is a documented deviation from RFC 9110's
            // expectation that HEAD is supported wherever GET is, not an
            // oversight, and the answer correctly carries no body. The header
            // block is asserted in full anyway: this is the one response whose
            // declared length deliberately disagrees with the bytes sent, and the
            // frozen field set is what proves the disagreement is the documented
            // one rather than a framing defect. The set is the SAME set every
            // other refused method gets, Content-Length included, which is the
            // RFC 9110 section 9.3.2 expectation; measured on the wire, this
            // implementation's HEAD block equals app.py's and index.js's field for
            // field APART FROM the Date this transport inserts unconditionally,
            // which is why the expectation is built from the specified set plus
            // that one named deviation rather than written out flat.
            HttpResponse<String> headed = send(client, "HEAD", port, route);
            checkEquals("HEAD is answered 405 by documented design",
                    HTTP_METHOD_NOT_ALLOWED, headed.statusCode());
            checkEquals("the 405 answer to HEAD carries no body", 0, headed.body().length());
            checkFrozenHeaders(headed, REFUSAL_HEADER_NAMES,
                    BODY_METHOD_NOT_ALLOWED.getBytes(StandardCharsets.UTF_8).length,
                    "405 (HEAD)");
            checkEquals("HEAD on an unknown path is refused before the route is consulted",
                    HTTP_METHOD_NOT_ALLOWED,
                    send(client, "HEAD", port, UNKNOWN_PATH).statusCode());

            checkEquals("the self-check grades a live endpoint healthy",
                    EXIT_SUCCESS, User.probe(TEST_HOST, port, route));
            checkEquals("the self-check probes a wildcard bind over loopback",
                    EXIT_SUCCESS, User.probe(DEFAULT_APP_HOST, port, route));
        } finally {
            server.stop();
        }

        check("stopping the server releases its non-daemon dispatcher thread",
                awaitNoDispatcherThread());
        announceExpectedDiagnostic("[User] probe could not reach http://"
                + TEST_HOST + ":" + port + route);
        checkEquals("the self-check fails closed once the endpoint is gone",
                EXIT_FAILURE, User.probe(TEST_HOST, port, route));
    }

    /**
     * Issues a GET request against the test server.
     */
    private static HttpResponse<String> get(HttpClient client, int port, String path)
            throws IOException, InterruptedException {
        return send(client, "GET", port, path);
    }

    /**
     * Issues a request with an explicit method and an empty body.
     */
    private static HttpResponse<String> send(HttpClient client, String method, int port,
            String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://" + TEST_HOST + ":" + port + path))
                .timeout(Duration.ofSeconds(CLIENT_TIMEOUT_SECONDS))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Reports whether a response header contains a fragment, case-insensitively
     * in the field NAME.
     */
    private static boolean headerContains(HttpResponse<String> response, String name,
            String fragment) {
        return response.headers().firstValue(name)
                .filter(value -> value.contains(fragment))
                .isPresent();
    }

    /**
     * Every response field name, lower-cased, as one set.
     *
     * <p>Folding is done explicitly rather than relied upon. RFC 9110 makes field
     * names case-insensitive, so an implementation may normalise their casing and a
     * client may report them in whatever casing it likes; this project's own
     * implementations differ from one another for exactly that reason. Folding here
     * is what lets this harness assert the same contract the Python and JavaScript
     * suites assert, and what keeps the assertion correct if either the server's
     * writer or the client's reporting ever changes casing.
     */
    private static Set<String> foldedHeaderNames(HttpResponse<?> response) {
        Set<String> folded = new TreeSet<>();
        for (String name : response.headers().map().keySet()) {
            folded.add(name.toLowerCase(Locale.ROOT));
        }
        return folded;
    }

    /**
     * Asserts a response's complete header block: exactly which fields, the media
     * type, every cache directive, an accurate length, and nothing disclosed.
     *
     * <p>The set EQUALITY does the most work, for the reason recorded on
     * {@link #CONTRACT_HEADER_NAMES}: it proves the required fields arrived AND that
     * nothing else did, in one comparison that cannot be satisfied by accident.
     *
     * <p>{@code Server} is then named individually as well. That is redundant against
     * the set equality and kept deliberately: it is the specific disclosure
     * requirement, so a reader of the log sees it asserted by name, and if the
     * expected set is ever widened the named check still holds the line.
     *
     * <p>{@code Date} is asserted by FORMAT rather than by absence, because the server
     * writes the field itself and application code cannot suppress it, RFC 9110
     * section 6.6.1 says an origin server SHOULD send it, and its value is a clock
     * reading - so the only assertion that can be both meaningful and stable is that
     * the field is present and well formed.
     *
     * <p>All three cache directives are required, not just one. They do different
     * jobs - a stale health answer is worse than no answer, and a response merely
     * marked {@code no-cache} may still be written to a shared store - so asserting
     * only the presence of the header, or only one directive, would let a real
     * regression through.
     */
    private static void checkFrozenHeaders(HttpResponse<String> response,
            Set<String> expectedNames, int declaredLength, String label) {
        checkSetEquals(label + ": carries exactly the frozen header fields, case-folded",
                expectedNames, foldedHeaderNames(response));
        checkEquals(label + ": declares JSON content",
                Optional.of(CONTENT_TYPE_JSON), response.headers().firstValue("Content-Type"));
        for (String directive : CACHE_DIRECTIVES) {
            check(label + ": the cache header carries " + directive,
                    headerContains(response, "Cache-Control", directive));
        }
        checkEquals(label + ": declares an accurate Content-Length",
                Optional.of(Integer.toString(declaredLength)),
                response.headers().firstValue("content-length"));
        checkMatches(label + ": the runtime's Date is a well-formed HTTP-date (format only)",
                HTTP_DATE_PATTERN, response.headers().firstValue("Date").orElse(""));
        check(label + ": discloses no Server banner",
                response.headers().firstValue("Server").isEmpty());
        checkDeviationIsBounded(response, expectedNames, label);
    }

    /**
     * Asserts that this implementation's departure from the specified header set is
     * EXACTLY the one stated deviation, and nothing more.
     *
     * <p>The point of this check is to stop the harness from ratifying the
     * divergence it is documenting. Asserting only that the response carries
     * {@code CONTRACT_HEADER_NAMES} would silently accept whatever that set happened
     * to contain; this instead computes, from the response actually received, the
     * field names that the contract does NOT specify, and requires that set to equal
     * {@link #DEVIATION_HEADER_NAMES} - one field, named, with its reason recorded.
     * A second unspecified field appearing later fails here even if someone had
     * already added it to the expectation set above.
     */
    private static void checkDeviationIsBounded(HttpResponse<String> response,
            Set<String> expectedNames, String label) {
        Set<String> allowed = new TreeSet<>(SPECIFIED_HEADER_NAMES);
        if (expectedNames.contains("allow")) {
            // Allow is specified by the contract for a refusal: 405 must name the
            // methods it permits, so it is not a deviation on that response class.
            allowed.add("allow");
        }
        Set<String> unspecified = new TreeSet<>(foldedHeaderNames(response));
        unspecified.removeAll(allowed);
        checkSetEquals(label
                + ": departs from the specified header set by exactly the stated deviation",
                DEVIATION_HEADER_NAMES, unspecified);
    }

    /**
     * Waits briefly for the stopped server's dispatcher thread to disappear.
     *
     * <p>{@code stop} releases the thread promptly, but thread termination is
     * observed rather than commanded, so this polls instead of asserting
     * immediately. Polling is what keeps the check from being a race: it returns
     * as soon as the thread is gone and only spends its budget when something is
     * genuinely wrong. That the thread must go is not cosmetic - it is not a
     * daemon thread, so a leaked one would keep this JVM alive after the summary
     * was printed and the harness would appear to hang.
     */
    private static boolean awaitNoDispatcherThread() {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(DISPATCHER_SHUTDOWN_WAIT_SECONDS).toNanos();
        while (dispatcherThreadCount() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(DISPATCHER_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return dispatcherThreadCount() == 0;
    }

    // Section G - entry-point dispatch. Every check above this point calls a method
    // of User directly, which proves the methods and says nothing about the
    // DISPATCHER that chooses between them. That gap matters more than it sounds:
    // the flag handling is the newest code in the program, and the entire
    // backward-compatibility guarantee rests on which branch an empty argument
    // vector selects. A direct call to startServer cannot tell you that --serve
    // reaches it, and a direct call to probe cannot tell you that --probe exits with
    // the value probe returned.
    //
    // So this section drives the real main method of a real process and observes only
    // what a shell or a container runtime would observe: the exit status, the two
    // streams, and whether a port is listening.

    /**
     * Asserts that {@code --serve} and {@code --probe} reach their modes, that the
     * default branch survives an unrecognised flag, and that both new modes fail
     * closed.
     */
    private static void verifyEntrypointDispatch() throws IOException, InterruptedException {
        verifyUnrecognisedFlagKeepsDefault();
        verifyServeMode();
        verifyServeFailsClosed();
        verifyProbeMode();
    }

    /**
     * Asserts that an argument the dispatcher does not know selects the default.
     *
     * <p>Deliberate design, not laxity: the original program accepted any argument
     * vector and printed its line, so refusing an unknown flag - or printing a
     * usage message, or exiting non-zero - would itself be a breaking change. This
     * is the check that pins that decision, and it is asserted from outside the
     * process for the same reason section A is.
     */
    private static void verifyUnrecognisedFlagKeepsDefault() {
        byte[] expected = (LEGACY_STDOUT_TEXT + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        ChildOutcome outcome = runApplication(List.of(UNRECOGNISED_FLAG), Map.of());
        check("an unrecognised flag still terminates", outcome.exited());
        checkEquals("an unrecognised flag still exits 0", EXIT_SUCCESS, outcome.status());
        checkBytesEqual("an unrecognised flag still prints the preserved line",
                expected, outcome.standardOutput());
        checkBytesEqual("an unrecognised flag writes nothing to standard error",
                new byte[0], outcome.standardError());
    }

    /**
     * Asserts that {@code --serve} really starts the listener, on the configured
     * route, without disturbing standard output.
     *
     * <p>The standard-output assertion is the subtle one. The default mode's line
     * goes to stdout and the server's banner goes to stderr, which is what keeps
     * the two channels from colliding: a startup line on stdout would corrupt the
     * output stream that the compatibility gate hashes for any tool piping this
     * program. Asserting stdout is EMPTY in serve mode is what protects that
     * separation.
     */
    private static void verifyServeMode() throws IOException, InterruptedException {
        String route = "/dispatch-health";
        String name = "dispatch-name";
        String version = "3.2.1";
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put(ENV_HEALTH_PATH, route);
        overrides.put(ENV_APP_NAME, name);
        overrides.put(ENV_APP_VERSION, version);

        try (ServeChild child = startServeChild(overrides)) {
            check("--serve announced a usable port",
                    child.port() > MIN_PORT && child.port() <= MAX_PORT);
            check("--serve did not bind the documented default port",
                    child.port() != DEFAULT_JAVA_PORT);
            checkEquals("--serve announced the host it was told to bind",
                    TEST_HOST, child.host());
            checkEquals("--serve announced the configured route", route, child.route());
            check("--serve is still running after it announced itself", child.alive());
            checkBytesEqual("--serve writes nothing to standard output",
                    new byte[0], child.standardOutputBytes());
            // Cast, not decoration: checkEquals compares with Objects.equals, and a
            // boxed Integer never equals a boxed Long however equal the numbers are.
            checkEquals("--serve writes exactly one diagnostic line",
                    1, (int) child.diagnostics().lines().count());

            HttpResponse<String> served = fetch(child.port(), route);
            checkEquals("the dispatched listener answers 200", HTTP_OK, served.statusCode());
            checkMatches("the dispatched listener serves the frozen four-key shape",
                    PAYLOAD_SHAPE_PATTERN, served.body());
            checkEquals("the dispatched listener reports the injected name",
                    name, jsonField(served.body(), "name"));
            checkEquals("the dispatched listener reports the injected version",
                    version, jsonField(served.body(), "version"));
            check("the dispatched listener reports status UP",
                    served.body().contains(STATUS_UP_FRAGMENT));
            checkFrozenHeaders(served, CONTRACT_HEADER_NAMES,
                    served.body().getBytes(StandardCharsets.UTF_8).length, "200 (dispatched)");
            checkEquals("the built-in default route is not served when one is configured",
                    HTTP_NOT_FOUND, fetch(child.port(), DEFAULT_HEALTH_PATH).statusCode());
        }

        // Both orderings, because a dispatcher written as an if/else chain would
        // honour whichever flag it tested first and a dispatcher written as guard
        // clauses honours serve regardless. The documented behaviour is the
        // latter, and only trying both orderings can tell them apart.
        for (String[] ordering : new String[][] {{FLAG_SERVE, FLAG_PROBE},
                {FLAG_PROBE, FLAG_SERVE}}) {
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put(ENV_APP_HOST, TEST_HOST);
            environment.put(ENV_JAVA_PORT, Integer.toString(EPHEMERAL_PORT));
            ServeChild child = null;
            try {
                child = new ServeChild(startApplication(environment, ordering));
                check("--serve wins over --probe given " + Arrays.toString(ordering),
                        child.awaitBanner() && child.alive());
            } finally {
                if (child != null) {
                    child.close();
                }
            }
        }

        // A port the process no longer owns must be immediately reusable. A
        // listener left behind would hold it, and the next run would fail to bind
        // for a reason that has nothing to do with the code being tested.
        int released;
        try (ServeChild child = startServeChild(Map.of())) {
            released = child.port();
        }
        check("--serve releases its port when the process ends", awaitPortFree(released));
    }

    /**
     * Asserts that {@code --serve} refuses to start rather than starting wrongly.
     *
     * <p>Both refusals are about the same principle. A configured port that cannot
     * be used must produce a dead process and a non-zero status, never a live
     * process listening somewhere nobody is watching - which is the outcome that
     * would follow from quietly substituting the default. The offending value is
     * required to appear in the diagnostic, because a refusal an operator cannot
     * trace back to the setting they mistyped is only half a diagnostic.
     */
    private static void verifyServeFailsClosed() throws IOException {
        ChildOutcome unparseable = runApplication(List.of(FLAG_SERVE),
                Map.of(ENV_APP_HOST, TEST_HOST, ENV_JAVA_PORT, UNUSABLE_PORT_VALUE));
        checkEquals("--serve exits 1 on a port that cannot be parsed",
                EXIT_FAILURE, unparseable.status());
        checkBytesEqual("a refused start writes nothing to standard output",
                new byte[0], unparseable.standardOutput());
        check("the refusal names the offending port value",
                unparseable.errorText().contains(UNUSABLE_PORT_VALUE));

        ChildOutcome outOfRange = runApplication(List.of(FLAG_SERVE),
                Map.of(ENV_APP_HOST, TEST_HOST, ENV_JAVA_PORT, OUT_OF_RANGE_PORT_VALUE));
        checkEquals("--serve exits 1 on a port outside the legal range",
                EXIT_FAILURE, outOfRange.status());
        check("the out-of-range refusal names the offending value",
                outOfRange.errorText().contains(OUT_OF_RANGE_PORT_VALUE));

        // A genuine bind conflict, arranged rather than hoped for: this harness
        // holds the port itself for the duration, so the conflict is certain.
        try (ServerSocket occupied = new ServerSocket(EPHEMERAL_PORT, 1,
                InetAddress.getByName(TEST_HOST))) {
            int taken = occupied.getLocalPort();
            ChildOutcome conflicted = runApplication(List.of(FLAG_SERVE),
                    Map.of(ENV_APP_HOST, TEST_HOST, ENV_JAVA_PORT, Integer.toString(taken)));
            checkEquals("--serve exits 1 when the port is already bound",
                    EXIT_FAILURE, conflicted.status());
            check("the bind failure names the address it could not take",
                    conflicted.errorText().contains(TEST_HOST + ":" + taken));
            checkBytesEqual("a failed bind writes nothing to standard output",
                    new byte[0], conflicted.standardOutput());
        }
    }

    /**
     * Asserts that {@code --probe} grades a live endpoint healthy and everything
     * else unhealthy, and that its verdict becomes the process exit status.
     *
     * <p>The exit status IS the contract in this mode, and it has to be observed
     * from outside the process to be observed at all. Every negative case matters
     * as much as the positive one: a probe that cannot fail is indistinguishable
     * from no probe, and it would report a dead application as healthy for as long
     * as the deployment lived.
     */
    private static void verifyProbeMode() throws IOException, InterruptedException {
        int abandoned;
        String route = "/probe-health";
        try (ServeChild child = startServeChild(Map.of(ENV_HEALTH_PATH, route))) {
            abandoned = child.port();
            Map<String, String> reachable = new LinkedHashMap<>();
            reachable.put(ENV_APP_HOST, TEST_HOST);
            reachable.put(ENV_JAVA_PORT, Integer.toString(child.port()));
            reachable.put(ENV_HEALTH_PATH, route);

            ChildOutcome healthy = runApplication(List.of(FLAG_PROBE), reachable);
            checkEquals("--probe exits 0 against a live endpoint", EXIT_SUCCESS, healthy.status());
            checkBytesEqual("a successful probe writes nothing to standard output",
                    new byte[0], healthy.standardOutput());
            checkBytesEqual("a successful probe writes nothing to standard error",
                    new byte[0], healthy.standardError());

            // Pointed at a path that answers 404, the probe must fail. A probe that
            // graded any HTTP answer as healthy would report a misconfigured route
            // as a working one.
            Map<String, String> wrongRoute = new LinkedHashMap<>(reachable);
            wrongRoute.put(ENV_HEALTH_PATH, UNKNOWN_PATH);
            ChildOutcome refused = runApplication(List.of(FLAG_PROBE), wrongRoute);
            checkEquals("--probe exits 1 when the route answers 404",
                    EXIT_FAILURE, refused.status());
            check("the rejected probe reports the status it saw",
                    refused.errorText().contains("404"));
        }

        // The wildcard bind is what a container uses, and it is not a routable
        // target, so the probe has to translate it to loopback to reach its own
        // endpoint. Without that translation a wildcard-bound process could never
        // grade itself healthy.
        try (ServeChild wildcard = startServeChild(Map.of(ENV_APP_HOST, DEFAULT_APP_HOST))) {
            checkEquals("a wildcard-bound child announces the wildcard address",
                    DEFAULT_APP_HOST, wildcard.host());
            Map<String, String> viaWildcard = new LinkedHashMap<>();
            viaWildcard.put(ENV_APP_HOST, DEFAULT_APP_HOST);
            viaWildcard.put(ENV_JAVA_PORT, Integer.toString(wildcard.port()));
            checkEquals("--probe reaches a wildcard-bound endpoint over loopback",
                    EXIT_SUCCESS, runApplication(List.of(FLAG_PROBE), viaWildcard).status());
        }

        // Fail closed once the endpoint is gone. The child that owned this port has
        // been stopped by the block above, so nothing is listening.
        Map<String, String> gone = new LinkedHashMap<>();
        gone.put(ENV_APP_HOST, TEST_HOST);
        gone.put(ENV_JAVA_PORT, Integer.toString(abandoned));
        gone.put(ENV_HEALTH_PATH, route);
        ChildOutcome unreachable = runApplication(List.of(FLAG_PROBE), gone);
        checkEquals("--probe exits 1 once the endpoint is gone",
                EXIT_FAILURE, unreachable.status());
        check("the failed probe says which endpoint it could not reach",
                unreachable.errorText().contains(Integer.toString(abandoned)));

        ChildOutcome unparseable = runApplication(List.of(FLAG_PROBE),
                Map.of(ENV_APP_HOST, TEST_HOST, ENV_JAVA_PORT, UNUSABLE_PORT_VALUE));
        checkEquals("--probe exits 1 on a port that cannot be parsed",
                EXIT_FAILURE, unparseable.status());
        // The category, and NOT the value. This is the reverse of what --serve is
        // asserted to do a few methods above, and the difference is the point: the
        // interactive start names the value for the operator who typed it, and the
        // unattended probe withholds it because a configured value is an input and
        // everything an unattended refusal reaches is a log collector. Asserting the
        // absence as well as the presence is what makes this a policy rather than a
        // wording preference - and what keeps it byte-comparable to app.py's and
        // index.js's line for the same fault.
        check("the unrunnable probe reports the port category",
                unparseable.errorText().contains(PORT_REFUSAL_CATEGORY));
        check("the unrunnable probe does not echo the offending port value",
                !unparseable.errorText().contains(UNUSABLE_PORT_VALUE));
        check("the unrunnable probe refuses before it opens a socket",
                !unparseable.errorText().contains(PROBE_REACH_PREFIX));
        checkEquals("the unrunnable probe writes exactly one diagnostic line",
                1, unparseable.errorLineCount());
    }

    /**
     * Waits briefly for a port to become bindable again.
     *
     * <p>Polled rather than asserted immediately, because a socket the kernel has
     * only just reclaimed can refuse one bind and accept the next. Polling makes
     * the check mean "the port was released" instead of "the port was released
     * within one scheduling quantum".
     */
    private static boolean awaitPortFree(int port) {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(DISPATCHER_SHUTDOWN_WAIT_SECONDS).toNanos();
        while (System.nanoTime() < deadline) {
            try (ServerSocket probe = new ServerSocket(port, 1,
                    InetAddress.getByName(TEST_HOST))) {
                return probe.getLocalPort() == port;
            } catch (IOException stillHeld) {
                sleepBriefly();
            }
        }
        return false;
    }

    // Section H - transport behaviour over a raw socket.
    //
    // java.net.http is the right client for the response CONTRACT and the wrong
    // client for the transport. It frames and validates the request itself, so it
    // cannot be persuaded to send a two-token request line, a target of any length
    // the caller chooses, a header block of any size, an unsupported HTTP major, an
    // HTTP/1.1 request with no Host, a stale CRLF before the request line, or a
    // plain HTTP/1.0 request. It also reframes what comes BACK: a check made through
    // it is a check on a parsed object, not on the bytes on the wire.
    //
    // WHAT THIS SECTION DELIBERATELY DOES NOT ASSERT
    //
    // There is no 400, 414, 431 or 505 expectation anywhere below, and no fixed body
    // for one. This endpoint produces exactly three statuses - 200, 404 and 405 - and
    // the frozen contract in the specification enumerates exactly those three.
    // Request parsing belongs to com.sun.net.httpserver, which is the listener the
    // plan mandates, and it makes its own decisions: it tolerates a fourth token on
    // the request line, tolerates a lower-case version keyword, tolerates a version
    // with no minor, requires no Host at HTTP/1.1, imposes no target or header-size
    // ceiling, and answers the shapes it does refuse with an HTML document of its own
    // composition. Asserting a JSON 400 here would be asserting a response no
    // conforming implementation of this contract sends, on a bespoke parser this
    // endpoint is specified not to have. What IS asserted about those shapes is what
    // the contract genuinely promises: whatever answer comes back reflects nothing
    // from the request, and the endpoint is still serving afterwards.
    //
    // WHAT THIS SECTION DOES ASSERT ABOUT THE RUNTIME'S OWN REJECTIONS
    //
    // One exception to the paragraph above, and it is an exception with a different
    // subject rather than a softening of it. verifyRuntimeRejectionBodies pins all
    // eight of the runtime's rejection bodies VERBATIM. That is not an expectation
    // placed on this endpoint - the endpoint never sees these requests - it is a
    // change detector on a security position the class documentation on User records
    // as an accepted risk, because two of the eight disclose a Java exception class
    // name and no in-process interception point exists to stop them. A risk accepted
    // on the strength of a description stops being honestly accepted the moment the
    // description silently goes stale, so the description is held by a test. The
    // eight are asserted together with the absence of reflection, which is the half
    // of the position that would change the risk rating if it were ever false.
    //
    // The connection assertions are kept in full. A served 1.1 connection is reused,
    // a client that asks to close is answered and then closed, a lookalike connection
    // option does not retire a good connection, and HTTP/1.0's inverted default is
    // honoured both ways. Getting that backwards is not cosmetic: a health poller
    // that had to reconnect for every poll would pay a handshake it does not need,
    // and a client that kept writing into a socket the server had abandoned would see
    // a reset instead of the answer it was given.

    /**
     * Asserts the contract over raw bytes, the body drain, availability under
     * hostile input and the connection semantics, over a socket this harness writes
     * bytes to directly.
     */
    private static void verifyRawTransport() throws IOException {
        String route = User.healthPath();
        User.HealthServer server = User.startServer(TEST_HOST, EPHEMERAL_PORT);
        int port = server.port();
        try {
            verifyContractOverRawBytes(port, route);
            verifyHostileRequestLines(port, route);
            verifyRuntimeRejectionBodies(port, route);
            verifyProtocolVersions(port, route);
            verifyRequestSizeHandling(port, route);
            verifyRequestTargetHandling(port, route);
            verifyConnectionSemantics(port, route);
            verifyBodyDraining(port, route);
        } finally {
            server.stop();
        }
        check("the raw-transport server released its dispatcher thread",
                awaitNoDispatcherThread());
    }

    /**
     * Asserts all three contract responses as bytes on the wire.
     *
     * <p>Every assertion above this point reads a response through
     * {@code java.net.http}, which parses the header block, folds field names, and
     * hands back an object. That is the right tool for the contract's MEANING and
     * it cannot see the contract's BYTES: it would report an accurate response and
     * a response whose declared length was one byte short identically, because it
     * reads to the end of the stream either way. Here the length is the framing, so
     * an inaccurate one is observable.
     */
    private static void verifyContractOverRawBytes(int port, String route) {
        checkRawExchange(port, "the served answer, as bytes",
                wireRequest(List.of("GET " + route + " HTTP/1.1", "Host: " + TEST_HOST)),
                response -> {
                    checkEquals("the status line is HTTP/1.1 200 OK",
                            "HTTP/1.1 200 OK", response.statusLine());
                    checkSetEquals("the served answer carries exactly the frozen fields",
                            CONTRACT_HEADER_NAMES, response.names());
                    checkMatches("the served document matches the frozen four-key shape",
                            PAYLOAD_SHAPE_PATTERN, bodyText(response));
                    // The length this process's configuration renders, not the
                    // parity constant: see expectedServedBodyByteLength.
                    checkRawContractHeaders("the served answer", response,
                            expectedServedBodyByteLength());
                });

        checkRawExchange(port, "the 404 answer, as bytes",
                wireRequest(List.of("GET " + UNKNOWN_PATH + " HTTP/1.1", "Host: " + TEST_HOST)),
                response -> {
                    checkEquals("an unknown path is answered 404",
                            HTTP_NOT_FOUND, response.status());
                    checkEquals("the 404 body is the fixed document",
                            BODY_NOT_FOUND, bodyText(response));
                    checkSetEquals("the 404 carries exactly the frozen fields",
                            CONTRACT_HEADER_NAMES, response.names());
                    checkRawContractHeaders("the 404", response,
                            BODY_NOT_FOUND.getBytes(StandardCharsets.UTF_8).length);
                });

        checkRawExchange(port, "the 405 answer, as bytes",
                wireRequest(List.of("POST " + route + " HTTP/1.1", "Host: " + TEST_HOST,
                        "Content-Length: 0")), response -> {
                    checkEquals("a refused method is answered 405",
                            HTTP_METHOD_NOT_ALLOWED, response.status());
                    checkEquals("the 405 body is the fixed document",
                            BODY_METHOD_NOT_ALLOWED, bodyText(response));
                    checkSetEquals("the 405 carries exactly the frozen refusal fields",
                            REFUSAL_HEADER_NAMES, response.names());
                    checkEquals("the 405 names GET as allowed",
                            "GET", response.headers().get("allow"));
                    checkRawContractHeaders("the 405", response,
                            BODY_METHOD_NOT_ALLOWED.getBytes(StandardCharsets.UTF_8).length);
                });
    }

    /**
     * Asserts the media type, cache directives, length, date format and the absence
     * of a banner, on a response read as raw bytes.
     */
    private static void checkRawContractHeaders(String label, RawResponse response,
            int declaredLength) {
        checkEquals(label + " declares JSON content",
                CONTENT_TYPE_JSON, response.headers().get("content-type"));
        checkEquals(label + " declares an accurate Content-Length",
                Integer.toString(declaredLength), response.headers().get("content-length"));
        String cache = response.headers().get("cache-control");
        check(label + " suppresses caching completely", cache != null
                && CACHE_DIRECTIVES.stream().allMatch(cache::contains));
        checkMatches(label + "'s Date is a well-formed HTTP-date (format only)",
                HTTP_DATE_PATTERN, response.headers().getOrDefault("date", ""));
        check(label + " discloses no Server banner",
                !response.names().contains("server"));
    }

    /**
     * Asserts that no shape of hostile request line can silence the endpoint or make
     * any response reflect its input.
     *
     * <p>The status a malformed request receives is the RUNTIME's decision, not this
     * endpoint's, and the runtime's decisions vary by shape: a request line with a
     * fourth token is tolerated and routed, one with a space in the target is routed
     * to the truncated target, one with an unparseable target or too few tokens is
     * refused before any handler runs, and one whose method token merely looks odd is
     * routed and refused 405 like any other non-GET. All of those are conformant,
     * none is in this endpoint's contract, so none is asserted as a specific status.
     *
     * <p>The fourth-token case is the one worth naming as a CROSS-LANGUAGE
     * DIVERGENCE rather than merely as a tolerance, because it is the one shape on
     * which the three implementations disagree about the outcome and not just about
     * the wording: {@code GET /health with space HTTP/1.1} is answered 200 with the
     * document here, and 400 by app.py and index.js, which both require exactly
     * three space-separated tokens. RFC 9112 section 3 permits a recipient to refuse
     * such a line, so 400 is the stricter reading and this listener takes the more
     * tolerant one. It matters only as a precondition for desynchronising an
     * intermediary that splits the line differently, and no intermediary is in scope
     * for this repository - so it is recorded here, asserted through the general
     * properties below, and deliberately not made a status expectation on a parser
     * this endpoint is specified not to own.
     *
     * <p>What IS asserted holds for every shape without exception: the answer is a
     * well-formed HTTP/1.1 message, its status is a recognised one rather than
     * something invented, nothing from the request comes back in the status line, the
     * header block or the body - checked with a target chosen to look like internal
     * deployment detail so that a leak would be unmistakable in the log - and the
     * endpoint is still answering afterwards on a fresh connection, which is the
     * availability property a health endpoint exists to provide.
     */
    private static void verifyHostileRequestLines(int port, String route) {
        String telltale = "/internal-deployment-detail-" + port;
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("two tokens", "GET " + telltale);
        cases.put("one token", "GET");
        cases.put("a fourth token", "GET " + telltale + " HTTP/1.1 extra");
        cases.put("a space in the target", "GET /hea lth" + telltale + " HTTP/1.1");
        cases.put("a tab in the target", "GET /a\tb" + telltale + " HTTP/1.1");
        cases.put("a delimiter in the method", "G(ET " + telltale + " HTTP/1.1");
        cases.put("a lower-case version keyword", "GET " + telltale + " http/1.1");
        cases.put("a version with no minor", "GET " + telltale + " HTTP/1");
        cases.put("a version with a missing minor", "GET " + telltale + " HTTP/1.");
        cases.put("a non-numeric version", "GET " + telltale + " HTTP/x.y");
        cases.put("a header field where the request line belongs", "Host: " + telltale);
        cases.put("an empty request line", "");

        String good = wireRequest(List.of("GET " + route + " HTTP/1.1", "Host: " + TEST_HOST));
        for (Map.Entry<String, String> entry : cases.entrySet()) {
            String label = "a request line with " + entry.getKey();
            checkHostileExchange(port, label,
                    wireRequest(List.of(entry.getValue(), "Host: " + TEST_HOST)), telltale);
            checkRawExchange(port, label + " leaves the endpoint serving", good, response ->
                    checkEquals(label + " does not silence the endpoint",
                            HTTP_OK, response.status()));
        }
    }

    /**
     * Sends one hostile request and asserts only what the contract promises about
     * the answer: that it is well formed, recognised, silent about the request, and
     * free of a banner.
     *
     * <p>A hostile request may be answered by this endpoint or refused by the
     * runtime beneath it, and the two write different header blocks - the runtime's
     * rejection declares HTML, announces a close and carries no {@code Date} or
     * {@code Cache-Control}. Both are accepted here, and each is held to its own
     * frozen field set, so "the runtime refused it" and "the endpoint answered it"
     * are distinguished rather than blurred.
     */
    private static void checkHostileExchange(int port, String label, String request,
            String telltale) {
        try (RawConnection connection = new RawConnection(port)) {
            RawResponse response = connection.send(request).readResponse();
            checkEquals(label + ": the answer is an HTTP/1.1 message",
                    "HTTP/1.1", response.version());
            check(label + ": the status is a recognised one",
                    response.status() >= HTTP_CONTINUE && response.status() <= 599);
            check(label + ": the status line reflects no part of the request",
                    !response.statusLine().contains(telltale));
            check(label + ": the body reflects no part of the request",
                    !bodyText(response).contains(telltale));
            check(label + ": no field value reflects any part of the request",
                    response.headers().values().stream().noneMatch(v -> v.contains(telltale)));
            check(label + ": discloses no Server banner",
                    !response.names().contains("server"));
            boolean refusedByRuntime = CONTENT_TYPE_HTML
                    .equals(response.headers().get("content-type"));
            if (refusedByRuntime) {
                checkSetEquals(label + ": the runtime's rejection carries its frozen fields",
                        RUNTIME_REJECTION_HEADER_NAMES, response.names());
                checkEquals(label + ": the runtime's rejection announces the close",
                        "close", response.headers().get("connection"));
            } else {
                check(label + ": the endpoint's answer is one of the three contract statuses",
                        response.status() == HTTP_OK || response.status() == HTTP_NOT_FOUND
                                || response.status() == HTTP_METHOD_NOT_ALLOWED);
                checkSetEquals(label + ": the endpoint's answer carries a frozen field set",
                        response.status() == HTTP_METHOD_NOT_ALLOWED
                                ? REFUSAL_HEADER_NAMES : CONTRACT_HEADER_NAMES,
                        response.names());
            }
        } catch (IOException failure) {
            recordRawFailure(label, failure);
        }
    }

    /**
     * Pins every transport-level rejection body the runtime emits, verbatim.
     *
     * <p>One trigger per body in {@link #RUNTIME_REJECTION_BODIES}, each asserted on
     * three counts: the exact body text, the rejection's own three-field header set
     * with {@code text/html} and {@code Connection: close}, and the absence of any
     * echoed part of the request. The status is asserted through the body, which
     * carries it - so a status that changed without the text changing would still be
     * caught, and a text that changed would be reported as the text it now is.
     *
     * <p>Why the reflection assertion travels with these rather than being left to
     * {@link #checkHostileExchange}: there it is one property of a general hostile
     * request, and here it is the specific claim that makes an exception-class
     * disclosure a fingerprint rather than a reflected-input channel. The marker is
     * planted in five places at once - the target, {@code Host}, a field NAME, a
     * field VALUE and the body - because a rejection decided at any of the five
     * stages could only echo the part it had already read.
     *
     * <p>Each case is followed by a control on a fresh connection: the runtime closes
     * a rejected connection, so a rejection that also disabled the listener would look
     * identical from the rejected socket alone.
     */
    private static void verifyRuntimeRejectionBodies(int port, String route) {
        Map<String, String> triggers = new LinkedHashMap<>();
        triggers.put("an unparsable target",
                wireRequest(List.of("GET " + route + "%zz" + REFLECTION_MARKER + " HTTP/1.1",
                        "Host: " + REFLECTION_MARKER,
                        "X-" + REFLECTION_MARKER + ": " + REFLECTION_MARKER)));
        triggers.put("a non-numeric Content-Length",
                wireRequest(List.of("POST " + route + " HTTP/1.1",
                        "Host: " + REFLECTION_MARKER, "Content-Length: 0x5")));
        triggers.put("a negative Content-Length",
                wireRequest(List.of("POST " + route + " HTTP/1.1",
                        "Host: " + REFLECTION_MARKER, "Content-Length: -1")));
        triggers.put("conflicting framing fields",
                wireRequest(List.of("POST " + route + " HTTP/1.1",
                        "Host: " + REFLECTION_MARKER, "Content-Length: 5",
                        "Transfer-Encoding: chunked"), REFLECTION_MARKER));
        triggers.put("whitespace before a field colon",
                wireRequest(List.of("GET " + route + " HTTP/1.1",
                        "Host: " + REFLECTION_MARKER, "X-" + REFLECTION_MARKER + " : 1")));
        triggers.put("a request line that is not three tokens",
                wireRequest(List.of("GET " + route + REFLECTION_MARKER,
                        "Host: " + REFLECTION_MARKER)));
        // A leading slash ahead of the route: the whole thing parses as a network-path
        // reference whose authority is the route text, so no context matches. Built
        // from the resolved route rather than a literal, so an overridden health path
        // exercises the same rule.
        triggers.put("a target no context matches",
                wireRequest(List.of("GET /" + route + REFLECTION_MARKER + " HTTP/1.1",
                        "Host: " + REFLECTION_MARKER)));
        triggers.put("an unsupported transfer coding",
                wireRequest(List.of("POST " + route + " HTTP/1.1",
                        "Host: " + REFLECTION_MARKER, "Transfer-Encoding: identity")));

        checkEquals("every recorded rejection body has a trigger",
                RUNTIME_REJECTION_BODIES.size(), triggers.size());
        String good = wireRequest(List.of("GET " + route + " HTTP/1.1", "Host: " + TEST_HOST));
        for (Map.Entry<String, String> entry : triggers.entrySet()) {
            String shape = entry.getKey();
            String expected = RUNTIME_REJECTION_BODIES.get(shape);
            check(shape + " is a recorded rejection shape", expected != null);
            if (expected == null) {
                continue;
            }
            checkRawExchange(port, shape + " is refused by the runtime", entry.getValue(),
                    response -> {
                        checkEquals(shape + ": the recorded rejection body is unchanged",
                                expected, bodyText(response));
                        checkSetEquals(shape + ": the rejection carries its three fields",
                                RUNTIME_REJECTION_HEADER_NAMES, response.names());
                        checkEquals(shape + ": the rejection declares HTML",
                                CONTENT_TYPE_HTML, response.headers().get("content-type"));
                        checkEquals(shape + ": the rejection announces the close",
                                "close", response.headers().get("connection"));
                        check(shape + ": the rejection echoes no part of the request",
                                !response.statusLine().contains(REFLECTION_MARKER)
                                        && !bodyText(response).contains(REFLECTION_MARKER)
                                        && response.headers().values().stream()
                                                .noneMatch(v -> v.contains(REFLECTION_MARKER)));
                    });
            checkRawExchange(port, shape + " leaves the endpoint serving", good, response ->
                    checkEquals(shape + " does not disable the listener",
                            HTTP_OK, response.status()));
        }
    }

    /**
     * Asserts what happens to a request announcing a version other than 1.1.
     *
     * <p>Version negotiation belongs to the listener, and this listener accepts what
     * it is given: an HTTP/2.0, 3.0, 0.9 or 9.9 request line over a 1.1 connection is
     * served, and the answer is a 1.1 message carrying the frozen contract. That is
     * asserted rather than a 505, which is not a response this contract defines and
     * not one the mandated listener produces.
     *
     * <p>HTTP/1.0 is the case that behaves differently and the case that matters,
     * because a real 1.0 client exists: its default disposition is the reverse of
     * 1.1's, and the server both honours it and says so - which is why the 1.0 answer
     * carries one field more than the contract set, named in an expectation of its own
     * rather than tolerated by a loosened one.
     */
    private static void verifyProtocolVersions(int port, String route) {
        for (String version : List.of("HTTP/2.0", "HTTP/3.0", "HTTP/0.9", "HTTP/9.9")) {
            checkRawExchange(port, "a request announcing " + version,
                    wireRequest(List.of("GET " + route + " " + version,
                            "Host: " + TEST_HOST)), response -> {
                        checkEquals(version + " is served by the listener",
                                HTTP_OK, response.status());
                        checkEquals("the answer to " + version + " is an HTTP/1.1 message",
                                "HTTP/1.1", response.version());
                        checkMatches("the answer to " + version + " is the frozen document",
                                PAYLOAD_SHAPE_PATTERN, bodyText(response));
                    });
        }

        // A four-hundred-digit major is input to survive, not arithmetic to perform:
        // whatever the listener decides, it must not overflow, hang or crash.
        checkRawExchange(port, "an absurdly wide major version",
                wireRequest(List.of("GET " + route + " HTTP/" + "9".repeat(400) + ".0",
                        "Host: " + TEST_HOST)), response ->
                        check("an absurdly wide major version is answered, not fatal",
                                response.status() > 0));

        checkRawExchange(port, "an HTTP/1.0 request",
                wireRequest(List.of("GET " + route + " HTTP/1.0")), response -> {
                    checkEquals("the 1.0 request is served", HTTP_OK, response.status());
                    checkEquals("the answer is still an HTTP/1.1 message",
                            "HTTP/1.1", response.version());
                    checkSetEquals("the 1.0 answer carries the contract fields plus Connection",
                            LEGACY_ANSWER_HEADER_NAMES, response.names());
                    checkEquals("the 1.0 answer announces the close it is about to make",
                            "close", response.headers().get("connection"));
                    checkMatches("the 1.0 answer is the frozen document",
                            PAYLOAD_SHAPE_PATTERN, bodyText(response));
                });
    }

    /**
     * Asserts the one request ceiling the runtime enforces observably, with the
     * large-but-legal controls that keep it from being a false positive.
     *
     * <p>The controls matter as much as the ceiling: a listener that rejected
     * everything large would pass a limit test and break every real client, so a
     * target of {@value #LARGE_TARGET_BYTES} bytes, a single header field of
     * {@value #LARGE_FIELD_BYTES} bytes and a header block of
     * {@value #LARGE_BLOCK_FIELD_COUNT} fields of {@value #LARGE_BLOCK_FIELD_BYTES}
     * bytes each are all required to be served.
     *
     * <p>The long target carries the one assertion here that is about correctness
     * rather than capacity: it must be answered 404, never 200. A target truncated
     * anywhere along its length could normalise to the health route and be answered
     * healthy on the strength of bytes that were never received.
     *
     * <p>Past {@value #RUNTIME_HEADER_FIELD_CEILING} fields the runtime closes the
     * connection without writing a byte. That is a denial-of-service control
     * reachable from the network, so it is asserted; and because it is the runtime's
     * control rather than this endpoint's, what is asserted alongside it is that the
     * endpoint survives it and keeps serving.
     */
    private static void verifyRequestSizeHandling(int port, String route) {
        checkRawExchange(port, "a very long request target",
                wireRequest(List.of("GET /" + "a".repeat(LARGE_TARGET_BYTES) + " HTTP/1.1",
                        "Host: " + TEST_HOST)), response -> {
                    checkEquals("a very long target is routed, not rejected",
                            HTTP_NOT_FOUND, response.status());
                    checkEquals("a very long target is never truncated into a route match",
                            BODY_NOT_FOUND, bodyText(response));
                    checkSetEquals("the answer to a very long target is a frozen field set",
                            CONTRACT_HEADER_NAMES, response.names());
                });

        checkRawExchange(port, "one very large header field",
                wireRequest(List.of("GET " + route + " HTTP/1.1", "Host: " + TEST_HOST,
                        "X-Padding: " + "p".repeat(LARGE_FIELD_BYTES))), response -> {
                    checkEquals("a very large field does not prevent service",
                            HTTP_OK, response.status());
                    checkSetEquals("the answer still carries exactly the frozen fields",
                            CONTRACT_HEADER_NAMES, response.names());
                });

        List<String> largeBlock = new ArrayList<>();
        largeBlock.add("GET " + route + " HTTP/1.1");
        largeBlock.add("Host: " + TEST_HOST);
        for (int index = 0; index < LARGE_BLOCK_FIELD_COUNT; index++) {
            largeBlock.add("X-Block-" + index + ": " + "p".repeat(LARGE_BLOCK_FIELD_BYTES));
        }
        check("the large block stays inside the field-count ceiling",
                LARGE_BLOCK_FIELD_COUNT < RUNTIME_HEADER_FIELD_CEILING);
        checkRawExchange(port, "a large block of ordinary fields",
                wireRequest(largeBlock), response ->
                        checkEquals("a large but legal block is served",
                                HTTP_OK, response.status()));

        // Exactly at the ceiling: served. One past it: refused outright. Asserting
        // both is what makes this a ceiling rather than an observation.
        checkRawExchange(port, "a header block at the runtime's field ceiling",
                wireRequest(headerFieldBlock(route, RUNTIME_HEADER_FIELD_CEILING)), response ->
                        checkEquals("a block at the ceiling is served",
                                HTTP_OK, response.status()));

        try (RawConnection connection = new RawConnection(port)) {
            connection.send(wireRequest(
                    headerFieldBlock(route, RUNTIME_HEADER_FIELD_CEILING + 1)));
            check("a block past the ceiling is refused without a response",
                    connection.peerHasClosed());
        } catch (IOException failure) {
            recordRawFailure("a header block past the runtime's field ceiling", failure);
        }
        checkRawExchange(port, "the endpoint survives an abusive header block",
                wireRequest(List.of("GET " + route + " HTTP/1.1", "Host: " + TEST_HOST)),
                response -> checkEquals("the next client is served normally",
                        HTTP_OK, response.status()));
    }

    /**
     * Builds a request whose header block carries exactly a chosen total number of
     * small fields, Host included.
     *
     * <p>The total is what the runtime counts, so the total is what this method
     * takes: a caller that had to remember to subtract the mandatory Host would sit
     * one field off the boundary it meant to probe, and a ceiling probed one field
     * off is a ceiling not probed at all.
     */
    private static List<String> headerFieldBlock(String route, int total) {
        List<String> lines = new ArrayList<>();
        lines.add("GET " + route + " HTTP/1.1");
        lines.add("Host: " + TEST_HOST);
        for (int index = 1; index < total; index++) {
            lines.add("X-Field-" + index + ": " + index);
        }
        return lines;
    }

    /**
     * Asserts how the request target and the Host field reach the routing decision.
     *
     * <p>These are the routing rules asserted at unit level in section D, driven here
     * through real bytes so that the parser between the wire and
     * {@code User.normalisePath} is part of the assertion rather than an assumption.
     * The absolute-form case is the one that would break silently: it is the only
     * request shape whose target carries a scheme and an authority, so it is the only
     * proof that {@code stripAuthority} is still reached now that a server parses the
     * request line.
     *
     * <p>The Host field is asserted to be RECOGNISED in any casing and not to be
     * required at all. That it is not required records the listener's behaviour rather
     * than endorsing it: RFC 9112 section 3.2 requires a 1.1 client to send one and
     * allows a server to reject a request without one, and this listener chooses to
     * serve it. The frozen contract defines no 400, so a hostless request is answered
     * by the route it asked for.
     *
     * <p>This is a CROSS-LANGUAGE DIVERGENCE and is recorded as one rather than left
     * to be discovered. Measured on the wire: app.py routes a hostless HTTP/1.1
     * request on its target and answers 200 exactly as this listener does, while
     * index.js refuses it 400 because its parser enforces the host requirement before
     * any handler runs. All three readings are conformant - the section requires the
     * CLIENT to send the field and permits, without requiring, a server to refuse
     * when it does not. An HTTP/1.0 request needs no Host and all three serve it.
     */
    private static void verifyRequestTargetHandling(int port, String route) {
        checkRawExchange(port, "a request with no Host at all",
                wireRequest(List.of("GET " + route + " HTTP/1.1")), response -> {
                    checkEquals("a hostless request is routed on its target",
                            HTTP_OK, response.status());
                    checkSetEquals("the answer carries exactly the frozen fields",
                            CONTRACT_HEADER_NAMES, response.names());
                });

        // The method is classified before the route, so a hostless POST is refused
        // as a method rather than answered as a routing decision.
        checkRawExchange(port, "a hostless POST",
                wireRequest(List.of("POST " + route + " HTTP/1.1")), response -> {
                    checkEquals("a hostless POST is refused as a method",
                            HTTP_METHOD_NOT_ALLOWED, response.status());
                    checkEquals("the refusal names GET as allowed",
                            "GET", response.headers().get("allow"));
                });

        for (String spelling : List.of("Host", "host", "HOST", "hOsT")) {
            checkRawExchange(port, "Host spelled " + spelling + " is accepted",
                    wireRequest(List.of("GET " + route + " HTTP/1.1",
                            spelling + ": " + TEST_HOST)), response ->
                            checkEquals("a case-folded Host does not change the answer",
                                    HTTP_OK, response.status()));
        }

        checkRawExchange(port, "an absolute-form target is reduced to its path",
                wireRequest(List.of("GET http://" + TEST_HOST + ":" + port + route + " HTTP/1.1",
                        "Host: " + TEST_HOST + ":" + port)), response -> {
                    checkEquals("the authority is stripped before matching",
                            HTTP_OK, response.status());
                    checkMatches("an absolute-form request is served the frozen document",
                            PAYLOAD_SHAPE_PATTERN, bodyText(response));
                });

        checkRawExchange(port, "an absolute-form target for another path",
                wireRequest(List.of("GET http://" + TEST_HOST + ":" + port + UNKNOWN_PATH
                        + " HTTP/1.1", "Host: " + TEST_HOST + ":" + port)), response ->
                        checkEquals("stripping the authority does not create a route match",
                                HTTP_NOT_FOUND, response.status()));

        checkRawExchange(port, "a target carrying a query string",
                wireRequest(List.of("GET " + route + "?probe=1&x=2 HTTP/1.1",
                        "Host: " + TEST_HOST)), response ->
                        checkEquals("the query string is stripped before matching",
                                HTTP_OK, response.status()));

        checkRawExchange(port, "a target with one trailing slash",
                wireRequest(List.of("GET " + route + "/ HTTP/1.1", "Host: " + TEST_HOST)),
                response -> checkEquals("one trailing slash is forgiven",
                        HTTP_OK, response.status()));

        checkRawExchange(port, "a target with two trailing slashes",
                wireRequest(List.of("GET " + route + "// HTTP/1.1", "Host: " + TEST_HOST)),
                response -> checkEquals("a second trailing slash is not forgiven",
                        HTTP_NOT_FOUND, response.status()));

        // RFC 9112 asks a server to skip an empty line left behind by a client that
        // terminated its previous request with a spare CRLF.
        checkRawExchange(port, "one stale CRLF before the request line is tolerated",
                CRLF + wireRequest(List.of("GET " + route + " HTTP/1.1",
                        "Host: " + TEST_HOST)), response ->
                        checkEquals("the stale line is skipped, not parsed",
                                HTTP_OK, response.status()));
    }

    /**
     * Asserts when the connection is kept and when it is closed.
     */
    private static void verifyConnectionSemantics(int port, String route) {
        String good = wireRequest(List.of("GET " + route + " HTTP/1.1", "Host: " + TEST_HOST));

        // Keep-alive is correctness here rather than throughput: a poller must not
        // pay a handshake per poll. Reading three framed answers off one connection
        // also proves every Content-Length was accurate - an inaccurate one would
        // leave the next read starting mid-stream.
        int framedLength = expectedServedBodyByteLength();
        try (RawConnection connection = new RawConnection(port)) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                RawResponse response = connection.send(good).readResponse();
                checkEquals("request " + attempt + " of 3 on one connection is served",
                        HTTP_OK, response.status());
                checkEquals("request " + attempt + " is framed accurately",
                        framedLength, response.body().length);
            }
            check("a served connection is left open for reuse", !connection.peerHasClosed());
        } catch (IOException failure) {
            recordRawFailure("three requests on one connection", failure);
        }

        try (RawConnection connection = new RawConnection(port)) {
            RawResponse response = connection.send(wireRequest(List.of(
                    "GET " + route + " HTTP/1.1", "Host: " + TEST_HOST, "Connection: close")))
                    .readResponse();
            checkEquals("a client asking to close is answered first",
                    HTTP_OK, response.status());
            // No Connection header on a 1.1 contract response, even when the client
            // asked to close: the listener honours the request without echoing it, so
            // the field set is the same set every other 1.1 answer carries and the
            // client learns of the close from the FIN that follows. Asserting this by
            // equality is what would catch the listener starting to echo the option,
            // which would make this the one 1.1 answer with a different shape.
            checkSetEquals("the answer adds no transport header of its own",
                    CONTRACT_HEADER_NAMES, response.names());
            check("the connection is then closed as asked", connection.peerHasClosed());
        } catch (IOException failure) {
            recordRawFailure("Connection: close", failure);
        }

        // "closely-related" is not "close". Substring matching would let an
        // unrelated connection option retire a perfectly good connection.
        try (RawConnection connection = new RawConnection(port)) {
            RawResponse first = connection.send(wireRequest(List.of(
                    "GET " + route + " HTTP/1.1", "Host: " + TEST_HOST,
                    "Connection: closely-related, not-close"))).readResponse();
            checkEquals("a lookalike connection option is answered", HTTP_OK, first.status());
            checkEquals("a lookalike connection option does not close the connection",
                    HTTP_OK, connection.send(good).readResponse().status());
        } catch (IOException failure) {
            recordRawFailure("a lookalike close token", failure);
        }

        // HTTP/1.0 defaults to closing and says so explicitly when it wants
        // otherwise, which is the reverse of 1.1.
        try (RawConnection connection = new RawConnection(port)) {
            checkEquals("an HTTP/1.0 request is served",
                    HTTP_OK, connection.send(wireRequest(List.of(
                            "GET " + route + " HTTP/1.0"))).readResponse().status());
            check("an HTTP/1.0 client is then disconnected", connection.peerHasClosed());
        } catch (IOException failure) {
            recordRawFailure("HTTP/1.0 default disposition", failure);
        }

        try (RawConnection connection = new RawConnection(port)) {
            String opted = wireRequest(List.of("GET " + route + " HTTP/1.0",
                    "Connection: keep-alive"));
            RawResponse first = connection.send(opted).readResponse();
            checkEquals("an HTTP/1.0 client may opt into keep-alive",
                    HTTP_OK, first.status());
            // The one response in the whole surface that discloses a runtime
            // parameter: a Keep-Alive field advertising the idle timeout, added by
            // the listener for a 1.0 client that asked to persist. It is named in an
            // expectation of its own rather than tolerated by a loosened one, so that
            // the disclosure is documented where it happens.
            checkSetEquals("the 1.0 keep-alive answer carries the legacy fields plus Keep-Alive",
                    LEGACY_KEEP_ALIVE_HEADER_NAMES, first.names());
            checkEquals("the 1.0 keep-alive answer confirms the persistence",
                    "keep-alive", first.headers().get("connection"));
            checkEquals("and is then served a second time",
                    HTTP_OK, connection.send(opted).readResponse().status());
        } catch (IOException failure) {
            recordRawFailure("HTTP/1.0 keep-alive", failure);
        }

        // Opening a socket and saying nothing must cost nothing, say nothing, and
        // not affect the next client.
        try (RawConnection silent = new RawConnection(port)) {
            check("a silent connection is neither answered nor closed at once",
                    !silent.peerHasClosed());
        } catch (IOException failure) {
            recordRawFailure("a silent connection", failure);
        }
        checkRawExchange(port, "a later client is unaffected by a silent one", good, response ->
                checkEquals("the next request is served normally", HTTP_OK, response.status()));
    }

    /**
     * Asserts that a refused method's body is consumed, so the connection survives.
     *
     * <p>This endpoint reads no body, but it must still drain one: the next request
     * on the same connection would otherwise be parsed starting from somebody
     * else's payload - and a JSON fragment interpreted as a request line is how a
     * refused POST turns into an unpredictable second answer. The follow-up GET is
     * the proof, and the refused method with a body is what makes it non-trivial.
     */
    private static void verifyBodyDraining(int port, String route) {
        String good = wireRequest(List.of("GET " + route + " HTTP/1.1", "Host: " + TEST_HOST));
        String body = "{\"ignored\":\"" + "x".repeat(2048) + "\"}";

        try (RawConnection connection = new RawConnection(port)) {
            RawResponse refusal = connection.send(wireRequest(List.of(
                    "POST " + route + " HTTP/1.1", "Host: " + TEST_HOST,
                    "Content-Type: " + CONTENT_TYPE_JSON,
                    "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length), body))
                    .readResponse();
            checkEquals("a POST carrying a body is refused", HTTP_METHOD_NOT_ALLOWED,
                    refusal.status());
            checkEquals("the refusal is the fixed document",
                    BODY_METHOD_NOT_ALLOWED, bodyText(refusal));
            checkSetEquals("the refusal carries exactly the refusal set",
                    REFUSAL_HEADER_NAMES, refusal.names());
            checkEquals("the refusal names GET as allowed", "GET", refusal.headers().get("allow"));
            checkEquals("the connection survived, so the body was drained",
                    HTTP_OK, connection.send(good).readResponse().status());
        } catch (IOException failure) {
            recordRawFailure("draining a Content-Length body", failure);
        }

        // The case that proves the drain is required rather than tidy. A body this
        // size left unread makes the kernel answer the close with a reset, so the
        // client reads "connection reset by peer" instead of the response that was
        // already written for it. One mebibyte is enough to fill the socket buffers
        // and is well inside the eight-mebibyte cap, so the whole body is consumed.
        try (RawConnection connection = new RawConnection(port)) {
            String large = "x".repeat(LARGE_BODY_BYTES);
            RawResponse refusal = connection.send(wireRequest(List.of(
                    "POST " + route + " HTTP/1.1", "Host: " + TEST_HOST,
                    "Content-Type: " + CONTENT_TYPE_JSON,
                    "Content-Length: " + large.length()), large)).readResponse();
            checkEquals("a POST carrying a one-mebibyte body is answered, not reset",
                    HTTP_METHOD_NOT_ALLOWED, refusal.status());
            checkEquals("the large-body refusal is the fixed document",
                    BODY_METHOD_NOT_ALLOWED, bodyText(refusal));
            checkSetEquals("the large-body refusal carries exactly the refusal set",
                    REFUSAL_HEADER_NAMES, refusal.names());
        } catch (IOException failure) {
            recordRawFailure("draining a one-mebibyte body", failure);
        }

        // The other framing a client may use, drained the same way.
        try (RawConnection connection = new RawConnection(port)) {
            RawResponse refusal = connection.send(wireRequest(List.of(
                    "PUT " + route + " HTTP/1.1", "Host: " + TEST_HOST,
                    "Transfer-Encoding: chunked"),
                    "10" + CRLF + "0123456789abcdef" + CRLF + "4" + CRLF + "tail" + CRLF
                            + "0" + CRLF + CRLF)).readResponse();
            checkEquals("a chunked request is refused", HTTP_METHOD_NOT_ALLOWED,
                    refusal.status());
            checkEquals("the connection survived a chunked body",
                    HTTP_OK, connection.send(good).readResponse().status());
        } catch (IOException failure) {
            recordRawFailure("draining a chunked body", failure);
        }

        // A client that announced Expect: 100-continue will not send its body until
        // it is answered, so the interim line is what keeps a refused POST from
        // stalling until the idle timeout fires.
        try (RawConnection connection = new RawConnection(port)) {
            String small = "x".repeat(64);
            connection.send(wireRequest(List.of("POST " + route + " HTTP/1.1",
                    "Host: " + TEST_HOST, "Expect: 100-continue",
                    "Content-Length: " + small.length())));
            RawResponse interim = connection.readResponse();
            checkEquals("an expectant client receives the interim response",
                    HTTP_CONTINUE, interim.status());
            checkEquals("the interim response carries no body", 0, interim.body().length);
            RawResponse refusal = connection.send(small).readResponse();
            checkEquals("the expectant client's request is then refused",
                    HTTP_METHOD_NOT_ALLOWED, refusal.status());
            checkEquals("the connection survived the expectation exchange",
                    HTTP_OK, connection.send(good).readResponse().status());
        } catch (IOException failure) {
            recordRawFailure("an Expect: 100-continue exchange", failure);
        }

        checkRawExchange(port, "HEAD over a raw socket", wireRequest(List.of(
                "HEAD " + route + " HTTP/1.1", "Host: " + TEST_HOST)), false, response -> {
                    checkEquals("raw HEAD is refused 405", HTTP_METHOD_NOT_ALLOWED,
                            response.status());
                    checkSetEquals("raw HEAD's refusal carries exactly the refusal set",
                            REFUSAL_HEADER_NAMES, response.names());
                    checkEquals("raw HEAD advertises the length it would have sent",
                            Integer.toString(BODY_METHOD_NOT_ALLOWED
                                    .getBytes(StandardCharsets.UTF_8).length),
                            response.headers().get("content-length"));
                    checkEquals("raw HEAD sends no body at all", 0, response.body().length);
                });
    }

    // Raw-socket machinery

    /** One parsed response, framed out of the byte stream by its Content-Length. */
    private record RawResponse(String statusLine, String version, int status,
            Map<String, String> headers, Set<String> names, byte[] body) {
    }

    private interface RawAssertions {
        void apply(RawResponse response);
    }

    /**
     * A raw TCP client for the endpoint, speaking bytes rather than HTTP.
     *
     * <p>Responses are framed by {@code Content-Length}, which every response this
     * endpoint writes declares accurately, so consuming one response leaves the
     * stream positioned exactly at the start of the next. That is what makes the
     * keep-alive and draining assertions possible: they are claims about where the
     * stream is, not merely about what came back.
     */
    private static final class RawConnection implements AutoCloseable {
        private final Socket socket;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private boolean peerClosed;

        RawConnection(int port) throws IOException {
            socket = new Socket(TEST_HOST, port);
            socket.setTcpNoDelay(true);
            // A short socket timeout is what lets every read loop below re-check
            // its own deadline instead of blocking indefinitely on a quiet peer.
            socket.setSoTimeout(RAW_POLL_TIMEOUT_MILLIS);
        }

        /**
         * Writes bytes exactly as given, with no framing of its own.
         */
        RawConnection send(String raw) throws IOException {
            OutputStream sink = socket.getOutputStream();
            sink.write(raw.getBytes(StandardCharsets.ISO_8859_1));
            sink.flush();
            return this;
        }

        /**
         * Reads exactly one complete response, leaving any remainder buffered.
         */
        RawResponse readResponse() throws IOException {
            return readResponse(true);
        }

        /**
         * Reads one complete response, optionally without waiting for a body.
         *
         * <p>The parameter exists for one case, and that case is a requirement
         * rather than an inconvenience: the answer to a HEAD request declares the
         * length the body WOULD have had and then sends none of it, exactly as RFC
         * 9110 requires. A framer that always waited for the declared bytes would
         * block forever on a correct response - so the caller, which is the only
         * party that knows it sent a HEAD, says so.
         */
        RawResponse readResponse(boolean expectBody) throws IOException {
            long deadline = System.nanoTime()
                    + Duration.ofMillis(RAW_READ_TIMEOUT_MILLIS).toNanos();
            while (true) {
                RawResponse framed = frame(expectBody);
                if (framed != null) {
                    return framed;
                }
                if (peerClosed) {
                    throw new IOException("the peer closed before a complete response arrived: "
                            + buffered());
                }
                if (System.nanoTime() > deadline) {
                    throw new IOException("no complete response within "
                            + RAW_READ_TIMEOUT_MILLIS + " ms: " + buffered());
                }
                readMore();
            }
        }

        /**
         * Reports whether the peer has closed its side, within a bounded wait.
         *
         * <p>Bounded so that a connection the server is deliberately holding open
         * reports {@code false} instead of hanging the harness.
         */
        boolean peerHasClosed() throws IOException {
            long deadline = System.nanoTime()
                    + Duration.ofMillis(RAW_CLOSE_TIMEOUT_MILLIS).toNanos();
            while (!peerClosed && System.nanoTime() < deadline) {
                readMore();
            }
            return peerClosed;
        }

        /** Reads whatever is available, recording end-of-stream when it arrives. */
        private void readMore() throws IOException {
            byte[] chunk = new byte[RAW_BUFFER_BYTES];
            try {
                int read = socket.getInputStream().read(chunk);
                if (read < 0) {
                    peerClosed = true;
                    return;
                }
                pending.write(chunk, 0, read);
            } catch (SocketTimeoutException quiet) {
                // Nothing arrived this interval. Whether that is a failure is the
                // caller's deadline to decide, not this method's.
            }
        }

        private String buffered() {
            return new String(pending.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        /**
         * Parses and consumes one response if a complete one is buffered.
         *
         * @return the response, or {@code null} while it is still incomplete
         */
        private RawResponse frame(boolean expectBody) {
            byte[] bytes = pending.toByteArray();
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            int separator = text.indexOf(HEAD_TERMINATOR);
            if (separator < 0) {
                return null;
            }
            String[] lines = text.substring(0, separator).split(CRLF);
            Map<String, String> headers = new LinkedHashMap<>();
            Set<String> names = new TreeSet<>();
            for (int index = 1; index < lines.length; index++) {
                int colon = lines[index].indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = lines[index].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                headers.put(name, lines[index].substring(colon + 1).trim());
                names.add(name);
            }
            int declared = expectBody && headers.containsKey("content-length")
                    ? Integer.parseInt(headers.get("content-length")) : 0;
            int bodyStart = separator + HEAD_TERMINATOR.length();
            if (bytes.length < bodyStart + declared) {
                return null;
            }
            byte[] body = Arrays.copyOfRange(bytes, bodyStart, bodyStart + declared);
            pending.reset();
            pending.write(bytes, bodyStart + declared, bytes.length - bodyStart - declared);
            String[] fields = lines[0].split(" ");
            int status = fields.length > 1 ? Integer.parseInt(fields[1]) : 0;
            return new RawResponse(lines[0], fields[0], status, headers, names, body);
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException alreadyGone) {
                // Closing a socket the peer has already dropped is not a failure.
            }
        }
    }

    /**
     * Assembles a raw HTTP request from literal lines.
     *
     * <p>Each line is terminated with CRLF and the block is closed with a blank
     * line, so a check writes exactly the bytes it means - including the malformed
     * ones no HTTP client would agree to send.
     */
    private static String wireRequest(List<String> lines) {
        return wireRequest(lines, "");
    }

    /**
     * Assembles a raw HTTP request with a body appended verbatim.
     */
    private static String wireRequest(List<String> lines, String body) {
        StringBuilder request = new StringBuilder();
        for (String line : lines) {
            request.append(line).append(CRLF);
        }
        return request.append(CRLF).append(body).toString();
    }

    private static String bodyText(RawResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /**
     * Sends one raw request on a fresh connection and applies assertions to the
     * single response it produces.
     *
     * <p>An I/O failure becomes a COUNTED failure rather than an escaped exception,
     * so one uncooperative case cannot abort the rest of the section and hide
     * findings behind the first stack trace.
     */
    private static void checkRawExchange(int port, String label, String request,
            RawAssertions assertions) {
        checkRawExchange(port, label, request, true, assertions);
    }

    /**
     * Sends one raw request and applies assertions, optionally expecting no body.
     */
    private static void checkRawExchange(int port, String label, String request,
            boolean expectBody, RawAssertions assertions) {
        try (RawConnection connection = new RawConnection(port)) {
            assertions.apply(connection.send(request).readResponse(expectBody));
        } catch (IOException failure) {
            recordRawFailure(label, failure);
        }
    }

    /**
     * Records a raw-socket I/O failure as a counted failure.
     */
    private static void recordRawFailure(String label, IOException failure) {
        checksExecuted++;
        checksFailed++;
        System.err.println("FAIL: " + label + " - the raw exchange failed: " + failure);
    }

    // Section I - configuration validation and the port grammar. A configuration
    // that cannot be published truthfully is refused BEFORE a socket is bound, which
    // is what makes the refusal total: there is no window in which a port is held by
    // a server that would answer 200 with a payload the contract forbids.
    //
    // Every message names the KEY and withholds the VALUE. That is what lets the
    // probe print the message verbatim without sanitising it a second time, and it is
    // why a configured value carrying CRLF cannot forge a log line through a refusal.

    /** The four refusal messages, worded identically in all three implementations. */
    private static final String NAME_REFUSAL =
            "invalid app.name: it must be non-empty text with no control character";
    private static final String VERSION_REFUSAL =
            "invalid app.version: it must be a three-part dotted numeric version";
    private static final String PATH_REFUSAL =
            "invalid health.path: it is not a valid request target";
    private static final String HOST_REFUSAL =
            "invalid app.host: it must be non-empty text with no control character";

    /**
     * Builds a configuration that is valid in every field, for one field to spoil.
     */
    private static User.Config configWith(String name, String version, String path, String host) {
        return new User.Config(name, version, path, host, 0);
    }

    private static void verifyConfigurationValidation() {
        User.Config sound = configWith("only_parent_parent_repo_10_LOC", "1.1.0",
                "/health", "127.0.0.1");
        checkEquals("the shipped configuration validates silently", "",
                withStderr(() -> User.validateConfig(sound)));

        for (String bad : List.of("", "a\nb", "a\rb", "a\u001bb", "a\u007fb")) {
            checkRefusalMessage("a name spelled " + describe(bad) + " is refused",
                    NAME_REFUSAL,
                    () -> User.validateConfig(configWith(bad, "1.1.0", "/health", "127.0.0.1")));
        }
        check("a name containing a space is ordinary text",
                User.isSingleLineText("my application"));

        for (String bad : List.of("", "1.1", "1.1.0.0", "v1.1.0", "1.1.0-rc1", "1..0", "1.1.")) {
            checkRefusalMessage("a version spelled " + describe(bad) + " is refused",
                    VERSION_REFUSAL,
                    () -> User.validateConfig(configWith("n", bad, "/health", "127.0.0.1")));
        }
        for (String good : List.of("1.1.0", "0.0.0", "10.20.30", "01.1.0")) {
            checkEquals("a version spelled " + describe(good) + " is accepted", "",
                    withStderr(() -> User.validateConfig(
                            configWith("n", good, "/health", "127.0.0.1"))));
        }

        for (String bad : List.of("", "/heal th", "/health\r\nX", "/health\n", "/hea\u001blth")) {
            checkRefusalMessage("a route spelled " + describe(bad) + " is refused",
                    PATH_REFUSAL,
                    () -> User.validateConfig(configWith("n", "1.1.0", bad, "127.0.0.1")));
        }

        // A network-path reference is refused in all three implementations. Nothing
        // in THIS runtime would notice it: "//health" survives normalisation
        // unchanged and HttpServer would serve it. The reason to refuse is that RFC
        // 3986 reads a leading "//" as the start of an authority, so a client, a
        // proxy or a sibling implementation may legitimately resolve the same
        // configured value against a different target - which would let one shared
        // configuration file describe three different endpoints.
        for (String bad : List.of("//health", "///health", "//health/", "//host/health")) {
            checkRefusalMessage("a network-path reference spelled " + describe(bad)
                            + " is refused", PATH_REFUSAL,
                    () -> User.validateConfig(configWith("n", "1.1.0", bad, "127.0.0.1")));
        }

        // The mirror image, and the reason the refusal above cannot be written as a
        // plain "must start with a slash" rule: the validator grades the route that
        // will be SERVED, not the configured text, so a value with no leading slash
        // is accepted and "health" and "/health" are the same configuration. "//"
        // is accepted because it reduces to "/" - it is a root route, not an
        // authority.
        for (String good : List.of("health", "healthz", "/health/", "/health?probe=1", "//")) {
            checkEquals("a route spelled " + describe(good) + " is accepted", "",
                    withStderr(() -> User.validateConfig(
                            configWith("n", "1.1.0", good, "127.0.0.1"))));
        }

        for (String bad : List.of("", "127.0.0.1\r\nX", "a\u0000b")) {
            checkRefusalMessage("a host spelled " + describe(bad) + " is refused",
                    HOST_REFUSAL,
                    () -> User.validateConfig(configWith("n", "1.1.0", "/health", bad)));
        }

        // The whole point of withholding the value: a configured name carrying CRLF
        // must not be able to write a second line into whatever collects stderr.
        String forged = "x\r\n[User] health endpoint listening on http://evil/";
        checkRefusalMessage("a refusal cannot be used to forge a log line", NAME_REFUSAL,
                () -> User.validateConfig(configWith(forged, "1.1.0", "/health", "127.0.0.1")));

        // isRequestTarget validates the CHARACTER SET, not rootedness: the leading
        // slash is supplied by the route resolver, so an unrooted value is a valid
        // target here and becomes "/health" there. Asserted so that division of
        // labour stays deliberate rather than accidental.
        check("a rooted route is a valid target", User.isRequestTarget("/health"));
        check("an unrooted route is still a valid target", User.isRequestTarget("health"));
        check("a space ends a request target", !User.isRequestTarget("/heal th"));
        check("DEL is not visible ASCII", !User.isRequestTarget("/health\u007f"));
        check("CRLF is not a request target", !User.isRequestTarget("/health\r\nX"));
        check("an empty string is not a request target", !User.isRequestTarget(""));

        // The port grammar. Character.digit accepts Arabic-Indic and other Unicode
        // decimal digits, so Integer.parseInt alone would read "\u0668\u0660\u0660\u0661"
        // as 8001 and this implementation would disagree with the other two about
        // which port had been requested.
        for (String bad : List.of("8_001", "0x1f41", "-8001", "8001.0",
                "\u0668\u0660\u0660\u0661", "80 01", "eighty", "   ")) {
            Properties stated = new Properties();
            stated.setProperty(KEY_JAVA_PORT, bad);
            checkRejects("a port spelled " + describe(bad) + " is refused, naming the value",
                    bad.trim().isEmpty() ? "invalid port value" : bad.trim(),
                    () -> User.resolvePort(stated, KEY_JAVA_PORT, ABSENT_ENV_PRIMARY,
                            ABSENT_ENV_SECONDARY, DEFAULT_JAVA_PORT));
        }
        // The grammar tolerates an explicit sign; the range check refuses a negative.
        // All three implementations share the pattern ^[+-]?[0-9]+$.
        checkEquals("a signed port is accepted by the grammar", 8001, portFrom("+8001"));
        checkEquals("a zero-padded port is accepted", 8001, portFrom("08001"));
        checkEquals("a padded numeric port is trimmed and accepted", 8080, portFrom(" 8080 "));
        for (String bad : List.of("65536", "70000", "999999")) {
            Properties stated = new Properties();
            stated.setProperty(KEY_JAVA_PORT, bad);
            checkRejects("a port outside the bindable range is refused", bad,
                    () -> User.resolvePort(stated, KEY_JAVA_PORT, ABSENT_ENV_PRIMARY,
                            ABSENT_ENV_SECONDARY, DEFAULT_JAVA_PORT));
        }
        checkEquals("an ephemeral port request is accepted", MIN_PORT, portFrom("0"));
        checkEquals("the top of the range is accepted", MAX_PORT, portFrom("65535"));
        // An EMPTY value is ABSENT rather than malformed, so it never reaches the
        // grammar and the built-in default applies. Identical in all three.
        checkEquals("an empty port value falls back to the default", DEFAULT_JAVA_PORT,
                portFrom(""));
    }

    /**
     * Resolves a port from a single stated properties value.
     *
     * <p>Both environment names are ones no test exports, so the file value is the
     * effective one and the resolution exercises the real code path.
     */
    private static int portFrom(String stated) {
        Properties props = new Properties();
        props.setProperty(KEY_JAVA_PORT, stated);
        return User.resolvePort(props, KEY_JAVA_PORT, ABSENT_ENV_PRIMARY,
                ABSENT_ENV_SECONDARY, DEFAULT_JAVA_PORT);
    }

    /**
     * Renders a value for a check name with its control characters made visible.
     *
     * <p>A test name containing a raw carriage return would corrupt the harness's
     * own output, which is the very failure mode these tests exist to prevent.
     */
    private static String describe(String value) {
        StringBuilder rendered = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\r' -> rendered.append("\\r");
                case '\n' -> rendered.append("\\n");
                case '\t' -> rendered.append("\\t");
                default -> {
                    if (character < 0x20 || character > 0x7E) {
                        rendered.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        rendered.append(character);
                    }
                }
            }
        }
        return rendered.append('"').toString();
    }

    // Section J - probe answer validation and connection reuse. A health probe's
    // caller can act only on an exit status, so the probe fails CLOSED: every doubt
    // resolves to unhealthy. It checks the whole document rather than searching the
    // body for a hopeful substring, because a substring test accepts a truncated body
    // that merely quotes the healthy fragment - a probe reporting health it never
    // established.
    //
    // The reuse tests cover a seam none of the three implementations could see alone:
    // a refused request that arrives WITH a body must not corrupt the next request on
    // the same connection.

    /**
     * The probe's body ceiling, stated independently of the implementation.
     *
     * <p>Written out rather than read from the module so this is a gate on the
     * agreed number - all three implementations use 8192 - rather than a mirror of
     * whatever the module happens to hold.
     */
    private static final int PROBE_BODY_LIMIT = 8192;

    /** Read budget for the reuse exchange, and the gap between its two requests. */
    private static final int REUSE_READ_TIMEOUT_MILLIS = 4000;
    private static final long REUSE_GAP_MILLIS = 300L;

    /** A document satisfying the contract in full, for one field to spoil. */
    private static final String SOUND_DOCUMENT =
            "{\"name\":\"n\",\"version\":\"1.1.0\","
            + "\"timestamp\":\"2026-07-29T08:00:00Z\",\"status\":\"UP\"}";

    /** The key-set reason, pinned byte for byte because an operator greps one deployment. */
    private static final String KEY_SET_REFUSAL =
            "body does not carry exactly the keys [\"name\",\"version\",\"timestamp\",\"status\"]"
            + " in order";

    /**
     * The reason every body that never becomes a readable document carries.
     *
     * <p>Pinned as a literal and asserted by EQUALITY rather than by non-nullness,
     * because the interesting property is not that these bodies are refused - it is
     * that all three implementations refuse them at the SAME STEP and word it the
     * same way. A duplicate member name, for instance, is settled while parsing by
     * every one of the three: this reader's member map, app.py's
     * {@code object_pairs_hook} and index.js's member scan all reject it before any
     * field rule is consulted. A check that only asserted non-nullness would pass
     * for an implementation that reported "the status field is not the expected
     * value" instead, and two operators grepping two deployments for one fault would
     * find two different strings.
     */
    private static final String MALFORMED_DOCUMENT_REFUSAL =
            "body is not the expected JSON document";

    /** The reason a readable document that is not an object carries. */
    private static final String NOT_AN_OBJECT_REFUSAL =
            "body is not a JSON object and carries no status field";

    /** The reason a name that is absent, empty or not a string carries. */
    private static final String NAME_FIELD_REFUSAL =
            "the name field is not a non-empty string";

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void verifyProbeValidationAndReuse() throws Exception {
        checkEquals("a sound answer is accepted", null,
                User.probeRejection(200, utf8(SOUND_DOCUMENT)));
        checkEquals("a non-200 answer is refused by code", "the endpoint answered status 500",
                User.probeRejection(500, utf8(SOUND_DOCUMENT)));
        checkEquals("a 404 answer is refused by code", "the endpoint answered status 404",
                User.probeRejection(404, utf8(SOUND_DOCUMENT)));

        // The fail-OPEN case a substring test accepts: these bytes contain
        // "status":"UP" and are not a JSON document at all.
        checkEquals("a truncated body quoting the healthy fragment is refused",
                "body is not the expected JSON document",
                User.probeRejection(200, utf8("{\"status\":\"UP\"")));

        checkEquals("a document reporting DOWN is refused",
                "the status field is not the expected value",
                User.probeRejection(200, utf8(SOUND_DOCUMENT.replace("\"UP\"", "\"DOWN\""))));
        checkEquals("an empty name is refused",
                "the name field is not a non-empty string",
                User.probeRejection(200, utf8(SOUND_DOCUMENT.replace("\"n\"", "\"\""))));
        checkEquals("a two-part version is refused",
                "the version field is not a three-part dotted numeric version",
                User.probeRejection(200, utf8(SOUND_DOCUMENT.replace("\"1.1.0\"", "\"1.1\""))));
        checkEquals("a sub-second timestamp is refused",
                "the timestamp field is not a whole-second UTC instant",
                User.probeRejection(200, utf8(SOUND_DOCUMENT
                        .replace("08:00:00Z", "08:00:00.500Z"))));

        checkEquals("a reordered key set is refused", KEY_SET_REFUSAL,
                User.probeRejection(200, utf8("{\"version\":\"1.1.0\",\"name\":\"n\","
                        + "\"timestamp\":\"2026-07-29T08:00:00Z\",\"status\":\"UP\"}")));
        checkEquals("an extra key is refused", KEY_SET_REFUSAL,
                User.probeRejection(200, utf8(SOUND_DOCUMENT.substring(0,
                        SOUND_DOCUMENT.length() - 1) + ",\"extra\":\"x\"}")));
        checkEquals("an empty object is refused", KEY_SET_REFUSAL,
                User.probeRejection(200, utf8("{}")));

        // A repeated member name is refused rather than letting the last one win,
        // which is what a permissive reader would do. The reason is asserted by
        // equality, and the same seven rows - same labels, same bytes, same expected
        // reason - are asserted in test_app.py and index.test.js.
        String tail = "\"version\":\"1.1.0\",\"timestamp\":\"2026-07-29T08:00:00Z\",\"status\":\"UP\"}";
        String[][] repeats = {
            {"a repeated member name whose last value disagrees",
             "{\"name\":\"n\",\"version\":\"1.1.0\",\"timestamp\":\"2026-07-29T08:00:00Z\","
                 + "\"status\":\"UP\",\"status\":\"DOWN\"}"},
            {"a repeated member name whose last value agrees",
             "{\"name\":\"n\",\"version\":\"1.1.0\",\"timestamp\":\"2026-07-29T08:00:00Z\","
                 + "\"status\":\"DOWN\",\"status\":\"UP\"}"},
            {"a repeated first member",
             "{\"name\":\"n\",\"name\":\"other\"," + tail},
            {"a repeat spelled with a unicode escape",
             "{\"name\":\"n\",\"\\u006eame\":\"other\"," + tail},
            {"a repeat nested inside a member value",
             "{\"name\":{\"a\":1,\"a\":2}," + tail},
            {"a repeat inside an array element", "[{\"a\":1,\"a\":2}]"},
            {"repeated empty-string keys",
             "{\"\":\"a\",\"\":\"b\",\"name\":\"n\"," + tail},
        };
        for (String[] row : repeats) {
            checkEquals(row[0] + " is refused as a malformed document",
                    MALFORMED_DOCUMENT_REFUSAL, User.probeRejection(200, utf8(row[1])));
        }

        // Found at every nesting level, because the member map is consulted on every
        // object this reader opens - which is what app.py's hook and index.js's scan
        // also do. A check that only examined the top-level object would agree with
        // them above and disagree here.
        for (int depth : new int[] {1, 2, 5, 20}) {
            String buried = "{\"name\":" + "{\"a\":".repeat(depth) + "{\"b\":1,\"b\":2}"
                    + "}".repeat(depth) + "," + tail;
            checkEquals("a repeat " + depth + " level(s) down is still refused",
                    MALFORMED_DOCUMENT_REFUSAL, User.probeRejection(200, utf8(buried)));
        }

        for (String body : List.of("[]", "\"UP\"", "42", "null", "true", "false")) {
            checkEquals("a body that is JSON but not an object is refused: " + describe(body),
                    NOT_AN_OBJECT_REFUSAL, User.probeRejection(200, utf8(body)));
        }
        // An empty body never becomes a document at all, so it is settled one step
        // earlier and carries the malformed reason instead - as it does in both siblings.
        checkEquals("an empty body is refused as a malformed document",
                MALFORMED_DOCUMENT_REFUSAL, User.probeRejection(200, new byte[0]));

        for (String value : List.of("1", "null", "true", "{}", "[\"x\"]")) {
            checkEquals("a name of " + describe(value) + " is refused",
                    NAME_FIELD_REFUSAL,
                    User.probeRejection(200, utf8("{\"name\":" + value + "," + tail)));
        }
        // A name of nothing but spaces is ACCEPTED by all three: the rule is
        // non-empty, not non-blank. Asserted so it cannot be tightened here alone.
        checkEquals("a name of only spaces is accepted, as in both siblings", null,
                User.probeRejection(200, utf8("{\"name\":\"   \"," + tail)));

        // Bytes that are not UTF-8 are refused rather than decoded to U+FFFD. This
        // reader's decoder reports a malformed sequence, app.py's strict decode
        // raises, and index.js decodes through a fatal TextDecoder, so one bad byte
        // inside a schema-shaped document is refused by all three at the same step.
        byte[][] illFormed = {
            {(byte) 0xC3, (byte) 0x28},
            {(byte) 0x80},
            {(byte) 0xE2, (byte) 0x82},
            {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
            {(byte) 0xC0, (byte) 0xAF},
            {(byte) 0xFE},
        };
        byte[] opening = utf8("{\"name\":\"");
        byte[] closing = utf8("\"," + tail);
        for (byte[] bad : illFormed) {
            byte[] body = new byte[opening.length + bad.length + closing.length];
            System.arraycopy(opening, 0, body, 0, opening.length);
            System.arraycopy(bad, 0, body, opening.length, bad.length);
            System.arraycopy(closing, 0, body, opening.length + bad.length, closing.length);
            checkEquals("a body carrying " + bad.length + " ill-formed byte(s) is refused",
                    MALFORMED_DOCUMENT_REFUSAL, User.probeRejection(200, body));
        }

        // A body that is not valid UTF-8 is refused rather than silently repaired.
        // new String(bytes, UTF_8) substitutes U+FFFD, which would turn a corrupt
        // answer into a merely wrong one and diverge from Python, where decoding
        // raises.
        check("a body that is not valid UTF-8 is refused",
                User.probeRejection(200, new byte[] {(byte) 0xC3, (byte) 0x28}) != null);

        // Size is checked FIRST, so an oversized body is refused on its size and
        // never parsed: the ceiling bounds the work as well as the memory.
        byte[] oversized = new byte[PROBE_BODY_LIMIT + 1];
        Arrays.fill(oversized, (byte) ' ');
        checkEquals("a body over the ceiling is refused on its size",
                "body exceeds the probe limit of " + PROBE_BODY_LIMIT + " bytes",
                User.probeRejection(200, oversized));
        check("size outranks status, which makes the ordering observable",
                User.probeRejection(500, oversized).startsWith("body exceeds the probe limit"));

        // The bound the request-time property installs. It is asserted through the
        // property rather than the constant, because a check that read the constant
        // it is checking would assert only that a value equals itself.
        User.HealthServer listener = User.createServer(configWith(
                "only_parent_parent_repo_10_LOC", "1.1.0", "/health", "127.0.0.1"));
        try {
            checkEquals("creating a server installs the platform request-time bound",
                    "15", System.getProperty("sun.net.httpserver.maxReqTime"));
            listener.start();
            verifyConnectionReuse(listener.port());
        } finally {
            listener.stop();
        }
    }

    /**
     * Drives a refused request carrying a body, then a GET on the SAME connection.
     *
     * <p>Draining is what makes this work. Left queued, those three bytes are
     * consumed as the start of the next request line, and the legitimate request
     * behind them is never answered. Node dumps an unconsumed request itself and
     * app.py drains explicitly, so all three share this behaviour.
     */
    private static void verifyConnectionReuse(int port) throws Exception {
        byte[] body = utf8("xyz");
        String received;
        try (Socket peer = new Socket(TEST_HOST, port)) {
            peer.setSoTimeout(REUSE_READ_TIMEOUT_MILLIS);
            OutputStream out = peer.getOutputStream();
            out.write(utf8("POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: "
                    + body.length + "\r\n\r\n"));
            out.write(body);
            out.flush();
            Thread.sleep(REUSE_GAP_MILLIS);
            out.write(utf8("GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"));
            out.flush();
            received = new String(peer.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        checkEquals("a body on a reused connection yields exactly two responses", 2,
                countOccurrences(received, "HTTP/1.1 "));
        check("the refused request is answered 405", received.contains("405 Method Not Allowed"));
        check("the following request is answered 200", received.contains("200 OK"));
        check("the following answer carries the payload", received.contains("\"status\":\"UP\""));
        check("no leftover byte is parsed as a request line", !received.contains("501"));
        check("no HTML error body is written",
                !received.toLowerCase(Locale.ROOT).contains("<html"));
    }

    /**
     * Counts non-overlapping occurrences of {@code needle} in {@code haystack}.
     */
    private static int countOccurrences(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }

    // Section K - the shared properties grammar and the failure policy. One file,
    // three parsers. java.util.Properties is the reference by construction - the
    // format was chosen so that this implementation needs no parser at all - and
    // app.py and index.js implement its grammar by hand. A fixture table is therefore
    // the only honest way to state that contract: the same documents and the same
    // malformed ones appear verbatim in test_app.py, index.test.js and here, with the
    // same expected results, so a divergence in any one runtime fails a named check
    // instead of surfacing later as two servers disagreeing about their own name.
    //
    // The rest of the section is the FAILURE policy, a contract in its own right:
    // absence is silent, and an unreadable file and a malformed file each produce
    // exactly ONE warning and then the built-in defaults. Those outcomes are asserted
    // from OUTSIDE the process in a child JVM given its own directory, because the
    // real path - configLocation, loadProperties, the catch order - is private and
    // takes no file argument, so a child is the only place it can be observed at all
    // rather than re-implemented and asserted against itself.

    /** One row of the cross-language properties grammar table. */
    private record GrammarFixture(String label, String text, Map<String, String> expected) {
    }

    /**
     * U+FEFF, built from its code point rather than written as an escape.
     *
     * <p>A {@code \\u} escape in Java source is processed before the lexer runs, so
     * writing it inline would put a real byte-order mark in the source file. Naming
     * it here keeps the fixture readable and the file plain ASCII.
     */
    private static final String BYTE_ORDER_MARK = String.valueOf((char) 0xFEFF);

    /**
     * The grammar, one document per row, with the map it must produce.
     *
     * <p>Identical - same labels, same text, same expectations - to
     * {@code SHARED_PROPERTIES_FIXTURES} in {@code test_app.py} and
     * {@code index.test.js}. Here the assertion is a round trip against the
     * reference implementation, which is what makes the same table in the other two
     * suites a statement about parity rather than about their own opinion.
     */
    private static final List<GrammarFixture> SHARED_PROPERTIES_FIXTURES = List.of(
            new GrammarFixture("a plain key and value", "a=1\n", Map.of("a", "1")),
            new GrammarFixture("a colon separator", "a:1\n", Map.of("a", "1")),
            new GrammarFixture("a space separator", "a 1\n", Map.of("a", "1")),
            new GrammarFixture("a tab separator", "a\t1\n", Map.of("a", "1")),
            new GrammarFixture("a form-feed separator", "a\f1\n", Map.of("a", "1")),
            new GrammarFixture("whitespace around the separator", "a = 1\n", Map.of("a", "1")),
            new GrammarFixture("trailing value whitespace is preserved", "a=1   \n",
                    Map.of("a", "1   ")),
            new GrammarFixture("a whitespace-only value is empty", "a=   \n", Map.of("a", "")),
            new GrammarFixture("a key with no separator has an empty value", "abc\n",
                    Map.of("abc", "")),
            new GrammarFixture("an empty key is still a key", "=v\n", Map.of("", "v")),
            new GrammarFixture("only the first separator separates", "a = b=c \n",
                    Map.of("a", "b=c ")),
            new GrammarFixture("an escaped space belongs to the key", "a\\ b=x\n",
                    Map.of("a b", "x")),
            new GrammarFixture("an escaped equals belongs to the key", "a\\=b=x\n",
                    Map.of("a=b", "x")),
            new GrammarFixture("an escaped colon belongs to the key", "a\\:b=x\n",
                    Map.of("a:b", "x")),
            new GrammarFixture("a tab escape in a value", "a=x\\ty\n", Map.of("a", "x\ty")),
            new GrammarFixture("a newline escape in a value", "a=x\\nz\n", Map.of("a", "x\nz")),
            new GrammarFixture("a unicode escape in a value", "a=\\u0041\n", Map.of("a", "A")),
            new GrammarFixture("a capital U is not a unicode escape", "a=\\U0041\n",
                    Map.of("a", "U0041")),
            new GrammarFixture("an unknown escape is the character itself", "a=\\z\n",
                    Map.of("a", "z")),
            new GrammarFixture("an escaped backslash is one backslash", "a=x\\\\y\n",
                    Map.of("a", "x\\y")),
            new GrammarFixture("an odd trailing backslash continues the line",
                    "a=one\\\n   two\n", Map.of("a", "onetwo")),
            new GrammarFixture("an even trailing backslash ends the line", "a=v\\\\\nb=2\n",
                    Map.of("a", "v\\", "b", "2")),
            new GrammarFixture("a hash comment is skipped", "#c\na=1\n", Map.of("a", "1")),
            new GrammarFixture("a bang comment is skipped", "!c\na=1\n", Map.of("a", "1")),
            new GrammarFixture("an indented comment is skipped", "   # c\na=1\n",
                    Map.of("a", "1")),
            new GrammarFixture("a continuation line is data, not a comment", "a=x\\\n#y\n",
                    Map.of("a", "x#y")),
            new GrammarFixture("CR, LF and CRLF all end a line", "a=1\r\nb=2\rc=3\n",
                    Map.of("a", "1", "b", "2", "c", "3")),
            new GrammarFixture("the last of a repeated key wins", "a=1\na=2\n", Map.of("a", "2")),
            new GrammarFixture("quote characters are literal", "a=\"q\"\n", Map.of("a", "\"q\"")),
            new GrammarFixture("a trailing backslash at end of input is dropped", "a=v\\",
                    Map.of("a", "v")),
            new GrammarFixture("a byte-order mark is not stripped", BYTE_ORDER_MARK + "a=1\n",
                    Map.of(BYTE_ORDER_MARK + "a", "1")));

    /**
     * Documents that must be refused outright rather than read literally.
     *
     * <p>A short, non-hexadecimal or truncated {@code \\uXXXX} escape makes the
     * whole document malformed - it is NOT a literal {@code \\u12}. That
     * distinction is the one place a hand-written parser is most likely to be
     * lenient, and a lenient parser would publish a name the reference
     * implementation would refuse.
     */
    private static final List<Map.Entry<String, String>> SHARED_MALFORMED_PROPERTIES = List.of(
            Map.entry("a short unicode escape in a value", "a=\\u12\n"),
            Map.entry("a non-hexadecimal unicode escape", "a=\\uZZZZ\n"),
            Map.entry("a malformed unicode escape in a key", "\\u12=v\n"));

    /**
     * The reference implementation's own wording for a malformed escape.
     *
     * <p>Asserted so that the refusal is the documented one and not, say, a
     * {@code NullPointerException} that happens to abort the parse. app.py raises
     * {@code PropertiesFormatError} and index.js a {@code RangeError}, each
     * carrying their own reason text; the internal wording is deliberately NOT
     * shared, because it never leaves the process. What is shared, and what all
     * three suites assert, is the OBSERVABLE outcome below: one warning line, then
     * the built-in defaults.
     */
    private static final String MALFORMED_ESCAPE_REASON = "Malformed \\uxxxx encoding.";

    /** The exact warning all three implementations emit for a file they cannot read. */
    private static final String UNREADABLE_CONFIG_WARNING =
            "cannot read the configuration file; using defaults";

    /** The exact warning all three implementations emit for a malformed file. */
    private static final String MALFORMED_CONFIG_WARNING =
            "the configuration file is malformed; using defaults";

    /** Prefix every diagnostic carries, on standard error and never on standard out. */
    private static final String DIAGNOSTIC_PREFIX = "[User] ";

    /**
     * Asserts the shared grammar and then the shared failure policy.
     */
    private static void verifySharedPropertiesGrammar() throws IOException {
        for (GrammarFixture fixture : SHARED_PROPERTIES_FIXTURES) {
            checkEquals("the shared grammar reads " + fixture.label(),
                    fixture.expected(), parseSharedProperties(fixture.text()));
        }
        for (Map.Entry<String, String> malformed : SHARED_MALFORMED_PROPERTIES) {
            checkRefusalMessage("the shared grammar refuses " + malformed.getKey(),
                    MALFORMED_ESCAPE_REASON,
                    () -> parseSharedProperties(malformed.getValue()));
        }
        verifyConfigurationFailurePolicy();
    }

    /**
     * Parses properties text the way the application parses its file.
     *
     * <p>{@code Properties.load(Reader)} - the character-based overload, not the
     * byte-based one - because the application reads UTF-8 through a decoding
     * reader and the two overloads disagree about every non-ASCII byte.
     */
    private static Map<String, String> parseSharedProperties(String text) {
        Properties parsed = new Properties();
        try (StringReader reader = new StringReader(text)) {
            parsed.load(reader);
        } catch (IOException impossible) {
            throw new IllegalStateException("reading from a string cannot fail", impossible);
        }
        Map<String, String> flattened = new LinkedHashMap<>();
        for (String key : parsed.stringPropertyNames()) {
            flattened.put(key, parsed.getProperty(key));
        }
        return flattened;
    }

    /**
     * Asserts the three-outcome failure policy from outside the process.
     *
     * <p>Five cases, in an order that makes each one interpretable. The two NEGATIVE
     * controls come first: an absent file, and a well-formed one delivered exactly the
     * same way. Without the second, every warning assertion that follows could be
     * passing because the file was never found at all.
     *
     * <p>Every case runs {@code --probe} with an unparseable port value, so the child
     * refuses before it opens a socket. That makes the run deterministic and
     * network-free: the exit status is always 1 for the same stated reason, and the
     * only thing that varies is which configuration warning, if any, precedes it.
     *
     * <p>The child's working directory is the enclosure, not the repository. Left
     * pointing at the repository it would find the repository's own properties file as
     * its second candidate, and the absent-file case would silently become a
     * well-formed-file case.
     */
    private static void verifyConfigurationFailurePolicy() throws IOException {
        Path enclosure = Files.createTempDirectory("usertest-config-policy-");
        try {
            List<String> prefix = isolatedLaunchPrefix(enclosure);
            Path config = enclosure.resolve(CONFIG_FILE_NAME);

            // 1. ABSENT - the only silent outcome. Every key has a built-in default,
            //    and a health endpoint that refused to start for want of an optional
            //    file would defeat the purpose of having a health endpoint.
            check("the enclosure genuinely holds no configuration file", !Files.exists(config));
            checkConfigurationDiagnostics("an absent configuration file",
                    probeInEnclosure(prefix, enclosure, UNUSABLE_PORT_VALUE), 0, 0);

            // 2. WELL-FORMED, delivered identically - the control that gives the
            //    three failure cases their meaning. That the file was READ rather
            //    than merely present is proved by WHICH refusal arrives: this child
            //    is given no environment at all, so the built-in default port would
            //    have been perfectly usable and the child would have connected and
            //    reported that it could not reach the endpoint. Getting the port
            //    CATEGORY instead can only mean the file's own unusable value was
            //    the one resolved.
            //
            //    Proving it this way rather than by finding the value in the text is
            //    deliberate and is the stronger form: the diagnostic withholds the
            //    value on purpose, so a control that depended on seeing it would be a
            //    control that only worked while the disclosure existed.
            Files.writeString(config, KEY_JAVA_PORT + "=" + UNUSABLE_PORT_VALUE + "\n",
                    StandardCharsets.UTF_8);
            ChildOutcome wellFormed = probeInEnclosure(prefix, enclosure, null);
            checkConfigurationDiagnostics("a well-formed configuration file", wellFormed, 0, 0);
            check("the isolated file was read, not merely present",
                    wellFormed.errorText().contains(PORT_REFUSAL_CATEGORY));
            check("the isolated file's port was resolved, so no socket was opened",
                    !wellFormed.errorText().contains(PROBE_REACH_PREFIX));
            check("the isolated file's value is not echoed into the refusal",
                    !wellFormed.errorText().contains(UNUSABLE_PORT_VALUE));

            // 3. MALFORMED - one warning, then the defaults.
            Files.writeString(config, SHARED_MALFORMED_PROPERTIES.get(0).getValue(),
                    StandardCharsets.UTF_8);
            checkConfigurationDiagnostics("a malformed configuration file",
                    probeInEnclosure(prefix, enclosure, UNUSABLE_PORT_VALUE), 0, 1);

            // 4. NOT UTF-8 - the decoding reader raises MalformedInputException, an
            //    IOException, so this lands on the unreadable path exactly as it does
            //    in app.py and index.js. Never U+FFFD: a silently substituted
            //    replacement character would let this implementation publish a name
            //    its siblings could not.
            Files.write(config, new byte[] {'a', '=', 'c', 'a', 'f', (byte) 0xE9, '\n'});
            checkConfigurationDiagnostics("a configuration file that is not UTF-8",
                    probeInEnclosure(prefix, enclosure, UNUSABLE_PORT_VALUE), 1, 0);

            // 5. UNREADABLE - one warning, then the defaults. A DIRECTORY standing
            //    where the file belongs is the portable fixture: a permission bit
            //    does not stop a process running as root, which is how CI and all
            //    three container images run, so a chmod-based fixture would pass
            //    vacuously there.
            Files.delete(config);
            Files.createDirectory(config);
            checkConfigurationDiagnostics("an unreadable configuration file",
                    probeInEnclosure(prefix, enclosure, UNUSABLE_PORT_VALUE), 1, 0);
        } finally {
            deleteRecursively(enclosure);
        }
        check("the configuration-policy enclosure was removed", !Files.exists(enclosure));
    }

    /**
     * Runs {@code --probe} in an isolated enclosure and collects its diagnostics.
     *
     * @param portOverride a {@code JAVA_PORT} value to export, or {@code null} to
     *                     give the child no environment at all
     */
    private static ChildOutcome probeInEnclosure(List<String> prefix, Path enclosure,
            String portOverride) {
        Map<String, String> environment = new LinkedHashMap<>();
        if (portOverride != null) {
            environment.put(ENV_JAVA_PORT, portOverride);
        }
        return runApplication(prefix, enclosure, List.of(FLAG_PROBE), environment);
    }

    /**
     * Asserts how many configuration warnings a child emitted, and no more.
     *
     * <p>Counting rather than searching is the point: the contract is exactly ONE
     * warning per failed load. A loader that read the file twice would emit two
     * identical lines and a substring search would call that correct.
     */
    private static void checkConfigurationDiagnostics(String label, ChildOutcome outcome,
            int expectedUnreadableWarnings, int expectedMalformedWarnings) {
        String diagnostics = outcome.errorText();
        checkEquals("--probe fails closed with " + label, EXIT_FAILURE, outcome.status());
        checkBytesEqual(label + " leaves standard output untouched",
                new byte[0], outcome.standardOutput());
        checkEquals(label + " emits " + expectedUnreadableWarnings
                        + " unreadable-file warning(s)", expectedUnreadableWarnings,
                countOccurrences(diagnostics, UNREADABLE_CONFIG_WARNING));
        checkEquals(label + " emits " + expectedMalformedWarnings
                        + " malformed-file warning(s)", expectedMalformedWarnings,
                countOccurrences(diagnostics, MALFORMED_CONFIG_WARNING));
        if (expectedUnreadableWarnings + expectedMalformedWarnings > 0) {
            check(label + " reports on standard error under the usual prefix",
                    diagnostics.startsWith(DIAGNOSTIC_PREFIX));
        }
    }

    // Section L - probe identity and media-type verification. probeRejection in
    // section J grades the SHAPE of an answer, and a document satisfying that shape
    // is what any conforming implementation serves - so on its own it lets a
    // different application holding this loopback port vouch for this one. Measured
    // before the fix, over a real socket: a decoy serving "name":"IMPOSTOR", one
    // serving "version":"9.9.9" and one serving a correct document as text/html all
    // made --probe exit 0, in this implementation and in the other two. The identity
    // step closes that, and every check below is worded and ordered identically in
    // test_app.py (TestProbeIdentity) and index.test.js (group G2), because the three
    // implementations answer the same container HEALTHCHECK contract.

    /** The reason an answer that does not name exactly one JSON media type carries. */
    private static final String MEDIA_TYPE_REFUSAL =
            "the answer is not served as application/json";

    /** The reason a conforming document naming another application carries. */
    private static final String IDENTITY_NAME_REFUSAL =
            "the name field is not this application's name";

    /** The reason a conforming document naming another version carries. */
    private static final String IDENTITY_VERSION_REFUSAL =
            "the version field is not this application's version";

    /**
     * Bytes of the rendered document that are neither the name nor the version: the
     * four keys, the punctuation, the 20-character instant and the status. Asserted
     * against the renderer rather than trusted, because it is the constant the
     * app.name budget in app.config.properties and .env.example is computed from.
     */
    private static final int RENDERED_FIXED_OVERHEAD_BYTES = 73;

    /** A conforming health document carrying a stated identity. */
    private static String identityDocument(String name, String version) {
        return User.renderPayload(name, version, REFERENCE_TIMESTAMP, STATUS_UP);
    }

    /** Grades an answer against the shipped identity. */
    private static String identityReject(List<String> contentTypes, String body) {
        return User.identityRejection(contentTypes, utf8(body),
                DEFAULT_APP_NAME, DEFAULT_APP_VERSION);
    }

    private static void verifyProbeIdentity() throws Exception {
        // RFC 9110 sections 8.3, 8.3.1 and 5.6.2, one row each.
        Map<String, String> reduced = new LinkedHashMap<>();
        reduced.put("application/json", "application/json");
        reduced.put("application/json; charset=utf-8", "application/json");
        reduced.put("application/json;charset=UTF-8", "application/json");
        reduced.put("APPLICATION/JSON", "application/json");
        reduced.put("  application/json  ", "application/json");
        reduced.put("text/html", "text/html");
        for (Map.Entry<String, String> row : reduced.entrySet()) {
            checkEquals("the sole media type of " + describe(row.getKey()) + " is "
                            + row.getValue(), row.getValue(),
                    User.soleMediaType(List.of(row.getKey())));
        }

        // The rule that keeps the three implementations in step. Their clients
        // disagree about a REPEATED Content-Type: app.py's joins the values with
        // ", ", index.js's res.headers keeps the first and discards the rest, and
        // this one's allValues exposes every one. Grading whichever value a client
        // surfaced would let one implementation accept a duplicate the other two
        // refused, so every answer that does not name exactly one media type
        // reduces to "". The non-string row the other two also assert has no
        // analogue here, because this list is typed.
        List<List<String>> nothing = new ArrayList<>();
        nothing.add(List.of());
        nothing.add(null);
        nothing.add(List.of("application/json", "text/html"));
        nothing.add(List.of("text/html", "application/json"));
        nothing.add(List.of("application/json", "application/json"));
        nothing.add(Arrays.asList((String) null));
        for (List<String> values : nothing) {
            checkEquals("a header block naming " + (values == null ? "no field" : values.size()
                            + " value(s)") + " reduces to nothing", "",
                    User.soleMediaType(values));
        }

        // The positive control for every rejection below.
        String shipped = identityDocument(DEFAULT_APP_NAME, DEFAULT_APP_VERSION);
        checkEquals("our own document served as JSON is accepted", null,
                identityReject(List.of("application/json"), shipped));
        checkEquals("a charset parameter changes nothing", null,
                identityReject(List.of("application/json; charset=utf-8"), shipped));

        for (String value : List.of("text/html", "text/plain", "application/health+json", "")) {
            checkEquals("a conforming document served as " + describe(value) + " is refused",
                    MEDIA_TYPE_REFUSAL, identityReject(List.of(value), shipped));
        }
        checkEquals("a conforming document with no media type at all is refused",
                MEDIA_TYPE_REFUSAL, identityReject(List.of(), shipped));
        checkEquals("a null header list is refused rather than trusted",
                MEDIA_TYPE_REFUSAL, identityReject(null, shipped));

        for (String name : List.of("IMPOSTOR", "", DEFAULT_APP_NAME + "x",
                DEFAULT_APP_NAME.toUpperCase(Locale.ROOT), " " + DEFAULT_APP_NAME)) {
            checkEquals("a document naming " + describe(name) + " is refused",
                    IDENTITY_NAME_REFUSAL, identityReject(List.of("application/json"),
                            identityDocument(name, DEFAULT_APP_VERSION)));
        }

        // A rolling deployment is the case that matters: the answer is a valid
        // health document from the same codebase at a different version, so only an
        // exact comparison can tell it apart from this process's own answer.
        for (String version : List.of("9.9.9", "1.1.1", "1.2.0", "0.1.1")) {
            checkEquals("a document reporting version " + version + " is refused",
                    IDENTITY_VERSION_REFUSAL, identityReject(List.of("application/json"),
                            identityDocument(DEFAULT_APP_NAME, version)));
        }

        // The order is part of the contract, so it is asserted rather than assumed:
        // with the framing and both identity fields wrong at once, the framing is
        // what gets reported, and with the framing right the name outranks the
        // version.
        String wrong = identityDocument("IMPOSTOR", "9.9.9");
        checkEquals("the media type is graded before the identity", MEDIA_TYPE_REFUSAL,
                identityReject(List.of("text/html"), wrong));
        checkEquals("the name is graded before the version", IDENTITY_NAME_REFUSAL,
                identityReject(List.of("application/json"), wrong));

        // A response body is an input, and an input reaching a log line verbatim is
        // how a forged entry gets written.
        String planted = "QaW002IdentityMarker";
        for (String body : List.of(identityDocument(planted, DEFAULT_APP_VERSION),
                identityDocument(DEFAULT_APP_NAME, planted))) {
            for (List<String> values : List.of(List.of("application/json"), List.of(planted))) {
                String reason = identityReject(values, body);
                check("a refusal never echoes the value the answer supplied",
                        reason != null && !reason.contains(planted));
            }
        }

        // Unreachable through probe, which grades shape first, and asserted anyway:
        // this method is reachable from the harness, so it has to be total.
        for (String shape : List.of("{\"status\":\"UP\"", "[]", "null", "")) {
            check("a body shaped " + describe(shape) + " fails closed on a direct call",
                    identityReject(List.of("application/json"), shape) != null);
        }
        check("a body that is not valid UTF-8 fails closed on a direct call",
                User.identityRejection(List.of("application/json"),
                        new byte[] {(byte) 0x7B, (byte) 0xC3, (byte) 0x28, (byte) 0x7D},
                        DEFAULT_APP_NAME, DEFAULT_APP_VERSION) != null);

        verifyProbeIdentityOverSockets();
        verifyProbeCeilingBudget();
    }

    /**
     * Drives {@code --probe} against decoy listeners, which is the finding itself.
     *
     * <p>Five decoys, each serving bytes this application would never serve on a port
     * this application would be probed on: a document naming another application, one
     * naming another version, a correct document framed as HTML, one framed as nothing
     * at all, and one whose Content-Type is repeated. Each must fail closed with the
     * shared reason and one line, and the two positive controls that follow must
     * still succeed in silence - otherwise the refusals above would prove only that
     * the identity step refuses everything.
     */
    private static void verifyProbeIdentityOverSockets() throws IOException {
        String shipped = identityDocument(DEFAULT_APP_NAME, DEFAULT_APP_VERSION);
        checkDecoyRefused("a decoy naming another application",
                identityDocument("IMPOSTOR", DEFAULT_APP_VERSION),
                List.of("application/json"), IDENTITY_NAME_REFUSAL);
        checkDecoyRefused("a decoy reporting another version",
                identityDocument(DEFAULT_APP_NAME, "9.9.9"),
                List.of("application/json"), IDENTITY_VERSION_REFUSAL);
        checkDecoyRefused("a decoy framing a correct document as HTML",
                shipped, List.of("text/html"), MEDIA_TYPE_REFUSAL);
        checkDecoyRefused("a decoy framing a correct document as nothing",
                shipped, List.of(), MEDIA_TYPE_REFUSAL);
        checkDecoyRefused("a decoy repeating the media type",
                shipped, List.of("application/json", "application/json"), MEDIA_TYPE_REFUSAL);

        // The end-to-end positive control: the decoys above must fail because of
        // what they served, not because the identity step refuses everything.
        checkProbeAccepts("the shipped contract is still healthy and silent",
                shipped, DEFAULT_APP_NAME, DEFAULT_APP_VERSION);
        // Identity is compared against the CONFIGURATION, not against a literal, so
        // an overridden name and version are what the probe then requires.
        checkProbeAccepts("a deployment that renames itself still probes itself healthy",
                identityDocument("renamed-service", "4.5.6"), "renamed-service", "4.5.6");
    }

    /** Asserts that probing a decoy fails closed with exactly the shared reason. */
    private static void checkDecoyRefused(String label, String body, List<String> contentTypes,
            String expected) throws IOException {
        try (DecoyListener decoy = new DecoyListener(decoyResponse(body, contentTypes))) {
            int[] verdict = new int[1];
            String written = withStderr(() -> verdict[0] = User.probe(new User.Config(
                    DEFAULT_APP_NAME, DEFAULT_APP_VERSION, DEFAULT_HEALTH_PATH,
                    TEST_HOST, decoy.port())));
            checkEquals(label + " fails closed", EXIT_FAILURE, verdict[0]);
            checkEquals(label + " reports exactly the shared reason and one line",
                    DIAGNOSTIC_PREFIX + "probe rejected: " + expected
                            + System.lineSeparator(), written);
        }
    }

    /** Asserts that probing a listener serving `body` as JSON succeeds in silence. */
    private static void checkProbeAccepts(String label, String body, String name, String version)
            throws IOException {
        try (DecoyListener listener = new DecoyListener(
                decoyResponse(body, List.of("application/json")))) {
            int[] verdict = new int[1];
            String written = withStderr(() -> verdict[0] = User.probe(new User.Config(
                    name, version, DEFAULT_HEALTH_PATH, TEST_HOST, listener.port())));
            checkEquals(label, EXIT_SUCCESS, verdict[0]);
            checkEquals(label + " says nothing at all", "", written);
        }
    }

    /**
     * Builds a raw 200 answer carrying exactly these header lines and body.
     *
     * <p>Assembled by hand rather than served by an {@code HttpServer}, because two
     * of the cases above are about the header block itself - the media type absent,
     * and the field repeated - which a server API normalises out of reach.
     */
    private static byte[] decoyResponse(String body, List<String> contentTypes) {
        byte[] payload = utf8(body);
        StringBuilder head = new StringBuilder("HTTP/1.1 200 OK\r\nContent-Length: ")
                .append(payload.length).append("\r\n");
        for (String value : contentTypes) {
            head.append("Content-Type: ").append(value).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        byte[] prefix = utf8(head.toString());
        byte[] whole = new byte[prefix.length + payload.length];
        System.arraycopy(prefix, 0, whole, 0, prefix.length);
        System.arraycopy(payload, 0, whole, prefix.length, payload.length);
        return whole;
    }

    /**
     * A loopback listener that answers every request with one fixed byte string.
     *
     * <p>Bound to {@link #TEST_HOST} on an ephemeral port, served by a daemon thread
     * so an abandoned accept can never hold the harness open, and closed by
     * try-with-resources on every path. It answers repeatedly rather than once,
     * because a client that retries must not see a refused connection and report the
     * wrong fault category.
     */
    private static final class DecoyListener implements AutoCloseable {

        private final ServerSocket socket;

        DecoyListener(byte[] response) throws IOException {
            socket = new ServerSocket(0, 1, InetAddress.getByName(TEST_HOST));
            Thread worker = new Thread(() -> serve(response), "decoy-listener");
            worker.setDaemon(true);
            worker.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void serve(byte[] response) {
            while (!socket.isClosed()) {
                try (Socket peer = socket.accept()) {
                    peer.setSoTimeout(REUSE_READ_TIMEOUT_MILLIS);
                    InputStream in = peer.getInputStream();
                    // Read the request head and discard it: what is asserted is how
                    // the probe grades the ANSWER, so the question does not matter.
                    int consecutive = 0;
                    int next;
                    while (consecutive < 2 && (next = in.read()) >= 0) {
                        if (next == '\n') {
                            consecutive++;
                        } else if (next != '\r') {
                            consecutive = 0;
                        }
                    }
                    OutputStream out = peer.getOutputStream();
                    out.write(response);
                    out.flush();
                } catch (IOException stopped) {
                    // Closing the listener interrupts the accept above; a peer that
                    // hangs up early is equally uninteresting. Either way the next
                    // loop test ends the thread.
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * The probe body ceiling, from the operator's side.
     *
     * <p>F-15: the ceiling is what stops an endless stream, so it is deliberately not
     * raised - which means a long enough {@code app.name} makes this application's OWN
     * healthy answer too large to read and the probe fail closed on a working process,
     * which a container health check then restarts. The budget is documented in
     * app.config.properties and .env.example, and these checks are what keep the
     * documented arithmetic true.
     */
    private static void verifyProbeCeilingBudget() throws IOException {
        int overhead = utf8(identityDocument(DEFAULT_APP_NAME, DEFAULT_APP_VERSION)).length
                - DEFAULT_APP_NAME.length() - DEFAULT_APP_VERSION.length();
        checkEquals("the documented overhead is the measured overhead",
                RENDERED_FIXED_OVERHEAD_BYTES, overhead);
        checkEquals("the shipped identity renders the reference length",
                REFERENCE_BODY_BYTE_LENGTH,
                utf8(identityDocument(DEFAULT_APP_NAME, DEFAULT_APP_VERSION)).length);

        int budget = PROBE_BODY_LIMIT - RENDERED_FIXED_OVERHEAD_BYTES
                - DEFAULT_APP_VERSION.length();
        String fitting = "a".repeat(budget);
        checkEquals("the largest name inside the budget renders exactly to the ceiling",
                PROBE_BODY_LIMIT, utf8(identityDocument(fitting, DEFAULT_APP_VERSION)).length);
        checkEquals("one byte more renders one byte past it", PROBE_BODY_LIMIT + 1,
                utf8(identityDocument("a".repeat(budget + 1), DEFAULT_APP_VERSION)).length);
        checkEquals("an answer at the ceiling is still readable", null,
                User.probeRejection(200, utf8(identityDocument(fitting, DEFAULT_APP_VERSION))));
        check("an answer one byte past it is refused on its size",
                User.probeRejection(200, utf8(identityDocument("a".repeat(budget + 1),
                        DEFAULT_APP_VERSION))).startsWith("body exceeds the probe limit"));

        // An operator setting a name in an astral script spends four bytes per
        // character, which is the part of the budget a character count would miss.
        String astral = "\uD83D\uDE00".repeat(budget / 4);
        int rendered = utf8(identityDocument(astral, DEFAULT_APP_VERSION)).length;
        check("the budget counts bytes and not characters",
                rendered <= PROBE_BODY_LIMIT && rendered > PROBE_BODY_LIMIT - 4);
        check("one astral character more crosses the ceiling",
                utf8(identityDocument(astral + "\uD83D\uDE00",
                        DEFAULT_APP_VERSION)).length > PROBE_BODY_LIMIT);
        check("the same character count in ASCII is nowhere near the ceiling",
                utf8(identityDocument("a".repeat(astral.codePointCount(0, astral.length())),
                        DEFAULT_APP_VERSION)).length < 4000);

        // The drift detector. If the arithmetic above changes, the two files an
        // operator sets app.name from must change with it.
        List<String> numbers = List.of(String.valueOf(PROBE_BODY_LIMIT),
                String.valueOf(RENDERED_FIXED_OVERHEAD_BYTES), String.valueOf(budget),
                String.valueOf(PROBE_BODY_LIMIT - RENDERED_FIXED_OVERHEAD_BYTES));
        for (String filename : List.of("app.config.properties", ".env.example")) {
            String text = Files.readString(Path.of(filename), StandardCharsets.UTF_8);
            for (String number : numbers) {
                check(filename + " states the measured " + number, text.contains(number));
            }
        }
    }
}
