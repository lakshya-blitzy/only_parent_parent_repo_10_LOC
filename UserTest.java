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
 *      section captures stdout and stderr around a real invocation of
 *      User.main, and additionally proves the default path opens no socket.
 *   B  The frozen response contract: four keys, in the order name, version,
 *      timestamp, status, compact, with the literal status value UP.
 *   C  JSON escaping. The JDK ships no JSON serializer, so User assembles the
 *      document by hand; the escape helper is therefore load-bearing and is
 *      tested directly, including the characters it deliberately leaves alone.
 *   D  Path normalisation, which is the routing decision.
 *   E  Configuration precedence: environment over file over built-in default.
 *   F  Live routing over a real socket, including the negative paths.
 *
 * TWO RULES THIS HARNESS IMPOSES ON ITSELF
 * ----------------------------------------
 *   1. The timestamp is asserted by FORMAT and never by VALUE. It is the only
 *      non-deterministic field in the payload, and an assertion on its value
 *      would make this harness fail for a reason unrelated to correctness.
 *   2. Nothing here mutates the process environment, and no reflection is used
 *      to try. A JVM's environment is fixed at launch, so the environment-over-
 *      file step of the precedence chain is proven instead by passing a variable
 *      name that is genuinely set - see witnessEnvironmentName - and the
 *      end-to-end behaviour with real overrides is covered by
 *      scripts/verify-health.sh and the CI jobs, which export real variables
 *      before starting the server.
 *
 * A NOTE ON ENVIRONMENT VALUES AND DISCLOSURE
 * -------------------------------------------
 * A process environment routinely carries credentials, so the variables this
 * harness reads are named explicitly and never discovered by scanning: a scan
 * ordered by name could just as easily select an API key as a witness. Only two
 * categories are read. The application's own settings - APP_NAME, APP_VERSION,
 * HEALTH_PATH, APP_HOST, PORT and JAVA_PORT - are by definition not secrets,
 * since the first two are published in the health response itself, so those may
 * appear in a diagnostic. PATH, read once as a precedence witness, is compared
 * only through the boolean check form, so a failure reports the name of the check
 * and never the value. No environment variable is written, and no reflection is
 * used to try.
 *
 * EXPECTED OUTPUT THAT IS NOT A FAILURE
 * -------------------------------------
 * Section F deliberately drives User down its fail-closed probe path, and User
 * reports that on stderr by design:
 *   [User] probe could not reach http://...            (section F)
 * It is announced on stdout immediately before it is provoked so that a reader of
 * a CI log does not mistake an expected diagnostic for a real fault.
 *
 * Section E drives the other fail-closed path - an unusable port value - but that
 * one is refused by an exception rather than reported on a stream, so it produces
 * no stderr line to announce. See checkRejects.
 */
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
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

    /** Name of the non-daemon thread {@link User.HealthServer} runs its accept loop on. */
    private static final String DISPATCHER_THREAD_NAME = "health-acceptor";

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
     * Asserts that the default invocation is unchanged and side-effect free.
     *
     * <p>The listener assertion is the interesting one. A dual-mode program that
     * accidentally bound a socket in its default mode would still print the right
     * line, so output alone cannot prove the default path is inert.
     * {@link User.HealthServer} runs its accept loop on a non-daemon thread named
     * {@value #DISPATCHER_THREAD_NAME}; counting those threads before and after
     * the call turns "starts no listener" into something observable.
     */
    private static void verifyPreservedBehaviour() {
        int dispatchersBefore = dispatcherThreadCount();
        CapturedOutput captured = captureDefaultInvocation();

        // If User.main had called System.exit, this JVM would have terminated
        // inside the call above and no summary line would ever be printed, which
        // the CI gate treats as a failure because it requires a check count.
        // The flag documents the requirement and makes it explicit in the log.
        check("default mode returns rather than exiting the JVM", captured.returnedNormally());
        checkEquals("default mode standard output is preserved exactly",
                LEGACY_STDOUT_TEXT + System.lineSeparator(), captured.standardOutput());
        checkEquals("default mode standard output byte length",
                LEGACY_STDOUT_BYTE_LENGTH,
                captured.standardOutput().getBytes(StandardCharsets.UTF_8).length);
        checkEquals("default mode writes nothing to standard error",
                "", captured.standardError());
        checkEquals("default mode starts no HTTP listener",
                dispatchersBefore, dispatcherThreadCount());
    }

    /**
     * What one captured invocation produced on each stream.
     *
     * @param standardOutput  everything written to standard output
     * @param standardError   everything written to standard error
     * @param returnedNormally whether the call returned instead of exiting
     */
    private record CapturedOutput(String standardOutput, String standardError,
            boolean returnedNormally) {
    }

    /**
     * Invokes the default mode with both standard streams redirected.
     *
     * <p>The original streams are restored in a {@code finally} block, so a
     * failure inside the call cannot leave this JVM writing its own results into
     * a byte array where nobody would ever see them.
     *
     * @return what the invocation wrote, and whether it returned
     */
    private static CapturedOutput captureDefaultInvocation() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        boolean returnedNormally = false;
        try {
            System.setOut(new PrintStream(outBuffer, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBuffer, true, StandardCharsets.UTF_8));
            User.main(new String[] {});
            returnedNormally = true;
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new CapturedOutput(outBuffer.toString(StandardCharsets.UTF_8),
                errBuffer.toString(StandardCharsets.UTF_8), returnedNormally);
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
     * @throws IOException if the temporary properties file cannot be written or read
     */
    private static void verifyConfigurationPrecedence() throws IOException {
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
     * @throws IOException if the temporary file cannot be created, written or read
     */
    private static void verifyFileBackedPrecedence() throws IOException {
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
     * Asserts that the environment layer outranks the file layer, without
     * mutating the environment and without reflection.
     *
     * <p>{@code User.resolve} accepts the variable NAME as a parameter, so a name
     * that is genuinely set in this process proves the precedence directly. The
     * witness is named explicitly rather than discovered by scanning the
     * environment: a scan could select a credential, whereas PATH is present
     * everywhere this project runs and is not a secret. Its value is compared
     * with the boolean check form so that a failure reports only the name of the
     * check, never an environment value.
     *
     * @param fromFile a configuration that supplies both a name and a port, so
     *                 that the environment layer has something to outrank
     * @param filePort the port that configuration supplies
     */
    private static void verifyEnvironmentBeatsFile(Properties fromFile, int filePort) {
        String witness = System.getenv(WITNESS_ENV_NAME);
        if (witness == null || witness.isEmpty()) {
            note(WITNESS_ENV_NAME + " is not set, so the environment layer cannot be"
                    + " exercised from inside a JVM without mutating the environment,"
                    + " which this harness will not do; scripts/verify-health.sh and the"
                    + " CI jobs cover it end to end with real variables");
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
            checkEquals("the response declares JSON content",
                    Optional.of("application/json"), healthy.headers().firstValue("Content-Type"));
            check("the response forbids caching and storing",
                    headerContains(healthy, "Cache-Control", "no-store"));
            checkEquals("Content-Length equals the encoded body length",
                    Optional.of(Integer.toString(
                            healthy.body().getBytes(StandardCharsets.UTF_8).length)),
                    healthy.headers().firstValue("content-length"));
            check("no server banner is disclosed",
                    healthy.headers().firstValue("Server").isEmpty());

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
            check("the 404 response also forbids caching",
                    headerContains(missing, "Cache-Control", "no-store"));

            HttpResponse<String> posted = send(client, "POST", port, route);
            checkEquals("POST the route answers 405",
                    HTTP_METHOD_NOT_ALLOWED, posted.statusCode());
            checkEquals("the 405 body is the fixed error document",
                    BODY_METHOD_NOT_ALLOWED, posted.body());
            checkEquals("the 405 names GET as the only allowed method",
                    Optional.of("GET"), posted.headers().firstValue("allow"));

            // HEAD answering 405 is a documented deviation from RFC 9110's
            // expectation that HEAD is supported wherever GET is, not an
            // oversight, and the answer correctly carries no body.
            HttpResponse<String> headed = send(client, "HEAD", port, route);
            checkEquals("HEAD is answered 405 by documented design",
                    HTTP_METHOD_NOT_ALLOWED, headed.statusCode());
            checkEquals("the 405 answer to HEAD carries no body", 0, headed.body().length());

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
}
