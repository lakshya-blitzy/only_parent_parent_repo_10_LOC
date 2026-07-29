/*
 * =============================================================================
 * UserTest.java - the assertion harness for User.java (feature F-009).
 * =============================================================================
 *
 * HOW TO RUN
 * ----------
 *   java UserTest.java            From the repository root, with no classpath,
 *                                 no build step and no test framework. The JDK's
 *                                 multi-file source launcher resolves and
 *                                 compiles the sibling User.java automatically.
 *   javac -Xlint:all -d /tmp/out User.java UserTest.java
 *   java -cp /tmp/out UserTest    The compiled equivalent, used by the CI job
 *                                 that also asserts zero compiler warnings.
 *
 * Exit status is the whole contract: 0 when every check passed, 1 when any check
 * failed or anything unexpected was thrown. The final line reports the number of
 * checks executed, which the continuous-integration gate reads to prove that a
 * non-zero number of checks actually ran - a harness that silently executes
 * nothing while exiting 0 is worse than no harness at all.
 *
 * ZERO DEPENDENCIES
 * -----------------
 * JDK only. There is no JUnit, TestNG, AssertJ or Hamcrest here, and no Maven,
 * Gradle or wrapper anywhere in the repository. Installing a runner tree into a
 * repository this small would be disproportionate and would destroy the
 * zero-dependency property that the application itself preserves, so the
 * assertion engine below is roughly forty lines of plain Java. The HTTP client
 * used by the live-routing section is java.net.http from the standard module
 * graph, exactly as the application's own self-check uses it.
 *
 * DEFAULT UNNAMED PACKAGE - DELIBERATE
 * ------------------------------------
 * There is deliberately no package declaration. That is what lets
 * "java UserTest.java" resolve the sibling User class from source, and it is
 * what makes User's package-private helpers - configProperties, resolve,
 * resolvePort, the accessors, timestamp, jsonEscape, renderPayload,
 * healthPayload, normalisePath, createServer, startServer and probe - reachable
 * from here. Those members carry default access precisely so that this file can
 * exercise them without any of them being widened to public for testing.
 *
 * WHAT IS ASSERTED, AND WHY EACH GROUP EXISTS
 * -------------------------------------------
 *   A  Preserved legacy behaviour. The original program printed "Test" and
 *      exited 0, and that must remain byte-identical: the backward-compatibility
 *      requirement is the one constraint that outranks the new feature. This
 *      section runs the default mode in an ISOLATED CHILD JVM and reads its exit
 *      status and its two streams as raw bytes. See "WHY A CHILD JVM" below -
 *      the isolation is what makes the check trustworthy, not merely tidy.
 *   B  The frozen response contract: four keys, in the order name, version,
 *      timestamp, status, compact, with the literal status value UP.
 *   C  JSON escaping. The JDK ships no JSON serializer, so User assembles the
 *      document by hand; the escape helper is therefore load-bearing and is
 *      tested directly, including the characters it deliberately leaves alone.
 *   D  Path normalisation, which is the routing decision.
 *   E  Configuration precedence: environment over file over built-in default.
 *      The environment layer is proven in a child JVM whose environment this
 *      harness sets itself, so that layer is covered unconditionally.
 *   F  Live routing over a real socket, including the negative paths.
 *   G  Entry-point dispatch: --serve and --probe driven through the real main
 *      method of a child JVM, which is the only way to prove the dispatcher
 *      rather than the methods it dispatches to.
 *   H  Transport behaviour over a raw socket: the contract asserted byte-for-byte
 *      rather than through a client that reframes it, the connection and body
 *      semantics around it, and the endpoint's answer to hostile request bytes -
 *      none of which a conforming HTTP client can be persuaded to produce.
 *
 * WHY A CHILD JVM, FOR SECTIONS A, E AND G
 * ----------------------------------------
 * Three things can only be observed from outside the process under test, and all
 * three are contractual here.
 *
 * An EXIT STATUS is one. Calling User.main in this JVM and capturing the streams
 * looks equivalent and is not: if the default path ever regressed to call
 * System.exit(0), that call would terminate THIS process from inside the
 * assertion. No summary line would be printed, no check count would be reported,
 * and - because the status would be 0 - the harness would look like it passed.
 * That is the worst failure mode a test can have, and it is invisible from the
 * inside by construction. A child JVM makes the exit status an observation
 * instead of an assumption, and it makes System.exit(0) fail this section rather
 * than silently satisfy it.
 *
 * An ENVIRONMENT is the second. A JVM's environment is fixed at launch and this
 * harness will not use reflection to forge one, so the environment layer of the
 * precedence chain cannot be exercised in process at all. A child can: its
 * environment is an argument. Section E therefore starts a child with a
 * properties file saying one thing and environment variables saying another, and
 * reads the served payload to see which won. The child's environment is built
 * EMPTY and populated explicitly - not even PATH is inherited - so the check
 * neither depends on nor discloses anything about the environment this harness
 * happens to run in, and it can never be skipped for want of a witness variable.
 *
 * A PROCESS LIFECYCLE is the third. "The default mode starts no listener" is a
 * claim about the whole process: a child that bound a socket would not exit, so
 * observing prompt termination proves it more completely than counting threads
 * from the inside ever could.
 *
 * The child is launched with the JDK's multi-file source launcher when User.java
 * can be located, and from compiled classes otherwise, so both documented
 * invocations work. If NEITHER can be located the harness records a counted
 * FAILURE rather than skipping: a check that quietly does not run is the same
 * false green this section exists to eliminate.
 *
 * TWO RULES THIS HARNESS IMPOSES ON ITSELF
 * ----------------------------------------
 *   1. The timestamp is asserted by FORMAT and never by VALUE. It is the only
 *      non-deterministic field in the payload, and an assertion on its value
 *      would make this harness fail for a reason unrelated to correctness.
 *   2. Nothing here mutates the environment of THIS process, and no reflection
 *      is used to try. Where an environment override is needed it is passed to a
 *      child process, which is the supported way to set one.
 *
 * A NOTE ON ENVIRONMENT VALUES AND DISCLOSURE
 * -------------------------------------------
 * A process environment routinely carries credentials, so the variables this
 * harness reads are named explicitly and never discovered by scanning: a scan
 * ordered by name could just as easily select an API key as a witness. Only two
 * categories are read. The application's own settings - APP_NAME, APP_VERSION,
 * HEALTH_PATH, APP_HOST, PORT and JAVA_PORT - are by definition not secrets,
 * since the first two are published in the health response itself, so those may
 * appear in a diagnostic. PATH is read once, as an opportunistic in-process
 * precedence witness, and compared only through the boolean check form, so a
 * failure reports the name of the check and never the value; the precedence proof
 * does not depend on it, because section E's child JVM supplies its own variables.
 * No environment variable of this process is written, and no reflection is used
 * to try. Every child environment is built EMPTY and populated explicitly, so
 * nothing this harness runs can inherit a credential from its own environment.
 *
 * EXPECTED OUTPUT THAT IS NOT A FAILURE
 * -------------------------------------
 * Four sections deliberately drive User down a fail-closed path, and User reports
 * each on stderr by design:
 *   [User] probe could not reach http://...            (sections F and G)
 *   [User] probe rejected http://...: status 404       (section G)
 *   [User] refusing to start: invalid port value: ...  (section G)
 *   [User] could not bind ...: java.net.BindException  (section G)
 * The first is announced on stdout immediately before it is provoked so that a
 * reader of a CI log does not mistake an expected diagnostic for a real fault.
 * The remaining three are produced by CHILD processes, whose streams this harness
 * captures rather than passes through, so they never reach the log at all - they
 * are asserted on instead.
 *
 * Section E drives the other fail-closed path - an unusable port value - but that
 * one is refused by an exception rather than reported on a stream, so it produces
 * no stderr line to announce. See checkRejects.
 */
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
        } catch (RuntimeException unexpected) {
            // Defensive: runSection already contains every section, so reaching
            // this point means the harness itself misbehaved. It is still turned
            // into a counted failure rather than a bare stack trace, because an
            // exit status is the only thing the CI gate can act on.
            checksFailed++;
            System.err.println("FAIL: harness aborted unexpectedly: " + unexpected);
        }
        System.out.println(SEPARATOR);
        System.out.println("UserTest summary: " + checksExecuted + " checks executed, "
                + checksFailed + " failed");
        System.out.println("RESULT: " + (checksFailed == 0 ? "PASS" : "FAIL"));
        System.exit(checksFailed == 0 ? EXIT_SUCCESS : EXIT_FAILURE);
    }

    // -------------------------------------------------------------------------
    // The frozen expectations
    //
    // User keeps its configuration keys, environment names and built-in defaults
    // private, which is correct: they are its implementation detail. This
    // harness therefore restates them as its own literals. That is deliberate
    // rather than duplication for its own sake - a test that imported the
    // constants it is checking would assert only that a value equals itself.
    // -------------------------------------------------------------------------

    /** Standard-output bytes the original program produced, and must still produce. */
    private static final String LEGACY_STDOUT_TEXT = "Test";

    /**
     * Byte length of the preserved default output, {@code Test} plus one newline.
     *
     * <p>Five is the value the backward-compatibility gate hashes. It assumes a
     * single-byte line separator, which every target of this project uses: the
     * CI runner is Linux and all three container images are Linux. A platform
     * with a two-byte separator would legitimately fail this check, because it
     * would also break the byte-for-byte output contract the gate enforces.
     */
    private static final int LEGACY_STDOUT_BYTE_LENGTH = 5;

    /** The one and only value the endpoint reports for a passing status. */
    private static final String STATUS_UP = "UP";

    /** The exact fragment a healthy body must contain. */
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

    /** Highest legal port. */
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

    /** The reference document, byte for byte. */
    private static final String REFERENCE_BODY =
            "{\"name\":\"only_parent_parent_repo_10_LOC\",\"version\":\"1.1.0\""
            + ",\"timestamp\":\"2026-07-28T13:47:08Z\",\"status\":\"UP\"}";

    // -------------------------------------------------------------------------
    // Patterns
    // -------------------------------------------------------------------------

    /** Three-part dotted numeric version. */
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

    // -------------------------------------------------------------------------
    // Harness mechanics
    // -------------------------------------------------------------------------

    /** Exit status meaning every check passed. */
    private static final int EXIT_SUCCESS = 0;

    /** Exit status meaning at least one check failed, or the harness aborted. */
    private static final int EXIT_FAILURE = 1;

    /** Visual divider around the summary. */
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

    /**
     * The variable read as a precedence witness. PATH is present in every
     * environment this project runs in - a shell, a CI runner and all three
     * container images - it is never numeric, and it is not a secret. It is
     * named explicitly rather than discovered by scanning the environment,
     * because a scan could just as easily select a credential.
     */
    private static final String WITNESS_ENV_NAME = "PATH";

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

    /** Total number of checks executed; the CI gate requires this to exceed zero. */
    private static int checksExecuted;

    /** Number of checks that failed; the process exit status is derived from it. */
    private static int checksFailed;

    /** A path that is deliberately not the configured route. */
    private static final String UNKNOWN_PATH = "/__usertest_unknown_route__";

    /** The root path, which is what an empty or null request path normalises to. */
    private static final String ROOT_PATH = "/";

    /** Seconds to wait for a stopped server's dispatcher thread to disappear. */
    private static final int DISPATCHER_SHUTDOWN_WAIT_SECONDS = 5;

    /** Poll interval while waiting for that thread, in milliseconds. */
    private static final long DISPATCHER_POLL_MILLIS = 20L;

    // -------------------------------------------------------------------------
    // Child-process expectations - sections A, E and G
    // -------------------------------------------------------------------------

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

    /** Budget for a serving child to exit once it has been asked to, in seconds. */
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

    /** The application's source file name, for the source-launcher invocation. */
    private static final String APPLICATION_SOURCE_FILE = "User.java";

    /** The application's compiled class file name, for the classpath invocation. */
    private static final String APPLICATION_CLASS_FILE = "User.class";

    /** The application's class name, as passed to the launcher. */
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

    /** The flag that selects serve mode. */
    private static final String FLAG_SERVE = "--serve";

    /** The flag that selects probe mode. */
    private static final String FLAG_PROBE = "--probe";

    /** An argument the dispatcher must not recognise, so it selects the default. */
    private static final String UNRECOGNISED_FLAG = "--usertest-not-a-mode";

    /** A port value no configuration may accept, used to prove fail-closed starts. */
    private static final String UNUSABLE_PORT_VALUE = "not-a-port";

    /** A numerically valid but out-of-range port, refused for a different reason. */
    private static final String OUT_OF_RANGE_PORT_VALUE = "70000";

    // -------------------------------------------------------------------------
    // Transport expectations - section H
    //
    // The exact header sets, the runtime's one observable ceiling, and the sizes
    // used to probe it. These are restated here rather than imported from User for
    // the same reason every other expectation is: a test that read the constant it
    // is checking would assert only that a value equals itself.
    //
    // What is NOT here is as deliberate as what is. There is no 400, 414, 431 or
    // 505 expectation, and no fixed body for one, because this endpoint produces no
    // such response and the frozen contract enumerates none. Section H's own
    // comment records the reasoning in full.
    // -------------------------------------------------------------------------

    private static final int HTTP_CONTINUE = 100;

    /** The media type every response this endpoint writes declares. */
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
     * Exactly the field names a 200 or a 404 carries, lower-cased.
     *
     * <p>Asserted by set EQUALITY rather than by presence, which is what makes it a
     * disclosure check as well as a contract check: it proves in one assertion that
     * these four are present AND that a {@code Server} banner, a {@code Keep-Alive}
     * advertising the idle timeout, and anything else are not. Presence checks can
     * only ever prove the first half, and the half they miss is the half that leaks.
     *
     * <p>{@code date} is one of the four because {@code HttpServer} writes it and
     * application code cannot stop it - setting the field to a sentinel has it
     * overwritten, and removing it after the header block is sent still sends it.
     * RFC 9110 section 6.6.1 says an origin server SHOULD send {@code Date}, so the
     * field is conformant, and its value discloses nothing about the runtime. It is
     * the one field this implementation carries that app.py and index.js do not, and
     * {@link #checkFrozenHeaders} asserts its FORMAT rather than its value for the
     * same reason the payload timestamp is asserted by format: it is a clock
     * reading, and a check that compared it would fail for the wrong reason.
     */
    private static final Set<String> CONTRACT_HEADER_NAMES =
            Set.of("date", "content-type", "cache-control", "content-length");

    /**
     * Exactly the field names a 405 carries: the contract four plus Allow.
     *
     * <p>Every refused method carries this same set, HEAD included. HEAD's response
     * body is empty, as RFC 9110 requires, but its {@code Content-Length} still
     * declares the length the body would have had - which is why the set is shared
     * rather than split, and why {@link User} sets that field explicitly instead of
     * letting the server derive it and then drop it.
     */
    private static final Set<String> REFUSAL_HEADER_NAMES =
            Set.of("date", "content-type", "cache-control", "content-length", "allow");

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
     * The only request ceiling the runtime enforces observably: more header fields
     * than this and the connection is dropped without a response.
     *
     * <p>The server's own limit, not this endpoint's, and established by execution:
     * at this many fields a request is served normally, and at one more the
     * connection closes with no bytes written and the handler is never entered. It
     * is asserted because it is a denial-of-service control that is reachable from
     * the network, and a control nobody tests is a control nobody has.
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

    /** Field count for the large-but-legal header block control. */
    private static final int LARGE_BLOCK_FIELD_COUNT = 20;

    /** Per-field size for the large-but-legal header block control. */
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

    /** Read budget for one raw socket exchange, in milliseconds. */
    private static final int RAW_READ_TIMEOUT_MILLIS = 10_000;

    /** How long to wait for a peer to close before concluding it will not, in ms. */
    private static final int RAW_CLOSE_TIMEOUT_MILLIS = 2_000;

    /**
     * Socket timeout for one read attempt, in milliseconds.
     *
     * <p>Short on purpose. It is not a budget but a heartbeat: it returns control to
     * each read loop often enough that the loop can check its own deadline, so a
     * quiet peer produces a diagnosable failure rather than a blocked thread.
     */
    private static final int RAW_POLL_TIMEOUT_MILLIS = 250;

    /** Read buffer size for one raw socket read. */
    private static final int RAW_BUFFER_BYTES = 8192;

    /** The end of a header block, and the separator before any body. */
    private static final String HEAD_TERMINATOR = "\r\n\r\n";

    /** Line terminator for every raw request this harness writes. */
    private static final String CRLF = "\r\n";

    // -------------------------------------------------------------------------
    // The assertion engine
    //
    // Three primitives, one counter pair, and a section runner. Every failure
    // goes to standard error naming the check that failed, and every failure is
    // counted, because the counter pair is the only thing the exit status - and
    // therefore the CI gate - is derived from.
    // -------------------------------------------------------------------------

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
     *
     * @param label   human-readable section name, used in every message
     * @param section the checks to run
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
     *
     * @param name      description of what is being asserted
     * @param condition the condition that must hold
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
     * @param name     description of what is being asserted
     * @param expected the required value; {@code null} is compared safely
     * @param actual   the observed value
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
     * log and all break the hash the compatibility gate compares.
     *
     * @param name     description of what is being asserted
     * @param expected the required bytes
     * @param actual   the observed bytes
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
     *
     * @param name     description of what is being asserted
     * @param expected the exact set required
     * @param actual   the observed set
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
     *
     * @param work the action to run
     * @return everything the action wrote to stderr, decoded as UTF-8
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
     *
     * @param name     description of what is being asserted
     * @param expected the exact message the refusal must carry
     * @param call     the action expected to be refused
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
     *
     * @param name      description of what is being asserted
     * @param offending the text that must appear in the rejection message
     * @param call      the call that must reject its input
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
     * @param name    description of what is being asserted
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
     * Prints an uncounted informational line.
     *
     * @param message the note to print
     */
    private static void note(String message) {
        System.out.println("NOTE: " + message);
    }

    /**
     * Announces a diagnostic that the next check will deliberately provoke.
     *
     * <p>Two checks drive User down a fail-closed path on purpose, and User
     * reports those on standard error by design. Announcing them first is what
     * stops a reader of a CI log from mistaking correct behaviour for a fault.
     *
     * @param expected the diagnostic that is about to appear on standard error
     */
    private static void announceExpectedDiagnostic(String expected) {
        System.out.println("NOTE: an expected diagnostic follows on standard error: " + expected);
    }

    // -------------------------------------------------------------------------
    // Section A - preserved legacy behaviour
    //
    // The backward-compatibility requirement outranks the new feature: the
    // original program printed "Test" and exited 0, and it still must. These
    // checks are the mechanical form of that promise.
    // -------------------------------------------------------------------------

    /**
     * Asserts that the default invocation is unchanged and side-effect free,
     * observed from outside the process that performs it.
     *
     * <p>The isolation is the point. Capturing streams around an in-process
     * {@code User.main} call reads the same bytes but cannot see the exit status,
     * and the exit status is half the contract: a default path that regressed to
     * {@code System.exit(0)} would kill this JVM mid-assertion, print no summary,
     * and still hand the shell a zero - a passing result for a run that asserted
     * nothing. A child cannot do that to us. Its status is data.
     *
     * <p>Both streams are compared as RAW BYTES rather than as decoded strings.
     * The gate this mirrors hashes bytes, so bytes are what must be asserted: a
     * text comparison would silently accept a changed encoding or a normalised
     * line ending, both of which would break the hash while passing the test.
     *
     * <p>Prompt termination carries the listener assertion. A dual-mode program
     * that accidentally bound a socket in its default mode would print exactly the
     * right line and then never exit, because the acceptor thread is not a daemon.
     * Observing the process end is therefore a stronger proof that the default
     * path is inert than counting threads from the inside could be, and it needs
     * no knowledge of how the listener is implemented.
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

    // -------------------------------------------------------------------------
    // Child-process machinery, shared by sections A, E and G
    //
    // Small, and deliberately so, but three details in it are load-bearing and
    // each is there because omitting it produces a harness that hangs or lies:
    //
    //   Both output streams are drained on their own threads. A child writing
    //   more than a pipe buffer to a stream nobody is reading BLOCKS, and a
    //   server child writes to stderr. Draining only stdout would wedge the very
    //   process under test, and the failure would look like a timeout in the
    //   application rather than a defect in the harness.
    //
    //   Every wait is bounded and every child is destroyed on every path. An
    //   orphaned server would hold its port and outlive the run.
    //
    //   Every child environment starts EMPTY. Nothing is inherited - not even
    //   PATH - so a child sees exactly the variables a check names, which is what
    //   makes the environment layer of the precedence chain provable rather than
    //   merely probable, and what keeps this harness from leaking its own
    //   environment into a process it starts.
    // -------------------------------------------------------------------------

    /**
     * What one completed child invocation produced.
     *
     * @param status         exit status, or {@code -1} when the child had to be killed
     * @param standardOutput every byte the child wrote to standard output
     * @param standardError  every byte the child wrote to standard error
     * @param exited         whether the child ended on its own inside its budget
     */
    private record ChildOutcome(int status, byte[] standardOutput, byte[] standardError,
            boolean exited) {

        /** @return standard error decoded for a diagnostic message */
        String errorText() {
            return new String(standardError, StandardCharsets.UTF_8);
        }
    }

    /**
     * The {@code java} launcher of the JVM running this harness.
     *
     * <p>Derived from {@code java.home} rather than found on {@code PATH}, which
     * guarantees the child runs on the same runtime as the parent - so a result
     * can never be explained by two different Java versions - and lets the child
     * environment be built empty, since an empty environment has no PATH to search.
     *
     * @return absolute path to the launcher
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
     *
     * @return launcher and target, ready for arguments to be appended
     * @throws IllegalStateException if neither the source nor the class can be found
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
     * @param fileName the file to find
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
     * <p>This exists for one purpose: to place a properties file BESIDE the code
     * the child will run. The application resolves its configuration relative to its
     * own code source first and the working directory second - the same rule app.py
     * applies to {@code __file__} and index.js to {@code __dirname} - and there is
     * deliberately no environment variable that names an arbitrary properties file
     * in any of the three implementations. Copying the artifact is therefore the
     * only way to give a child a configuration file of the harness's choosing, and
     * it is a truer test than a variable would have been: it exercises the real
     * resolution rule rather than an override that bypasses it.
     *
     * <p>The working directory is deliberately NOT changed, so the repository's own
     * properties file remains the second candidate. That makes the check
     * discriminating rather than merely arranged: if code-source resolution ever
     * regressed, the child would serve the repository's values, which differ from
     * every value written into the temporary file.
     *
     * @param directory an existing directory to copy the application into
     * @return launcher and target for the copy, ready for arguments to be appended
     * @throws IOException           if the copy cannot be made
     * @throws IllegalStateException if neither the source nor the class can be found
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
     * @param args        arguments to pass after the launch target
     * @return the started process, with its standard input already closed
     * @throws IOException if the child cannot be started
     */
    private static Process startApplication(Map<String, String> environment, String... args)
            throws IOException {
        return startApplication(applicationLaunchPrefix(), environment, args);
    }

    /**
     * Starts a child JVM from an explicit launch command, with an explicit
     * environment.
     *
     * @param prefix      launcher and target, as built by
     *                    {@link #applicationLaunchPrefix} or
     *                    {@link #isolatedLaunchPrefix}
     * @param environment the child's COMPLETE environment; nothing is inherited
     * @param args        arguments to pass after the launch target
     * @return the started process, with its standard input already closed
     * @throws IOException if the child cannot be started
     */
    private static Process startApplication(List<String> prefix,
            Map<String, String> environment, String... args) throws IOException {
        List<String> command = new ArrayList<>(prefix);
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
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
     * @param args        arguments to pass after the launch target
     * @param environment the child's COMPLETE environment
     * @return the exit status, both streams as raw bytes, and whether it exited
     */
    private static ChildOutcome runApplication(List<String> args,
            Map<String, String> environment) {
        Process child = null;
        try {
            child = startApplication(environment, args.toArray(new String[0]));
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
     * @param source the child stream to read
     * @param sink   where to put the bytes
     * @param name   thread name, so a stack dump is readable
     * @return the started thread
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
         *
         * @return {@code true} once a port has been announced
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

        /** @return every diagnostic line the child has written so far */
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
     * @return the running child, already listening
     */
    private static ServeChild startServeChild(Map<String, String> overrides) {
        return startServeChild(applicationLaunchPrefix(), overrides);
    }

    /**
     * Starts a serving child from an explicit launch command, on an OS-chosen port.
     *
     * @param prefix    launcher and target, which may point at an isolated copy of
     *                  the application so that a chosen properties file sits beside it
     * @param overrides environment variables for the child, added to a bind on
     *                  loopback and an ephemeral port
     * @return the running child, already listening
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

    // -------------------------------------------------------------------------
    // Section B - the frozen response contract
    //
    // Four keys, in the order name, version, timestamp, status, compact, with the
    // literal status value UP. Monitoring tools, deployment scripts and humans
    // all come to depend on this shape, so it is pinned here rather than merely
    // described in a comment somewhere.
    // -------------------------------------------------------------------------

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
     *
     * @param document the rendered document
     * @return {@code true} when name precedes version precedes timestamp
     *         precedes status, and all four are present
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
     * @param document the compact JSON document to read
     * @param key      the member name to extract
     * @return the raw field value, or {@code null} when the member is absent
     */
    private static String jsonField(String document, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\":\"([^\"]*)\"")
                .matcher(document);
        return matcher.find() ? matcher.group(1) : null;
    }

    // -------------------------------------------------------------------------
    // Section C - JSON escaping
    //
    // The JDK has no JSON serializer, so User assembles the document by hand.
    // That makes its escape helper load-bearing in a way that its Python and
    // JavaScript siblings' json.dumps and JSON.stringify calls are not, which is
    // why it is tested directly - including the two categories of character it
    // deliberately leaves alone, because over-escaping would break byte parity
    // with those siblings just as surely as under-escaping would break the JSON.
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Section D - path normalisation
    //
    // Normalisation IS the routing decision, so these cases are the route's
    // specification. They are expressed relative to the effective route rather
    // than to the literal /health, so that the section keeps testing the real
    // behaviour when the route is reconfigured instead of quietly testing a path
    // the server no longer answers.
    // -------------------------------------------------------------------------

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
    }

    // -------------------------------------------------------------------------
    // Section E - configuration precedence
    //
    // Environment variable beats properties file beats built-in default, and for
    // the listener port the universal PORT variable beats even the
    // language-specific JAVA_PORT.
    //
    // A JVM's environment is fixed at launch and this harness will not use
    // reflection to forge one, so the environment layer is exercised the honest
    // way: User.resolve takes the variable NAME as a parameter, so passing a name
    // that is genuinely set proves that the environment layer wins, and passing a
    // name that cannot be set proves that it is absent. The end-to-end behaviour
    // with real overrides is covered by scripts/verify-health.sh and the CI jobs,
    // which export real variables before starting a server.
    // -------------------------------------------------------------------------

    /** Properties key used only to hold a deliberately malformed port value. */
    private static final String KEY_MALFORMED_PORT = "malformed.port";

    /** Properties key used only to hold a deliberately out-of-range port value. */
    private static final String KEY_OUT_OF_RANGE_PORT = "out.of.range.port";

    /**
     * Asserts the precedence chain, the accessors, and the fail-closed port rules.
     *
     * @throws IOException          if the temporary properties file cannot be written
     * @throws InterruptedException if a child-process request is interrupted
     */
    private static void verifyConfigurationPrecedence()
            throws IOException, InterruptedException {
        Properties empty = new Properties();
        check("the file-backed configuration is never null", User.configProperties() != null);

        // --- The accessors, against an independently derived expectation --------
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

        // --- Built-in defaults, with both other layers provably absent ----------
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

        // --- The shipped file agrees with the built-in defaults ----------------
        verifyShippedConfigurationAgrees();

        // --- File over default, and the port rules, through a real file ---------
        verifyFileBackedPrecedence();
    }

    /**
     * Asserts that the shipped configuration file and the built-in defaults do
     * not disagree, so that the single source of truth really is single.
     *
     * <p>The file is resolved relative to the working directory, so a run started
     * from elsewhere will not find it. That is not a failure: the application is
     * specified to fall back to its built-in defaults in exactly that case, and
     * the accessor checks above already prove the fallback. The distinction is
     * reported as a note so that a reader of the log knows which path was taken.
     */
    private static void verifyShippedConfigurationAgrees() {
        Properties shipped = User.configProperties();
        if (shipped.getProperty(KEY_APP_NAME) == null) {
            note("no configuration file was found from this working directory, so the"
                    + " built-in defaults are in force; the accessor checks above cover them");
            return;
        }
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
     *
     * @throws IOException          if the temporary file cannot be created or written
     * @throws InterruptedException if a child-process request is interrupted
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
     *
     * @param location the file to read
     * @return the parsed properties
     * @throws IOException if the file cannot be read
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
     * <p>This is the check that makes the environment layer unconditionally
     * covered. A properties file is written saying one thing, a child is started
     * with environment variables saying another, and the SERVED PAYLOAD decides
     * which won - so the assertion is about the application's real configuration
     * path, not about a resolver method called in isolation.
     *
     * <p>Three separate claims are settled here, and each is arranged so that only
     * one outcome is possible if precedence is correct.
     *
     * <ul>
     *   <li>Name and version come from the environment, not the file, and the
     *       file's values appear nowhere in the response.</li>
     *   <li>The route comes from the environment: the environment's path answers
     *       200 and the FILE's path answers 404. Asserting both directions is what
     *       distinguishes "the environment won" from "both paths happen to work".</li>
     *   <li>The universal PORT variable outranks the language-specific one. This is
     *       proven with an UNBINDABLE language port: if {@code JAVA_PORT} were
     *       consulted first the child would refuse to start, so the fact that it
     *       binds and announces a port is the proof. No port has to be reserved and
     *       nothing can race.</li>
     * </ul>
     *
     * <p>A second child, given the file but NO overriding variables, closes the
     * chain from the other end by serving the file's values - which proves the
     * file layer is genuinely being read, and therefore that the first child's
     * result is precedence rather than the file simply being ignored.
     *
     * <p>The file is delivered by copying the application into a temporary
     * directory and writing the properties file beside the copy, because that is
     * how the file layer is actually reached: all three implementations resolve
     * their configuration relative to their own source file and NONE of them
     * accepts an environment variable naming an arbitrary properties file. See
     * {@link #isolatedLaunchPrefix}. The child's working directory is left pointing
     * at the repository, so the repository's own properties file is still the
     * second candidate - which means a regression in code-source resolution would
     * serve the repository's values and fail every assertion below rather than
     * quietly passing on a different file.
     *
     * @throws IOException          if the temporary directory cannot be prepared
     * @throws InterruptedException if a request is interrupted
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
     * Removes a directory and everything in it, reporting nothing.
     *
     * <p>Used only to clean up a temporary enclosure this harness created itself, so
     * a failure to remove a file is not a test failure: the next run creates a fresh
     * directory with a fresh name and the operating system reclaims the rest. The
     * assertion that the enclosure is gone is made by the caller, which is where a
     * genuine leak would matter.
     *
     * @param root the directory to remove
     * @throws IOException if the directory cannot be walked at all
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
     *
     * @param port the port the child announced
     * @param path the request path
     * @return the complete response
     * @throws IOException          if the request fails
     * @throws InterruptedException if the request is interrupted
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
     * Asserts that the environment layer outranks the file layer, without
     * mutating the environment of this process and without reflection.
     *
     * <p>Two independent proofs, and the split is the whole design.
     *
     * <p>The FIRST is unconditional and end to end: {@link #verifyEnvironmentBeatsFileInChild}
     * starts a child JVM with a properties file saying one thing and environment
     * variables saying another, and reads the served payload to see which won.
     * Because the harness supplies the child's environment itself, this proof can
     * never be skipped for want of a variable, and it exercises the real
     * {@code loadConfig} path rather than a resolver call.
     *
     * <p>The SECOND is opportunistic and unit-level. {@code User.resolve} takes the
     * variable NAME as a parameter, so any name that happens to be set in this
     * process proves the precedence directly and instantly. PATH is used when it is
     * available - named explicitly, never discovered by scanning, because a scan
     * could just as easily select a credential - and compared only through the
     * boolean check form so a failure reports the name of the check and never a
     * value. When PATH is absent this proof is simply not available, and that is
     * now harmless rather than a gap, because the first proof has already covered
     * the layer. Its absence is recorded as a note and NOT as a skipped check.
     *
     * @param fromFile a configuration that supplies both a name and a port, so
     *                 that the environment layer has something to outrank
     * @param filePort the port that configuration supplies
     * @throws IOException          if a temporary file or a child cannot be handled
     * @throws InterruptedException if a request or a child wait is interrupted
     */
    private static void verifyEnvironmentBeatsFile(Properties fromFile, int filePort)
            throws IOException, InterruptedException {
        // Unconditional, and therefore the proof this section relies on.
        verifyEnvironmentBeatsFileInChild();

        String witness = System.getenv(WITNESS_ENV_NAME);
        if (witness == null || witness.isEmpty()) {
            note(WITNESS_ENV_NAME + " is not set in this process, so the in-process"
                    + " resolver proof is unavailable; the environment layer is already"
                    + " proven end to end by the child-JVM check above, which supplies"
                    + " its own variables and therefore cannot be skipped");
            return;
        }
        check("an environment value overrides a file value",
                witness.equals(User.resolve(fromFile, KEY_APP_NAME,
                        WITNESS_ENV_NAME, DEFAULT_APP_NAME)));

        // The universal PORT variable outranks the language-specific key AND the
        // file. Whether the witness parses as a port decides only which correct
        // outcome to expect, so the check runs either way rather than being
        // silently skipped: a numeric witness must win with its own value, and a
        // non-numeric one must be REFUSED, which proves the file value was
        // outranked just as conclusively.
        boolean universalOutranksFile;
        if (isParsablePort(witness)) {
            universalOutranksFile = Integer.parseInt(witness.trim())
                    == User.resolvePort(fromFile, KEY_JAVA_PORT, ABSENT_ENV_PRIMARY,
                            WITNESS_ENV_NAME, DEFAULT_JAVA_PORT);
        } else {
            // The same call resolves cleanly to the file port when no universal name
            // is supplied, so naming the witness is the ONLY difference between the
            // two calls below - which makes the refusal proof that the universal
            // variable was consulted first and outranked the file.
            //
            // The outcome is reduced to a boolean and deliberately NOT reported
            // through checkRejects: that form prints the offending value, and here
            // the offending value is the contents of the witness variable.
            boolean fileAloneResolves = filePort == User.resolvePort(fromFile, KEY_JAVA_PORT,
                    ABSENT_ENV_PRIMARY, ABSENT_ENV_SECONDARY, DEFAULT_JAVA_PORT);
            boolean refusedWithWitness;
            try {
                User.resolvePort(fromFile, KEY_JAVA_PORT, ABSENT_ENV_PRIMARY,
                        WITNESS_ENV_NAME, DEFAULT_JAVA_PORT);
                refusedWithWitness = false;
            } catch (IllegalArgumentException rejectedWitnessValue) {
                refusedWithWitness = true;
            }
            universalOutranksFile = fileAloneResolves && refusedWithWitness;
        }
        check("the universal port override outranks a file port", universalOutranksFile);
    }

    // -------------------------------------------------------------------------
    // Expectation helpers
    //
    // These re-derive the precedence order independently of User.resolve, which
    // is the whole point: an expectation computed by calling the method under
    // test would assert only that a value equals itself.
    // -------------------------------------------------------------------------

    /**
     * Re-derives one effective value from the documented precedence order.
     *
     * <p>Reads the environment but never writes it.
     *
     * @param environmentName the variable that outranks the file
     * @param propertiesKey   the key read from the file
     * @param builtInDefault  the value used when neither source supplies one
     * @return the value the application must report
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

    /** @return the application name the payload must report */
    private static String expectedAppName() {
        return expectedEffective(ENV_APP_NAME, KEY_APP_NAME, DEFAULT_APP_NAME);
    }

    /** @return the application version the payload must report */
    private static String expectedAppVersion() {
        return expectedEffective(ENV_APP_VERSION, KEY_APP_VERSION, DEFAULT_APP_VERSION);
    }

    /** @return the bind address the listener must use */
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
     *
     * @return the route the endpoint must answer
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
     * @return {@code true} when it parses to a number inside the legal port range
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

    // -------------------------------------------------------------------------
    // Section F - live routing over a real socket
    //
    // Unit checks prove the pieces; only a real request proves the wiring. The
    // server is bound on loopback with port 0, so the OS chooses a free port: the
    // documented default 8002 is never bound here, which is what lets this harness
    // run twice at once, run beside a real server, and run on a busy CI machine
    // without a bind collision. The chosen port is read back from the server
    // rather than assumed.
    // -------------------------------------------------------------------------

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
     *
     * @throws IOException          if the server cannot be bound or a request fails
     * @throws InterruptedException if a request is interrupted while in flight
     */
    private static void verifyLiveRouting() throws IOException, InterruptedException {
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
            // the same five fields each time. Sampling one verb would leave the
            // rest free to answer differently.
            for (String method : new String[] {"PUT", "DELETE", "PATCH", "OPTIONS"}) {
                HttpResponse<String> refused = send(client, method, port, route);
                checkEquals(method + " the route answers 405",
                        HTTP_METHOD_NOT_ALLOWED, refused.statusCode());
                checkEquals("the 405 answer to " + method + " names GET as allowed",
                        Optional.of("GET"), refused.headers().firstValue("allow"));
                checkSetEquals("the 405 answer to " + method + " carries exactly five fields",
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
            // RFC 9110 section 9.3.2 expectation and what keeps this
            // implementation's HEAD block equal to app.py's and index.js's.
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
     *
     * @param client the client to use
     * @param port   the ephemeral port the test server chose
     * @param path   request path, including any query string
     * @return the complete response with its body as a string
     * @throws IOException          if the request fails
     * @throws InterruptedException if the request is interrupted
     */
    private static HttpResponse<String> get(HttpClient client, int port, String path)
            throws IOException, InterruptedException {
        return send(client, "GET", port, path);
    }

    /**
     * Issues a request with an explicit method and an empty body.
     *
     * @param client the client to use
     * @param method the HTTP method token, which is case-sensitive per RFC 9110
     * @param port   the ephemeral port the test server chose
     * @param path   request path, including any query string
     * @return the complete response with its body as a string
     * @throws IOException          if the request fails
     * @throws InterruptedException if the request is interrupted
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
     *
     * @param response the response to inspect
     * @param name     the field name, in any casing
     * @param fragment the substring the value must contain
     * @return {@code true} when the header is present and contains the fragment
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
     *
     * @param response the response to inspect
     * @return the field names, lower-cased and sorted
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
     * <p>The set equality is the assertion that does the most work. Checking that
     * required fields are PRESENT can only ever prove half the contract, and the
     * half it cannot reach is the half that leaks: a {@code Server} banner naming
     * the runtime and its version, a {@code Keep-Alive} advertising the idle
     * timeout, a {@code Via} or an {@code X-} field added by something in the
     * middle. Equality proves the required fields arrived AND that nothing else
     * did, in one comparison that cannot be satisfied by accident.
     *
     * <p>{@code Server} is then named individually as well. That is redundant
     * against the set equality and kept deliberately: it is the specific disclosure
     * requirement, so a reader of the log sees it asserted by name, and if the
     * expected set is ever widened the named check still holds the line.
     *
     * <p>{@code Date} is asserted by FORMAT rather than by absence. The server
     * writes the field itself and application code cannot suppress it, RFC 9110
     * section 6.6.1 says an origin server SHOULD send it, and its value is a clock
     * reading - so the only assertion that can be both meaningful and stable is
     * that the field is present and well formed. Asserting a value would make this
     * check fail for the wrong reason, exactly as an asserted payload timestamp
     * would.
     *
     * <p>All three cache directives are required, not just one. They do different
     * jobs - a stale health answer is worse than no answer, and a response merely
     * marked {@code no-cache} may still be written to a shared store - so asserting
     * only the presence of the header, or only one directive, would let a real
     * regression through.
     *
     * @param response        the response to inspect
     * @param expectedNames   exactly the field names the response must carry
     * @param declaredLength  the byte length {@code Content-Length} must declare,
     *                        which for a HEAD refusal is the length the body WOULD
     *                        have had rather than the zero bytes actually sent
     * @param label           prefix for every message, naming the case under test
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
     *
     * @return {@code true} once no dispatcher thread remains
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

    // -------------------------------------------------------------------------
    // Section G - entry-point dispatch
    //
    // Every check above this point calls a method of User directly, which proves
    // the methods and says nothing about the DISPATCHER that chooses between
    // them. That gap matters more than it sounds: the argument vector had never
    // been read in this program's history before this feature, the flag handling
    // is new code, and the entire backward-compatibility guarantee rests on which
    // branch an empty argument vector selects. A direct call to startServer cannot
    // tell you that --serve reaches it, and a direct call to probe cannot tell you
    // that --probe exits with the value probe returned.
    //
    // So this section drives the real main method of a real process and observes
    // only what a shell or a container runtime would observe: the exit status, the
    // two streams, and whether a port is listening.
    // -------------------------------------------------------------------------

    /**
     * Asserts that {@code --serve} and {@code --probe} reach their modes, that the
     * default branch survives an unrecognised flag, and that both new modes fail
     * closed.
     *
     * @throws IOException          if a child cannot be started or a request fails
     * @throws InterruptedException if a wait is interrupted
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
     *
     * @throws IOException          if a request fails
     * @throws InterruptedException if a request is interrupted
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
     *
     * @throws IOException if the port used to provoke a bind conflict cannot be held
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
     * <p>This is the mode a container health check runs, so the exit status IS the
     * contract - and it has to be observed from outside the process to be observed
     * at all. Every negative case matters as much as the positive one: a probe that
     * cannot fail is indistinguishable from no health check, and it would report a
     * dead application as healthy for as long as the deployment lived.
     *
     * @throws IOException          if a child cannot be started
     * @throws InterruptedException if a wait is interrupted
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
        // endpoint. Without this the container health check could never pass.
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
        check("the unrunnable probe names the offending port value",
                unparseable.errorText().contains(UNUSABLE_PORT_VALUE));
    }

    /**
     * Waits briefly for a port to become bindable again.
     *
     * <p>Polled rather than asserted immediately, because a socket the kernel has
     * only just reclaimed can refuse one bind and accept the next. Polling makes
     * the check mean "the port was released" instead of "the port was released
     * within one scheduling quantum".
     *
     * @param port the port that should now be free
     * @return {@code true} once the port can be bound
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

    // -------------------------------------------------------------------------
    // Section H - transport behaviour over a raw socket
    //
    // java.net.http is the right client for the response CONTRACT and the wrong
    // client for the transport. It frames and validates the request itself, so it
    // cannot be persuaded to send a two-token request line, a target of any length
    // the caller chooses, a header block of any size, an unsupported HTTP major, an
    // HTTP/1.1 request with no Host, a stale CRLF before the request line, or a
    // plain HTTP/1.0 request. It also reframes what comes BACK: a check made
    // through it is a check on a parsed object, not on the bytes on the wire.
    //
    // WHAT THIS SECTION ASSERTS
    //
    //  1. The contract, byte-for-byte. Three answers read off a single connection
    //     prove every Content-Length was accurate, because an inaccurate one leaves
    //     the next read starting mid-stream and the third answer never frames.
    //     Nothing above this point can prove that.
    //  2. The request-body drain, which is code in User and is REQUIRED rather than
    //     tidy: bytes still queued when the exchange closes make the kernel answer
    //     with a reset, so a client that POSTed a body would see "connection reset
    //     by peer" instead of the 405 that was written for it. Reproduced with a
    //     one-mebibyte body, which is served correctly with the drain in place.
    //  3. Availability under hostile bytes. Every malformed shape a client can send
    //     is followed by a well-formed request on a fresh connection, which must
    //     still be served. That is the property that actually matters for a health
    //     endpoint: an endpoint a single bad request can silence is worse than no
    //     endpoint, because the monitoring around it now reports a false outage.
    //  4. Non-disclosure. No response, from this endpoint OR from the runtime
    //     underneath it, may reflect any part of the request back, and none may
    //     carry a Server banner. A rejection that quotes its input is how an error
    //     path becomes an information leak, and the runtime's own error paths are
    //     the ones application code never sees.
    //  5. The one request ceiling the runtime enforces observably, and its
    //     controls. A ceiling that rejected everything large would pass a limit
    //     test and break every real client, so the over-limit case is paired with
    //     large-but-legal requests that must be served.
    //
    // WHAT THIS SECTION DELIBERATELY DOES NOT ASSERT
    //
    // There is no 400, 414, 431 or 505 expectation anywhere below, and no fixed
    // body for one. This endpoint produces exactly three statuses - 200, 404 and
    // 405 - and the frozen contract in the specification enumerates exactly those
    // three. Request parsing belongs to com.sun.net.httpserver, which is the
    // listener the plan mandates, and it makes its own decisions: it tolerates a
    // fourth token on the request line, tolerates a lower-case version keyword,
    // tolerates a version with no minor, requires no Host at HTTP/1.1, imposes no
    // target or header-size ceiling, and answers the shapes it does refuse with an
    // HTML document of its own composition. Asserting a JSON 400 here would be
    // asserting a response no conforming implementation of this contract sends -
    // it would be testing a bespoke parser that no longer exists, and that this
    // endpoint is specified not to have. What IS asserted about those shapes is
    // what the contract genuinely promises: whatever answer comes back reflects
    // nothing from the request, and the endpoint is still serving afterwards.
    //
    // The connection assertions are kept in full. A served 1.1 connection is
    // reused, a client that asks to close is answered and then closed, a lookalike
    // connection option does not retire a good connection, and HTTP/1.0's inverted
    // default is honoured both ways. Getting that backwards is not cosmetic: a
    // health poller that had to reconnect for every poll would pay a handshake it
    // does not need, and a client that kept writing into a socket the server had
    // abandoned would see a reset instead of the answer it was given.
    // -------------------------------------------------------------------------

    /**
     * Asserts the contract over raw bytes, the body drain, availability under
     * hostile input and the connection semantics, over a socket this harness writes
     * bytes to directly.
     *
     * @throws IOException if the test server cannot be bound
     */
    private static void verifyRawTransport() throws IOException {
        String route = User.healthPath();
        User.HealthServer server = User.startServer(TEST_HOST, EPHEMERAL_PORT);
        int port = server.port();
        try {
            verifyContractOverRawBytes(port, route);
            verifyHostileRequestLines(port, route);
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
                    checkRawContractHeaders("the served answer", response,
                            REFERENCE_BODY_BYTE_LENGTH);
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
     *
     * @param label          names the case in every message
     * @param response       the parsed raw response
     * @param declaredLength the byte length {@code Content-Length} must declare
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
     * Asserts that no shape of hostile request line can silence the endpoint or
     * make any response reflect its input.
     *
     * <p>The status a malformed request receives is the RUNTIME's decision, not this
     * endpoint's, and the runtime's decisions vary by shape: a request line with a
     * fourth token is tolerated and routed, one with a space in the target is routed
     * to the truncated target, one with an unparseable target or too few tokens is
     * refused by the server before any handler runs, and one whose method token
     * merely looks odd is routed and refused 405 like any other non-GET. All of
     * those are conformant, none of them is in this endpoint's contract, and so none
     * of them is asserted as a specific status.
     *
     * <p>What IS asserted is what the contract does promise, for every shape without
     * exception. The answer is a well-formed HTTP/1.1 message. Its status is a
     * recognised one rather than something invented. Nothing from the request comes
     * back in the status line, the header block or the body - which is the property
     * that turns a parser error into an information leak when it fails, and which is
     * checked with a target chosen to look like internal deployment detail so that a
     * leak would be unmistakable in the log. And the endpoint is still answering
     * afterwards, on a fresh connection, which is the availability property a health
     * endpoint exists to provide.
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
     *
     * @param port     the port the test server bound
     * @param label    names the case in every message
     * @param request  the raw bytes to send
     * @param telltale a distinctive string planted in the request that must not
     *                 appear anywhere in the response
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
     * Asserts what happens to a request announcing a version other than 1.1.
     *
     * <p>Version negotiation belongs to the listener, and this listener accepts what
     * it is given: an HTTP/2.0, 3.0, 0.9 or 9.9 request line over a 1.1 connection is
     * served, and the answer is a 1.1 message carrying the frozen contract. That is
     * asserted rather than a 505, because a 505 is not a response this endpoint's
     * contract defines and the mandated listener does not produce one.
     *
     * <p>HTTP/1.0 is the case that behaves differently and the case that matters,
     * because a real 1.0 client exists. Its default disposition is the reverse of
     * 1.1's, and the server both honours it and says so - which is why the 1.0
     * answer carries one field more than the contract set, and why that extra field
     * is named in an expectation of its own rather than tolerated by a loosened one.
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
     * <p>The controls matter as much as the ceiling. A listener that rejected
     * everything large would pass a limit test and break every real client, so a
     * target of {@value #LARGE_TARGET_BYTES} bytes, a single header field of
     * {@value #LARGE_FIELD_BYTES} bytes and a header block of
     * {@value #LARGE_BLOCK_FIELD_COUNT} fields of {@value #LARGE_BLOCK_FIELD_BYTES}
     * bytes each are all required to be served.
     *
     * <p>The long target carries the one assertion in this method that is about
     * correctness rather than capacity: it must be answered 404, never 200. A target
     * truncated anywhere along its length could normalise to the health route and be
     * answered healthy on the strength of bytes that were never received, and that
     * is the single way an unbounded target could produce a wrong ANSWER rather than
     * merely a large one.
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
     *
     * @param route the request target
     * @param total how many header fields the block must contain in all, at least one
     * @return request line first, then exactly {@code total} field lines
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
     * <p>These are the routing rules asserted at unit level in section D, driven
     * here through real bytes so that the parser between the wire and
     * {@code User.normalisePath} is part of the assertion rather than an assumption.
     * The absolute-form case is the one that would break silently: it is the only
     * request shape in which the target carries a scheme and an authority, so it is
     * the only proof that {@code stripAuthority} is still reached now that a server
     * parses the request line.
     *
     * <p>The Host field is asserted to be RECOGNISED in any casing and not to be
     * required at all. That it is not required is a deliberate recording of the
     * listener's behaviour rather than an endorsement: RFC 9112 section 3.2 requires
     * a 1.1 client to send one and allows a server to reject a request without one,
     * and this listener chooses to serve it. That choice is the listener's, the
     * frozen contract defines no 400, and all three implementations of this endpoint
     * route on the target alone - so a request missing a Host is answered by the
     * route it asked for, which is what is asserted here.
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
        try (RawConnection connection = new RawConnection(port)) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                RawResponse response = connection.send(good).readResponse();
                checkEquals("request " + attempt + " of 3 on one connection is served",
                        HTTP_OK, response.status());
                checkEquals("request " + attempt + " is framed accurately",
                        REFERENCE_BODY_BYTE_LENGTH, response.body().length);
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
            checkSetEquals("the refusal carries exactly five fields",
                    REFUSAL_HEADER_NAMES, refusal.names());
            checkEquals("the refusal names GET as allowed", "GET", refusal.headers().get("allow"));
            checkEquals("the connection survived, so the body was drained",
                    HTTP_OK, connection.send(good).readResponse().status());
        } catch (IOException failure) {
            recordRawFailure("draining a Content-Length body", failure);
        }

        // The case that proves the drain is required rather than tidy. A body this
        // size left unread makes the kernel answer the close with a reset, and the
        // client then reads "connection reset by peer" instead of the response that
        // was already written for it - reproduced exactly that way before the drain
        // was added. One mebibyte is enough to fill the socket buffers and is well
        // inside the eight-mebibyte cap, so the whole body is consumed.
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
            checkSetEquals("the large-body refusal carries exactly five fields",
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
                    checkSetEquals("raw HEAD's refusal carries exactly five fields",
                            REFUSAL_HEADER_NAMES, response.names());
                    checkEquals("raw HEAD advertises the length it would have sent",
                            Integer.toString(BODY_METHOD_NOT_ALLOWED
                                    .getBytes(StandardCharsets.UTF_8).length),
                            response.headers().get("content-length"));
                    checkEquals("raw HEAD sends no body at all", 0, response.body().length);
                });
    }

    // -------------------------------------------------------------------------
    // Raw-socket machinery
    // -------------------------------------------------------------------------

    /** One parsed response, framed out of the byte stream by its Content-Length. */
    private record RawResponse(String statusLine, String version, int status,
            Map<String, String> headers, Set<String> names, byte[] body) {
    }

    /** One assertion block applied to a parsed response. */
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
         *
         * @param raw the bytes to send, interpreted as one byte per character
         * @return this connection, for chaining
         * @throws IOException if the write fails
         */
        RawConnection send(String raw) throws IOException {
            OutputStream sink = socket.getOutputStream();
            sink.write(raw.getBytes(StandardCharsets.ISO_8859_1));
            sink.flush();
            return this;
        }

        /**
         * Reads exactly one complete response, leaving any remainder buffered.
         *
         * @return the parsed response
         * @throws IOException if the peer closes or goes quiet before one arrives
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
         *
         * @param expectBody whether the declared Content-Length will be delivered
         * @return the parsed response
         * @throws IOException if the peer closes or goes quiet before one arrives
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
         *
         * @return {@code true} once the peer has gone
         * @throws IOException if reading fails for a reason other than a timeout
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

        /** @return the buffered bytes as text, for a failure message */
        private String buffered() {
            return new String(pending.toByteArray(), StandardCharsets.ISO_8859_1);
        }

        /**
         * Parses and consumes one response if a complete one is buffered.
         *
         * @param expectBody whether the declared Content-Length will be delivered
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
     *
     * @param lines request line first, then header field lines
     * @return the complete request, with no body
     */
    private static String wireRequest(List<String> lines) {
        return wireRequest(lines, "");
    }

    /**
     * Assembles a raw HTTP request with a body appended verbatim.
     *
     * @param lines request line first, then header field lines
     * @param body  raw body bytes, appended with no framing of their own
     * @return the complete request
     */
    private static String wireRequest(List<String> lines, String body) {
        StringBuilder request = new StringBuilder();
        for (String line : lines) {
            request.append(line).append(CRLF);
        }
        return request.append(CRLF).append(body).toString();
    }

    /** @return a response body decoded as UTF-8 */
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
     *
     * @param port       the port the test server bound
     * @param label      names the case in every message
     * @param request    the raw bytes to send
     * @param assertions what to assert about the response
     */
    private static void checkRawExchange(int port, String label, String request,
            RawAssertions assertions) {
        checkRawExchange(port, label, request, true, assertions);
    }

    /**
     * Sends one raw request and applies assertions, optionally expecting no body.
     *
     * @param port       the port the test server bound
     * @param label      names the case in every message
     * @param request    the raw bytes to send
     * @param expectBody whether the declared Content-Length will be delivered,
     *                   which is false for a HEAD request and true otherwise
     * @param assertions what to assert about the response
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
     *
     * @param label   names the case that failed
     * @param failure what went wrong
     */
    private static void recordRawFailure(String label, IOException failure) {
        checksExecuted++;
        checksFailed++;
        System.err.println("FAIL: " + label + " - the raw exchange failed: " + failure);
    }

    // ===================================================================== //
    // Section I - configuration validation and the port grammar
    //
    // A configuration that cannot be published truthfully is refused BEFORE a
    // socket is bound, which is what makes the refusal total: there is no window
    // in which a port is held by a server that would answer 200 with a payload
    // the contract forbids.
    //
    // Every message here names the KEY and withholds the VALUE. That is not
    // decoration: it is what lets the probe print the message verbatim without
    // sanitising it a second time, and it is why a configured value carrying CRLF
    // cannot forge a log line through a refusal.
    // ===================================================================== //

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
     *
     * @param name    application name
     * @param version application version
     * @param path    health route
     * @param host    bind address
     * @return a configuration bound to an ephemeral port
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
     *
     * @param stated the value as it would appear in the properties file
     * @return the resolved port
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
     *
     * @param value the value to describe
     * @return a single-line, quoted rendering
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

    // ===================================================================== //
    // Section J - probe answer validation and connection reuse
    //
    // The probe is consumed by a container HEALTHCHECK, so it fails CLOSED: every
    // doubt resolves to unhealthy. It checks the whole document rather than
    // searching the body for a hopeful substring - the substring test passed for a
    // truncated body that merely quoted the healthy fragment, which is a probe
    // reporting health it never established.
    //
    // The reuse tests cover a seam none of the three implementations could see
    // alone: a refused request that arrives WITH a body must not corrupt the next
    // request on the same connection.
    // ===================================================================== //

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

        // The fail-OPEN case the substring test used to pass: these bytes contain
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
        // which is what a permissive reader would do.
        check("a repeated member name is refused",
                User.probeRejection(200, utf8("{\"name\":\"n\",\"name\":\"other\","
                        + "\"version\":\"1.1.0\",\"timestamp\":\"2026-07-29T08:00:00Z\","
                        + "\"status\":\"UP\"}")) != null);

        for (String body : List.of("[]", "\"UP\"", "42", "null", "true")) {
            check("a body that is JSON but not an object is refused: " + describe(body),
                    User.probeRejection(200, utf8(body)) != null);
        }
        check("a non-string field value is refused",
                User.probeRejection(200, utf8("{\"name\":1,\"version\":\"1.1.0\","
                        + "\"timestamp\":\"2026-07-29T08:00:00Z\",\"status\":\"UP\"}")) != null);

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
     *
     * @param port the port the listener is bound to
     * @throws Exception if the exchange cannot be performed at all
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
     *
     * @param haystack the text to search
     * @param needle   the text to count
     * @return the number of occurrences
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
}
