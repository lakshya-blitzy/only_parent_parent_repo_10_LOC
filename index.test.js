/**
 * index.test.js - unit and integration tests for the JavaScript health endpoint.
 *
 * THE FILENAME IS A CORRECTNESS REQUIREMENT, NOT A STYLE CHOICE. Node's built-in
 * runner collects files whose name carries a `.test.js` or `_test.js` suffix. A
 * file named `index.spec.js` is silently ignored: the runner collects zero tests
 * and still exits 0, which is a suite that reports success while executing
 * nothing. With no collectible test file present, `node --test
 * --test-reporter=tap` prints "1..0 / # tests 0 / # pass 0" and exits 0. That is
 * why the collected count must be asserted to exceed zero, and why the TAP
 * reporter is requested explicitly: the default reporter prints decorative glyphs
 * rather than a greppable count line. Do not rename this file.
 *
 * `timestamp` is the only non-deterministic value in the payload and is matched
 * against a regular expression only, so no assertion here is time-flaky.
 *
 * Only long-stable built-ins are used, because the committed Node pin and the
 * locally installed runtime are not the same minor line; nothing here depends on
 * an API newer than the manifest's `engines.node` floor.
 */

const { describe, it, before, after } = require("node:test");
const assert = require("node:assert/strict");
const { execFileSync, spawn, spawnSync } = require("node:child_process");
const fs = require("node:fs");
const http = require("node:http");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");

// The module under test. Requiring it must be free of side effects; group A
// proves that in a child process, where a stray write cannot be missed.
const app = require("./index.js");

// Frozen contract constants, duplicated here on purpose rather than imported from
// the module: a test that imports the value it is asserting proves only
// self-consistency - if the implementation's status literal changed to "DOWN", an
// imported constant would change with it and the assertion would still pass.

/** The complete field set of the health payload, in contract order. */
const EXPECTED_KEYS = ["name", "version", "timestamp", "status"];

/** The single value `status` may take while the process is serving. */
const EXPECTED_STATUS = "UP";

/** Fixed-width UTC instant to whole seconds. Format only - never a value (S6). */
const TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;

/** Three-part dotted numeric version. */
const VERSION_PATTERN = /^\d+\.\d+\.\d+$/;

/** The preserved legacy stdout: "12" five times, newline-terminated. */
const LEGACY_STDOUT = "12\n12\n12\n12\n12\n";

/**
 * Byte length of the preserved legacy stdout. These are the exact bytes hashed
 * by the committed backward-compatibility baseline, so the count is asserted
 * alongside the string: a change in encoding that preserved the string would
 * still break the hash.
 */
const LEGACY_BYTE_LENGTH = 15;

/**
 * Byte length of the rendered health body under the default name and version.
 * Identical in all three language implementations, which is why it is worth
 * pinning: a stray space from a JSON serialiser would change it.
 */
const DEFAULT_BODY_BYTE_LENGTH = 108;

/** Exact error bodies. They carry no detail about the deployment by design. */
const NOT_FOUND_BODY = '{"error":"Not Found"}';
const METHOD_NOT_ALLOWED_BODY = '{"error":"Method Not Allowed"}';

/** Expected response media type and cache directive. */
const EXPECTED_CONTENT_TYPE = "application/json";
const NO_STORE_DIRECTIVE = "no-store";

/**
 * The frozen response header-name sets, sorted and lower-cased, asserted by
 * EQUALITY rather than containment.
 *
 * Containment cannot see an ADDED header, and every header this endpoint does
 * not need is disclosure it should not make (S8). Node would send `Date` and
 * would advertise its own version if asked; both are suppressed, which is also
 * what gives Python and Node an identical header-name set. Java's transport
 * injects `Date` unconditionally and that is recorded as a stated deviation in
 * User.java rather than asserted away here.
 */
const CONTRACT_HEADER_NAMES = Object.freeze(["cache-control", "content-length", "content-type"]);
const REFUSAL_HEADER_NAMES = Object.freeze(["allow", "cache-control", "content-length", "content-type"]);

/**
 * The port the application binds by default. The suite must never bind it:
 * every server below is started with `listen(0)` so the tests pass while a
 * developer has the real application running.
 */
const CONFIGURED_DEFAULT_PORT = 8001;

/** Loopback literal. A wildcard bind address is not connectable by a client. */
const LOOPBACK = "127.0.0.1";

/** Request timeout. Short, because a health endpoint must answer immediately. */
const REQUEST_TIMEOUT_MS = 4000;

/** Child-process ceiling, so a hung interpreter fails the test instead of hanging it. */
const CHILD_TIMEOUT_MS = 30000;

/**
 * Environment variables the configuration loader consults. Group E asserts that
 * none of them is mutated by the suite: precedence is exercised through an
 * injected map, never by writing to the real process environment.
 */
const CONFIG_ENV_KEYS = ["APP_NAME", "APP_VERSION", "HEALTH_PATH", "APP_HOST", "NODE_PORT", "PORT"];

/** Snapshot of those variables, taken before any test runs. */
const ENV_SNAPSHOT = Object.freeze(
  CONFIG_ENV_KEYS.reduce((accumulator, key) => {
    accumulator[key] = process.env[key];
    return accumulator;
  }, {}),
);

/**
 * The two configuration diagnostics, worded identically in app.py and User.java.
 * Asserted verbatim, because a shared failure policy that each implementation
 * words differently is not a shared policy to whoever greps the logs.
 */
const UNREADABLE_CONFIG_WARNING = "cannot read the configuration file; using defaults";
const MALFORMED_CONFIG_WARNING = "the configuration file is malformed; using defaults";

/**
 * The SHARED properties grammar fixtures: `[label, file text, expected mapping]`.
 *
 * The identical table - same labels, same text, same expectations - appears in
 * test_app.py and UserTest.java. Every expectation was produced by running
 * `java.util.Properties.load` on the same bytes, which is how User.java reads the
 * shared configuration file, so this table is a transcription of the reference
 * implementation rather than a description of this one.
 */
const SHARED_PROPERTIES_FIXTURES = [
  ["a plain key and value", "a=1\n", { a: "1" }],
  ["a colon separator", "a:1\n", { a: "1" }],
  ["a space separator", "a 1\n", { a: "1" }],
  ["a tab separator", "a\t1\n", { a: "1" }],
  ["a form-feed separator", "a\f1\n", { a: "1" }],
  ["whitespace around the separator", "a = 1\n", { a: "1" }],
  ["trailing value whitespace is preserved", "a=1   \n", { a: "1   " }],
  ["a whitespace-only value is empty", "a=   \n", { a: "" }],
  ["a key with no separator has an empty value", "abc\n", { abc: "" }],
  ["an empty key is still a key", "=v\n", { "": "v" }],
  ["only the first separator separates", "a = b=c \n", { a: "b=c " }],
  ["an escaped space belongs to the key", "a\\ b=x\n", { "a b": "x" }],
  ["an escaped equals belongs to the key", "a\\=b=x\n", { "a=b": "x" }],
  ["an escaped colon belongs to the key", "a\\:b=x\n", { "a:b": "x" }],
  ["a tab escape in a value", "a=x\\ty\n", { a: "x\ty" }],
  ["a newline escape in a value", "a=x\\nz\n", { a: "x\nz" }],
  ["a unicode escape in a value", "a=\\u0041\n", { a: "A" }],
  ["a capital U is not a unicode escape", "a=\\U0041\n", { a: "U0041" }],
  ["an unknown escape is the character itself", "a=\\z\n", { a: "z" }],
  ["an escaped backslash is one backslash", "a=x\\\\y\n", { a: "x\\y" }],
  ["an odd trailing backslash continues the line", "a=one\\\n   two\n", { a: "onetwo" }],
  ["an even trailing backslash ends the line", "a=v\\\\\nb=2\n", { a: "v\\", b: "2" }],
  ["a hash comment is skipped", "#c\na=1\n", { a: "1" }],
  ["a bang comment is skipped", "!c\na=1\n", { a: "1" }],
  ["an indented comment is skipped", "   # c\na=1\n", { a: "1" }],
  ["a continuation line is data, not a comment", "a=x\\\n#y\n", { a: "x#y" }],
  ["CR, LF and CRLF all end a line", "a=1\r\nb=2\rc=3\n", { a: "1", b: "2", c: "3" }],
  ["the last of a repeated key wins", "a=1\na=2\n", { a: "2" }],
  ["quote characters are literal", 'a="q"\n', { a: '"q"' }],
  ["a trailing backslash at end of input is dropped", "a=v\\", { a: "v" }],
  ["a byte-order mark is not stripped", "\ufeffa=1\n", { "\ufeffa": "1" }],
];

/**
 * The shared MALFORMED fixtures: the one condition under which
 * `java.util.Properties.load` refuses a document outright rather than reading
 * part of it as a literal.
 */
const SHARED_MALFORMED_PROPERTIES = [
  ["a short unicode escape in a value", "a=\\u12\n"],
  ["a non-hexadecimal unicode escape", "a=\\uZZZZ\n"],
  ["a malformed unicode escape in a key", "\\u12=v\n"],
];

/**
 * Configuration used by every test that needs deterministic field values.
 *
 * `env: {}` is passed so an environment variable exported in the shell running
 * the suite cannot perturb the name, version or port the assertions expect. The
 * properties file that ships beside the module is still read, so this exercises
 * the real configuration path rather than a synthetic one.
 */
function fixedConfig() {
  return app.loadConfig({ env: {} });
}

// Child-process helpers, so stdout is observed exactly as a shell would see it

/**
 * Runs this same Node binary against the given arguments and returns stdout.
 *
 * `process.execPath` is used rather than the string "node" so the child is
 * guaranteed to be the interpreter running the suite. The working directory is
 * this file's directory so that a relative `require("./index.js")` inside the
 * child resolves to the module under test regardless of where the runner was
 * invoked from. stdin is ignored and stderr is piped rather than inherited, so
 * a diagnostic from the child cannot interleave with the reporter's output.
 *
 * execFileSync throws when the child exits non-zero, so a successful return is
 * itself an assertion that the exit status was 0.
 */
function nodeStdout(args) {
  return execFileSync(process.execPath, args, {
    cwd: __dirname,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    timeout: CHILD_TIMEOUT_MS,
  });
}

/**
 * Runs this same Node binary and returns the full outcome without throwing.
 *
 * This complements nodeStdout: it makes the exit status and stderr assertable
 * values rather than a thrown exception, which is what lets a test state
 * "exits 0 AND writes nothing to stderr" as an explicit expectation.
 *
 * @returns {{status: number|null, stdout: string, stderr: string}} Child outcome.
 */
function runNode(args) {
  const result = spawnSync(process.execPath, args, {
    cwd: __dirname,
    encoding: "utf8",
    timeout: CHILD_TIMEOUT_MS,
  });
  assert.equal(result.error, undefined, `spawning node ${args.join(" ")} failed`);
  return { status: result.status, stdout: result.stdout, stderr: result.stderr };
}

// Server and HTTP helpers

/**
 * Starts a server on an ephemeral port on loopback and resolves the real port.
 *
 * Port 0 asks the kernel for any free port, which is what makes this suite safe
 * to run while the application itself is bound to its configured port. The
 * bound port is read back from server.address() rather than assumed.
 *
 * @returns {Promise<number>} The port the kernel assigned.
 */
function listen(server) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, LOOPBACK, () => {
      const address = server.address();
      if (!address || typeof address !== "object") {
        reject(new Error("server.address() did not return an AddressInfo"));
        return;
      }
      resolve(address.port);
    });
  });
}

/**
 * Closes a server and waits for it to stop listening.
 *
 * Keep-alive sockets would otherwise keep close() pending and leave the test
 * process holding a listening socket open, so any that remain are dropped
 * first. Closing an already-closed server is tolerated, which keeps cleanup
 * hooks safe to run after a failed test.
 *
 * @returns {Promise<void>} Resolves once the socket is released.
 */
function closeServer(server) {
  return new Promise((resolve) => {
    if (!server || !server.listening) {
      resolve();
      return;
    }
    if (typeof server.closeAllConnections === "function") {
      server.closeAllConnections();
    }
    server.close(() => resolve());
  });
}

/**
 * Performs one HTTP request against loopback and resolves the whole response.
 *
 * node:http is used rather than fetch for symmetry with the implementation,
 * which serves and self-probes with the same module. The raw header names are
 * returned alongside the parsed header map so a test can assert that a header
 * is genuinely absent from the wire rather than merely absent from a lookup.
 *
 * @param {{port: number, method?: string, path?: string}} options Request target.
 * @returns {Promise<{status: number, headers: Record<string, string>,
 *                    headerNames: string[], body: string}>} The response.
 */
function request(options) {
  const method = options.method === undefined ? "GET" : options.method;
  const target = options.path === undefined ? "/" : options.path;

  return new Promise((resolve, reject) => {
    const clientRequest = http.request(
      { host: LOOPBACK, port: options.port, path: target, method, timeout: REQUEST_TIMEOUT_MS },
      (response) => {
        let body = "";
        response.setEncoding("utf8");
        response.on("data", (chunk) => {
          body += chunk;
        });
        response.on("aborted", () => reject(new Error(`${method} ${target}: response aborted`)));
        response.on("end", () => {
          resolve({
            status: response.statusCode,
            headers: response.headers,
            // rawHeaders is a flat [name, value, name, value, ...] list; the
            // even indices are the names exactly as they went over the wire.
            headerNames: response.rawHeaders
              .filter((_, index) => index % 2 === 0)
              .map((name) => name.toLowerCase()),
            body,
          });
        });
      },
    );

    clientRequest.on("timeout", () => {
      clientRequest.destroy();
      reject(new Error(`${method} ${target}: no response within ${REQUEST_TIMEOUT_MS} ms`));
    });
    clientRequest.on("error", reject);
    clientRequest.end();
  });
}

/**
 * Reads a response header case-insensitively.
 *
 * RFC 9110 defines HTTP field names as case-insensitive, and the sibling Java
 * implementation of this same endpoint normalises field-name casing differently
 * from Node. A case-sensitive assertion would therefore pass for two of the
 * three implementations and fail for the third. Node already lower-cases the
 * keys of `response.headers`, so this helper only has to lower-case the name
 * being looked up - but going through it keeps the intent explicit and makes
 * every header assertion in this file uniform.
 *
 * @returns {string|undefined} The header value, or undefined when absent.
 */
function headerValue(response, name) {
  return response.headers[name.toLowerCase()];
}

/**
 * Reports whether a header was present on the wire, case-insensitively.
 *
 * @returns {boolean} True when the wire carried the header.
 */
function hasHeader(response, name) {
  return response.headerNames.includes(name.toLowerCase());
}

/**
 * Returns the wire header names lower-cased and sorted, for an equality assertion.
 *
 * Sorted because header order carries no meaning, lower-cased because RFC 9110
 * makes field names case-insensitive, and taken from the raw wire list rather
 * than the parsed map so a duplicated header cannot hide inside a single key.
 *
 * @returns {string[]} Sorted, lower-cased header names.
 */
function sortedHeaderNames(response) {
  return [...response.headerNames].sort();
}

/**
 * Asks the kernel for a port, then releases it, and returns the number.
 *
 * A child process cannot be handed an already-bound listening socket, so a test
 * that spawns `--serve` has to name a port up front. Binding zero and reading
 * the assignment back is the closest thing to a guarantee available: the port
 * was free a moment ago, and nothing else in this suite ever binds a fixed port.
 *
 * @returns {Promise<number>} A port that was free when this resolved.
 */
function unusedPort() {
  return new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.once("error", reject);
    probe.listen(0, LOOPBACK, () => {
      const address = probe.address();
      const port = address && typeof address === "object" ? address.port : 0;
      probe.close(() => (port > 0 ? resolve(port) : reject(new Error("no port was assigned"))));
    });
  });
}

/**
 * Sleeps, so a poll loop yields the event loop between attempts.
 */
function pause(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

// Diagnostic capture and temporary properties files

/**
 * Runs `work` with stderr captured, returning its result and everything written.
 *
 * Several assertions below are about a diagnostic the module writes DELIBERATELY.
 * Letting those lines through would interleave them with the runner's own TAP
 * output, and it would also leave the diagnostic unasserted - the interesting part
 * is usually that exactly one line was written and that it withheld the value.
 * The original writer is restored on every path.
 *
 * @returns {{value: *, written: string}} Its return value and the captured text.
 */
function withStderr(work) {
  const original = process.stderr.write;
  let written = "";
  process.stderr.write = (chunk) => {
    written += chunk;
    return true;
  };
  try {
    return { value: work(), written };
  } finally {
    process.stderr.write = original;
  }
}

/** Every temporary directory created by the suite, removed by the hook below. */
const temporaryDirectories = [];

/**
 * Writes a throwaway properties file and returns its path.
 *
 * Created under the system temporary directory, never inside the repository, so no
 * test run can leave an untracked artifact behind and dirty the clean-tree gate.
 *
 * @param {string[]} lines Properties-file lines, written verbatim.
 */
function writePropertiesFile(lines) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "health-config-"));
  temporaryDirectories.push(directory);
  const file = path.join(directory, "app.config.properties");
  fs.writeFileSync(file, `${lines.join("\n")}\n`, "utf8");
  return file;
}

/**
 * Writes RAW BYTES as the properties file, so a fixture can carry a sequence that
 * is not valid UTF-8 at all - which a string cannot express.
 */
function writePropertiesBytes(bytes) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "health-config-raw-"));
  temporaryDirectories.push(directory);
  const file = path.join(directory, "app.config.properties");
  fs.writeFileSync(file, bytes);
  return file;
}

/**
 * Returns a path that is guaranteed not to exist, to exercise the
 * missing-file fallback without depending on the absence of a real path.
 */
function missingPropertiesPath() {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "health-missing-"));
  temporaryDirectories.push(directory);
  return path.join(directory, "absent.properties");
}

// Removes every temporary directory once the whole file has run, whatever the
// outcome of the individual tests.
after(() => {
  for (const directory of temporaryDirectories) {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});

// A. Preserved legacy behaviour

describe("A. preserved legacy behaviour", () => {
  it("add(5, 7) returns 12, the value the original program printed", () => {
    assert.equal(app.add(5, 7), 12);
  });

  it("add sums other numeric pairs, including negatives and fractions", () => {
    assert.equal(app.add(-3, 3), 0);
    assert.equal(app.add(0, 0), 0);
    assert.equal(app.add(0.5, 0.25), 0.75);
    assert.equal(app.add(-4, -6), -10);
    assert.equal(app.add(1e3, 24), 1024);
  });

  it("exposes the documented public API, so the module is consumable as a library", () => {
    // Every name below is part of the published surface and is asserted to exist
    // with the expected type, which is what makes the module consumable as a
    // library rather than only runnable as a program.
    const expectedFunctions = [
      "add",
      "loadConfig",
      "parseProperties",
      "currentTimestamp",
      "buildPayload",
      "healthPayload",
      "renderPayload",
      "normalizePath",
      "configRoute",
      "createServer",
      "buildServer",
      "serve",
      "probe",
      "stripAuthority",
      "isSingleLineText",
      "isRequestTarget",
      "validateConfig",
      "sanitizeForLog",
      "probeAuthority",
      "probeRejection",
    ];
    for (const name of expectedFunctions) {
      assert.equal(typeof app[name], "function", `${name} should be an exported function`);
    }

    assert.equal(typeof app.CONFIG_FILE, "string");
    assert.equal(typeof app.HEALTH_STATUS, "string");
    assert.equal(typeof app.CONTENT_TYPE, "string");
    assert.equal(typeof app.CACHE_CONTROL, "string");
    assert.equal(typeof app.DEFAULTS, "object");
    assert.equal(typeof app.ENV_KEYS, "object");
    assert.equal(typeof app.MAX_PROBE_BODY_BYTES, "number");
  });

  it("publishes the documented aliases as the very same functions", () => {
    // Both names are documented, so a consumer written against either resolves.
    assert.equal(app.healthPayload, app.buildPayload);
    assert.equal(app.buildServer, app.createServer);
  });

  it("requiring the module writes nothing to stdout", () => {
    // The headline backward-compatibility assertion: the five writes live behind a
    // main-module guard, so importing the file is silent and the function is
    // consumable as a library. A child process is used because an in-process check
    // cannot distinguish "never written" from "written before the test started".
    const stdout = nodeStdout(["-e", "require('./index.js');"]);
    assert.equal(stdout, "");
    assert.equal(Buffer.byteLength(stdout), 0);
  });

  it("requiring the module writes nothing to stderr either", () => {
    const child = runNode(["-e", "require('./index.js');"]);
    assert.equal(child.status, 0);
    assert.equal(child.stdout, "");
    assert.equal(child.stderr, "");
  });

  it("the default invocation still prints 12 five times, byte for byte", () => {
    // These are the exact bytes the committed backward-compatibility baseline
    // hashes. The five writes must not be de-duplicated or collapsed into a
    // loop: the output itself is the contract.
    const stdout = nodeStdout(["index.js"]);
    assert.equal(stdout, LEGACY_STDOUT);
    assert.equal(Buffer.byteLength(stdout), LEGACY_BYTE_LENGTH);
    assert.equal(stdout.split("\n").filter((line) => line !== "").length, 5);
  });

  it("the default invocation exits 0 and writes nothing to stderr", () => {
    const child = runNode(["index.js"]);
    assert.equal(child.status, 0);
    assert.equal(child.stdout, LEGACY_STDOUT);
    assert.equal(child.stderr, "");
  });

  it("an unrecognised flag falls through to the legacy output rather than failing", () => {
    // Documented behaviour: the legacy invocation never fails and there is no
    // usage error to print, so an unknown argument is simply ignored.
    const child = runNode(["index.js", "--not-a-real-flag"]);
    assert.equal(child.status, 0);
    assert.equal(child.stdout, LEGACY_STDOUT);
  });
});


// B. The frozen health payload contract

describe("B. health payload contract", () => {
  it("carries exactly the four contract fields, in the contract order", () => {
    // Order is part of the contract, not an implementation detail:
    // JSON.stringify serialises own string keys in insertion order, so the key
    // order of this object is what appears on the wire. deepStrictEqual against
    // an array asserts the order and the completeness of the set at once - it
    // fails on a missing field, an extra field and a reordered field alike.
    const payload = app.buildPayload(fixedConfig());
    assert.deepStrictEqual(Object.keys(payload), EXPECTED_KEYS);
  });

  it('reports the literal status "UP"', () => {
    const payload = app.buildPayload(fixedConfig());
    assert.equal(payload.status, EXPECTED_STATUS);
  });

  it("reports a non-empty application name", () => {
    const payload = app.buildPayload(fixedConfig());
    assert.equal(typeof payload.name, "string");
    assert.ok(payload.name.length > 0, "name must not be empty");
    assert.equal(payload.name.trim(), payload.name, "name must not be padded");
  });

  it("reports a three-part dotted numeric version", () => {
    const payload = app.buildPayload(fixedConfig());
    assert.equal(typeof payload.version, "string");
    assert.match(payload.version, VERSION_PATTERN);
  });

  it("reports a fixed-width UTC timestamp, asserted by format and never by value", () => {
    // The timestamp is the only non-deterministic field in the payload and the
    // only wall-clock dependence in the repository. Asserting a value would
    // make this gate fail for a reason unrelated to correctness, so the
    // assertion is a format check: whole seconds, no fractional part, a
    // trailing "Z" zone designator, and a fixed width.
    const payload = app.buildPayload(fixedConfig());
    assert.equal(typeof payload.timestamp, "string");
    assert.match(payload.timestamp, TIMESTAMP_PATTERN);
    assert.equal(payload.timestamp.length, 20);
    assert.ok(!payload.timestamp.includes("."), "timestamp must not carry fractional seconds");
    assert.ok(payload.timestamp.endsWith("Z"), "timestamp must carry the UTC designator");
    // Parseable as a real instant - still a format assertion, not a value one.
    assert.ok(Number.isFinite(Date.parse(payload.timestamp)), "timestamp must be parseable");
  });

  it("formats any supplied instant to whole seconds in UTC", () => {
    // currentTimestamp accepts an instant, so its formatting is deterministic
    // and can be asserted exactly without depending on the current time.
    assert.equal(app.currentTimestamp(new Date(Date.UTC(2026, 6, 28, 13, 47, 8, 123))), "2026-07-28T13:47:08Z");
    assert.equal(app.currentTimestamp(new Date(Date.UTC(2000, 0, 1, 0, 0, 0, 0))), "2000-01-01T00:00:00Z");
    assert.match(app.currentTimestamp(), TIMESTAMP_PATTERN);
  });

  it("builds a payload from the ambient configuration when none is supplied", () => {
    // A caller that has no configuration in hand must still get a valid payload.
    for (const payload of [app.buildPayload(), app.buildPayload(undefined), app.buildPayload(null)]) {
      assert.deepStrictEqual(Object.keys(payload), EXPECTED_KEYS);
      assert.equal(payload.status, EXPECTED_STATUS);
    }
  });

  it("renders compact JSON with no whitespace anywhere", () => {
    // Python's serialiser inserts whitespace after separators by default and
    // must be asked for the compact form; this asserts the JavaScript rendering
    // that the other two implementations are matched against.
    const body = app.renderPayload(app.buildPayload(fixedConfig()));
    assert.ok(!/\s/.test(body), `rendered body must contain no whitespace: ${body}`);
    assert.ok(body.startsWith("{"), "rendered body must be a JSON object");
    assert.ok(body.endsWith("}"), "rendered body must be a JSON object");
  });

  it("renders exactly 108 bytes under the default name and version", () => {
    // Byte-identical body length across all three language implementations is
    // what the cross-language parity check rests on, so it is pinned here.
    const body = app.renderPayload(app.buildPayload(fixedConfig()));
    assert.equal(Buffer.byteLength(body), DEFAULT_BODY_BYTE_LENGTH);
    assert.equal(body.length, DEFAULT_BODY_BYTE_LENGTH, "body is ASCII, so bytes and characters agree");
  });

  it("renders a body that parses back to an equal payload in the same field order", () => {
    const payload = app.buildPayload(fixedConfig());
    const body = app.renderPayload(payload);
    const parsed = JSON.parse(body);
    assert.deepStrictEqual(parsed, payload);
    assert.deepStrictEqual(Object.keys(parsed), EXPECTED_KEYS);
  });

  it("accepts a configuration, a payload, or nothing at all", () => {
    // Three call shapes are documented, and all three must produce a body that
    // satisfies the contract - a caller holding only a config should not have
    // to build a payload first.
    const config = fixedConfig();
    const bodies = [app.renderPayload(config), app.renderPayload(app.buildPayload(config)), app.renderPayload()];
    for (const body of bodies) {
      const parsed = JSON.parse(body);
      assert.deepStrictEqual(Object.keys(parsed), EXPECTED_KEYS);
      assert.equal(parsed.status, EXPECTED_STATUS);
      assert.match(parsed.timestamp, TIMESTAMP_PATTERN);
    }

    // The claim under test is that the three call shapes AGREE, so they are
    // compared against each other rather than against the parity constant.
    // Restating 108 here would make this test fail on a legitimate
    // configuration rename for a reason unrelated to the call shapes, and the
    // parity constant already has one dedicated owner test above.
    const lengths = bodies.map((body) => Buffer.byteLength(body));
    assert.equal(lengths[1], lengths[0], "the config and payload call shapes must render equal bytes");
    assert.equal(lengths[2], lengths[0], "the no-argument call shape must render equal bytes");
  });

  it("reports the configured name and version verbatim", () => {
    // Proves the payload is genuinely driven by configuration rather than by
    // hardcoded literals, including for values that are not the defaults.
    const file = writePropertiesFile(["app.name=payload-name-check", "app.version=4.5.6"]);
    const payload = app.buildPayload(app.loadConfig({ file, env: {} }));
    assert.equal(payload.name, "payload-name-check");
    assert.equal(payload.version, "4.5.6");
    assert.match(payload.version, VERSION_PATTERN);
    assert.deepStrictEqual(Object.keys(payload), EXPECTED_KEYS);
  });
});

// C. Path normalisation, asserted as a pure function

describe("C. path normalisation", () => {
  // The route is matched by comparing the normalised request target with the
  // normalised configured path, so these tables assert exactly what the router
  // will decide - no server required.
  const routePath = app.normalizePath(fixedConfig().healthPath);

  it("normalises the configured health path to /health", () => {
    assert.equal(routePath, "/health");
  });

  it("matches the health path, one trailing slash, and a query string", () => {
    const matching = ["/health", "/health/", "/health?x=1", "/health?", "/health/?x=1", "/health?a=1&b=2"];
    for (const target of matching) {
      assert.equal(app.normalizePath(target), routePath, `${target} should match the route`);
    }
  });

  it("does not match a doubled trailing slash, another path, or the root", () => {
    // Exactly one trailing slash is forgiven. "/health//" is a different
    // resource and is deliberately left to the 404 branch.
    const notMatching = ["/health//", "/nope", "/", "/healthz", "/health/extra", "/HEALTH", "/api/health"];
    for (const target of notMatching) {
      assert.notEqual(app.normalizePath(target), routePath, `${target} should not match the route`);
    }
  });

  it("strips everything from the first question mark", () => {
    assert.equal(app.normalizePath("/health?x=1"), "/health");
    assert.equal(app.normalizePath("/nope?x=1"), "/nope");
    assert.equal(app.normalizePath("/health?x=/health"), "/health");
  });

  it("removes exactly one trailing slash and never more", () => {
    assert.equal(app.normalizePath("/health/"), "/health");
    assert.equal(app.normalizePath("/health//"), "/health/");
    assert.equal(app.normalizePath("/health///"), "/health//");
  });

  it("normalises an absent, empty or query-only target to the root", () => {
    // req.url is always a string in practice, but a pure function that is part
    // of the public surface must not throw on a hostile argument.
    assert.equal(app.normalizePath("/"), "/");
    assert.equal(app.normalizePath(""), "/");
    assert.equal(app.normalizePath("?x=1"), "/");
    assert.equal(app.normalizePath(undefined), "/");
    assert.equal(app.normalizePath(null), "/");
  });

  it("strips an absolute-form authority, which RFC 9112 permits in a request line", () => {
    // A proxy-aware client emits `GET http://host:8001/health HTTP/1.1`. The Java
    // implementation reduced this from the beginning; Python and this module did
    // not, so the same request reached the route on one implementation and 404'd
    // on the other two.
    const cases = {
      "http://host:8001/health": "/health",
      "http://host:8001/health/": "/health",
      "http://host/nope": "/nope",
      "https://host:443/health?probe=1": "/health",
      "http://host": "/",
    };
    for (const [target, expected] of Object.entries(cases)) {
      assert.equal(app.normalizePath(target), expected, target);
    }
  });

  it("strips a fragment, which is a client-side construct and selects no route", () => {
    assert.equal(app.normalizePath("/health#section"), "/health");
    assert.equal(app.normalizePath("/health?probe=1#section"), "/health");
  });

  it("validates the scheme before removing anything, so a query keeps its URL", () => {
    // `://` inside a QUERY is data. Without the scheme check `/health?next=http://x/`
    // would be truncated to `/` and the route would be lost.
    for (const target of [
      "/health?next=http://elsewhere/",
      "/health",
      "/",
      "//health",
      "/health%2f",
      "/9nothing://x",
      "/-bad://x",
      "http:/health",
      "",
    ]) {
      assert.equal(app.stripAuthority(target), target, target);
    }
  });

  it("always returns a path beginning with a slash", () => {
    for (const target of ["/health", "/health/", "", "?x=1", "/nope", undefined]) {
      assert.ok(app.normalizePath(target).startsWith("/"), `${target} should normalise to an absolute path`);
    }
  });
});


// D. Routing, status codes and headers over a live server

describe("D. routing over a live server", () => {
  let server;
  let port;
  let config;

  before(async () => {
    // listen(0) asks the kernel for a free port. Hardcoding the configured port
    // would make the suite fail whenever a developer had the application
    // running, which is precisely when tests are most likely to be run.
    config = fixedConfig();
    server = app.createServer(config);
    port = await listen(server);
  });

  after(async () => {
    // Releases the listening socket so the test process can exit cleanly and
    // leaves nothing bound behind, whatever happened above.
    await closeServer(server);
    assert.equal(server.listening, false, "the test server must not be left listening");
  });

  it("binds an ephemeral port rather than the application's configured port", () => {
    assert.ok(Number.isInteger(port), "the bound port must be an integer");
    assert.ok(port > 0, "the kernel must have assigned a real port");
    assert.notEqual(port, CONFIGURED_DEFAULT_PORT);
    assert.notEqual(port, config.port);
    assert.equal(server.listening, true);
  });

  it("answers GET /health with 200 and a payload reporting UP", async () => {
    const response = await request({ port, path: config.healthPath });
    assert.equal(response.status, 200);

    const payload = JSON.parse(response.body);
    assert.deepStrictEqual(Object.keys(payload), EXPECTED_KEYS);
    assert.equal(payload.status, EXPECTED_STATUS);
    assert.equal(payload.name, config.name);
    assert.equal(payload.version, config.version);
    assert.match(payload.version, VERSION_PATTERN);
    assert.match(payload.timestamp, TIMESTAMP_PATTERN);
  });

  it("serves the contract headers, matched case-insensitively", async () => {
    // Field names are case-insensitive per RFC 9110, and the sibling Java
    // implementation normalises their casing differently, so every header
    // assertion in this suite goes through a case-insensitive lookup. A
    // case-sensitive assertion would pass here and fail against Java.
    const response = await request({ port, path: config.healthPath });
    assert.equal(headerValue(response, "Content-Type"), EXPECTED_CONTENT_TYPE);
    assert.equal(headerValue(response, "content-type"), EXPECTED_CONTENT_TYPE);
    assert.equal(headerValue(response, "CONTENT-TYPE"), EXPECTED_CONTENT_TYPE);

    const cacheControl = headerValue(response, "Cache-Control");
    assert.equal(typeof cacheControl, "string");
    assert.ok(
      cacheControl.includes(NO_STORE_DIRECTIVE),
      `cache-control must forbid storing a health answer, got: ${cacheControl}`,
    );
    assert.ok(cacheControl.includes("no-cache"));
    assert.ok(cacheControl.includes("must-revalidate"));
  });

  it("declares a content length that matches the body exactly", async () => {
    const response = await request({ port, path: config.healthPath });
    const declared = Number(headerValue(response, "Content-Length"));
    assert.equal(declared, Buffer.byteLength(response.body));

    // Cross-checked against a locally rendered body rather than the parity
    // constant: this proves the server neither truncated nor padded what the
    // renderer produced, and it holds under any configuration.
    assert.equal(declared, Buffer.byteLength(app.renderPayload(config)));
  });

  it("discloses neither a Date nor a Server header (S8: least disclosure)", async () => {
    // Node adds a Date header by default and would happily advertise its own
    // version; the implementation suppresses both, which gives the three
    // language implementations an identical header-name set and keeps a
    // runtime version off a network-reachable surface. Header suppression is
    // trivially regressed by a refactor, so it is asserted against the raw wire
    // header names as well as the parsed map.
    const response = await request({ port, path: config.healthPath });
    assert.equal(headerValue(response, "Date"), undefined);
    assert.equal(headerValue(response, "Server"), undefined);
    assert.equal(hasHeader(response, "date"), false, `wire headers: ${response.headerNames.join(", ")}`);
    assert.equal(hasHeader(response, "server"), false, `wire headers: ${response.headerNames.join(", ")}`);

    // The three contract headers must all be present on the wire.
    for (const name of ["content-type", "cache-control", "content-length"]) {
      assert.equal(hasHeader(response, name), true, `${name} must be sent`);
    }
  });

  it("sends EXACTLY the frozen header set on 200, asserted by equality", async () => {
    // Equality, not containment: a containment check passes when a header is
    // ADDED, and an added header on this surface is disclosure nobody asked for.
    // This is the assertion that would catch a future refactor reintroducing
    // Node's default Date header, or a helper quietly attaching Connection.
    const response = await request({ port, path: config.healthPath });
    assert.equal(response.status, 200);
    assert.deepStrictEqual(sortedHeaderNames(response), [...CONTRACT_HEADER_NAMES]);
  });

  it("sends EXACTLY the frozen header set on 404, asserted by equality", async () => {
    const response = await request({ port, path: "/nope" });
    assert.equal(response.status, 404);
    assert.deepStrictEqual(sortedHeaderNames(response), [...CONTRACT_HEADER_NAMES]);
  });

  it("sends EXACTLY the frozen set plus Allow on 405, for every refused method", async () => {
    // Allow is the ONLY header a refusal adds. Asserted for every method the
    // suite refuses, because a per-method branch is exactly where a header set
    // drifts apart without anyone noticing.
    for (const method of ["POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"]) {
      const response = await request({ port, method, path: config.healthPath });
      assert.equal(response.status, 405, `${method} should not be allowed`);
      assert.deepStrictEqual(
        sortedHeaderNames(response),
        [...REFUSAL_HEADER_NAMES],
        `${method} sent: ${response.headerNames.join(", ")}`,
      );
    }
  });

  it("serves the health path with one trailing slash and with a query string", async () => {
    for (const target of [`${config.healthPath}/`, `${config.healthPath}?verbose=1`, `${config.healthPath}/?x=1`]) {
      const response = await request({ port, path: target });
      assert.equal(response.status, 200, `${target} should be served`);
      assert.equal(JSON.parse(response.body).status, EXPECTED_STATUS);
    }
  });

  it("answers an unknown path with 404 and a detail-free JSON error", async () => {
    for (const target of ["/nope", "/", "/healthz", `${config.healthPath}//`]) {
      const response = await request({ port, path: target });
      assert.equal(response.status, 404, `${target} should not be found`);
      assert.equal(response.body, NOT_FOUND_BODY);
      assert.equal(headerValue(response, "content-type"), EXPECTED_CONTENT_TYPE);
      // The error body must not echo the requested path back to the caller.
      assert.ok(!response.body.includes(target), "the error body must not disclose the request target");
    }
  });

  it("answers a non-GET method with 405 and an Allow header naming GET", async () => {
    for (const method of ["POST", "PUT", "DELETE", "PATCH", "OPTIONS"]) {
      const response = await request({ port, method, path: config.healthPath });
      assert.equal(response.status, 405, `${method} should not be allowed`);
      assert.equal(response.body, METHOD_NOT_ALLOWED_BODY);
      assert.equal(headerValue(response, "Allow"), "GET");
      assert.equal(hasHeader(response, "allow"), true);
      assert.equal(headerValue(response, "content-type"), EXPECTED_CONTENT_TYPE);
    }
  });

  it("answers HEAD with 405, the documented deviation from GET/HEAD parity", async () => {
    // HTTP expects HEAD wherever GET is supported. The endpoint is GET-only by
    // design and no identified consumer issues a HEAD request, so this is a
    // documented deferral rather than an oversight - asserted here so the
    // decision is visible and cannot drift silently.
    const response = await request({ port, method: "HEAD", path: config.healthPath });
    assert.equal(response.status, 405);
    assert.equal(headerValue(response, "Allow"), "GET");
    // A HEAD response carries no body, which is why header inspection of this
    // endpoint must use a GET that discards the body rather than a HEAD.
    assert.equal(response.body, "");
  });

  it("answers repeated requests consistently, with a fresh timestamp each time", async () => {
    // Proves the payload is rebuilt per request rather than cached, without
    // asserting any timestamp value.
    const first = await request({ port, path: config.healthPath });
    const second = await request({ port, path: config.healthPath });
    assert.equal(first.status, 200);
    assert.equal(second.status, 200);
    for (const response of [first, second]) {
      const payload = JSON.parse(response.body);
      assert.deepStrictEqual(Object.keys(payload), EXPECTED_KEYS);
      assert.equal(payload.status, EXPECTED_STATUS);
      assert.match(payload.timestamp, TIMESTAMP_PATTERN);
    }
    assert.equal(JSON.parse(first.body).name, JSON.parse(second.body).name);
  });
});

describe("D2. a server built from non-default configuration", () => {
  it("serves the configured path and 404s the former default", async () => {
    // Proves the health path is genuinely configurable rather than merely
    // parameterised in name: the old default must stop working.
    const file = writePropertiesFile([
      "app.name=configured-path-check",
      "app.version=2.3.4",
      "health.path=/livez",
    ]);
    const config = app.loadConfig({ file, env: {} });
    assert.equal(config.healthPath, "/livez");

    const server = app.createServer(config);
    try {
      const port = await listen(server);

      const served = await request({ port, path: "/livez" });
      assert.equal(served.status, 200);
      const payload = JSON.parse(served.body);
      assert.equal(payload.name, "configured-path-check");
      assert.equal(payload.version, "2.3.4");
      assert.equal(payload.status, EXPECTED_STATUS);
      assert.deepStrictEqual(Object.keys(payload), EXPECTED_KEYS);

      const formerDefault = await request({ port, path: "/health" });
      assert.equal(formerDefault.status, 404);
      assert.equal(formerDefault.body, NOT_FOUND_BODY);
    } finally {
      // try/finally rather than a hook: this server is local to the test, and
      // it must be released even if an assertion above throws.
      await closeServer(server);
    }
  });

  it("self-probes to 0 while listening and to 1 once the socket is gone", async () => {
    // The probe is in process because slim and JRE base images ship neither curl
    // nor wget, so the application has to check itself. probe() resolves a code
    // rather than calling process.exit, so calling it here cannot kill the runner.
    const config = fixedConfig();
    const server = app.createServer(config);
    let port;
    try {
      port = await listen(server);
      const healthy = await app.probe({ config, host: LOOPBACK, port, timeout: REQUEST_TIMEOUT_MS });
      assert.equal(healthy, 0, "a listening endpoint reporting UP must probe as healthy");
    } finally {
      await closeServer(server);
    }

    // Fail closed: with nothing listening the probe must report unhealthy. The
    // implementation writes an expected one-line diagnostic to stderr here.
    const unhealthy = await app.probe({ config, host: LOOPBACK, port, timeout: 1000 });
    assert.equal(unhealthy, 1, "a refused connection must probe as unhealthy");
  });
});


// E. Configuration precedence: environment > properties file > default

// F. Configuration validation and the port grammar. A configuration that cannot be
// published truthfully is refused BEFORE a socket is bound, so there is no window
// in which a port is held by a server that would answer 200 with a payload the
// contract forbids. All three implementations raise, and the message wording is
// byte-identical across them - an operator greps one deployment's logs, not one
// language's.

describe("F. configuration validation", () => {
  const NAME_REASON = "invalid app.name: it must be non-empty text with no control character";
  const VERSION_REASON =
    "invalid app.version: it must be a three-part dotted numeric version";
  const PATH_REASON = "invalid health.path: it is not a valid request target";
  const HOST_REASON = "invalid app.host: it must be non-empty text with no control character";

  function base() {
    return app.loadConfig({ env: {} });
  }

  function rejectionOf(overrides) {
    try {
      app.validateConfig({ ...base(), ...overrides });
      return null;
    } catch (refusal) {
      return refusal.message;
    }
  }

  it("accepts the configuration that ships with the module", () => {
    assert.equal(rejectionOf({}), null);
  });

  it("refuses a name that is empty or carries a control character", () => {
    for (const name of ["", "a\nb", "a\rb", "a\u001bb", "a\u007fb"]) {
      assert.equal(rejectionOf({ name }), NAME_REASON, JSON.stringify(name));
    }
    assert.equal(rejectionOf({ name: "my app" }), null, "a space is ordinary text");
  });

  it("refuses a version that is not three dotted numeric parts", () => {
    for (const version of ["", "1.1", "1.1.0.0", "v1.1.0", "1.1.0-rc1", "1..0", "1.1."]) {
      assert.equal(rejectionOf({ version }), VERSION_REASON, JSON.stringify(version));
    }
    for (const version of ["1.1.0", "0.0.0", "10.20.30"]) {
      assert.equal(rejectionOf({ version }), null, JSON.stringify(version));
    }
  });

  it("refuses a health path that is not a visible-ASCII request target", () => {
    for (const healthPath of ["", "/heal th", "/health\r\nX", "/health\n", "/hea\u001blth"]) {
      assert.equal(rejectionOf({ healthPath }), PATH_REASON, JSON.stringify(healthPath));
    }
  });

  it("refuses a network-path reference, which only this runtime could serve", () => {
    // RFC 3986 section 4.2 reads "//health" as an authority named "health", not as
    // a path, and the three platform servers do not agree about it: CPython's
    // request parser folds an inbound "//health" down to "/health" and the JDK's
    // URI parser resolves it to an empty path, while this runtime hands it through
    // unchanged. A value all three validators accept and only one can answer makes
    // HEALTH_PATH=//health a configuration-dependent outage - Python's and Java's
    // self-probes exit 1 while this one reports healthy - so the shared rule
    // refuses it before a socket is bound.
    for (const healthPath of ["//health", "///health", "//health/", "//host/health"]) {
      assert.equal(rejectionOf({ healthPath }), PATH_REASON, JSON.stringify(healthPath));
    }
  });

  it("accepts a route with no leading slash and grades the route it will serve", () => {
    // The validator grades the NORMALISED route rather than the raw value, which is
    // what makes validation and routing the same decision in all three
    // implementations: a validator grading the raw value would refuse an unrooted
    // path outright, so HEALTH_PATH=healthz would stop that implementation from
    // starting while another served /healthz.
    for (const [configured, expected] of [
      ["healthz", "/healthz"],
      ["health", "/health"],
      ["/health/", "/health"],
      ["/health?probe=1", "/health"],
    ]) {
      assert.equal(rejectionOf({ healthPath: configured }), null, JSON.stringify(configured));
      assert.equal(app.configRoute(configured), expected, JSON.stringify(configured));
    }
  });

  it("reduces a configured path to a route the shared way", () => {
    // The same table appears in test_app.py and UserTest.java. configRoute is the
    // single function both the validator and the router go through, so this table
    // is simultaneously the routing contract and the validation contract.
    const cases = [
      ["/health", "/health"],
      ["health", "/health"],
      ["healthz", "/healthz"],
      ["/health/", "/health"],
      ["/health?probe=1", "/health"],
      ["/health#part", "/health"],
      // The leading slash is supplied BEFORE normalisation, so a configured value
      // that looks like an absolute URL is no longer in absolute form by the time
      // the authority would be stripped. All three implementations do this in the
      // same order, which is the property that matters.
      ["http://host:8000/health", "/http://host:8000/health"],
      ["/", "/"],
      ["//", "/"],
      ["//health", "//health"],
      ["/health//", "/health/"],
    ];
    for (const [configured, expected] of cases) {
      assert.equal(app.configRoute(configured), expected, JSON.stringify(configured));
    }
  });

  it("refuses a host carrying a control character", () => {
    for (const host of ["", "127.0.0.1\r\nX", "a\u0000b"]) {
      assert.equal(rejectionOf({ host }), HOST_REASON, JSON.stringify(host));
    }
  });

  it("never quotes the offending value, so a rejection cannot forge a log line", () => {
    // The messages name the KEY and withhold the VALUE, which is what lets the
    // probe print them verbatim without sanitising them a second time.
    const forged = "x\r\n[index.js] health endpoint listening on http://evil/";
    const message = rejectionOf({ name: forged });
    assert.equal(message, NAME_REASON);
    assert.ok(!message.includes("evil"), "the value must not appear in the message");
    assert.ok(!message.includes("\n"), "the message must be a single line");
  });

  it("refuses before it binds, so no socket is ever held by a bad configuration", () => {
    assert.throws(
      () => app.createServer({ ...base(), version: "1.2" }),
      (error) => error instanceof RangeError && error.message === VERSION_REASON,
    );
  });

  it("gates the port through an ASCII-decimal grammar", () => {
    // Number() and parseInt() both accept forms an operator never intends: a
    // numeric separator, a radix prefix, a sign, and - the one that matters - a
    // Unicode decimal digit, which would make the two implementations disagree
    // about which port was requested.
    for (const port of ["8_001", "0x1f41", "-8001", "8001.0", "\u0668\u0660\u0660\u0661", "80 01", "eighty"]) {
      assert.throws(
        () => app.loadConfig({ env: { NODE_PORT: port } }),
        RangeError,
        JSON.stringify(port),
      );
    }
    assert.equal(app.loadConfig({ env: { NODE_PORT: "8001" } }).port, 8001);
    assert.equal(app.loadConfig({ env: { NODE_PORT: "08001" } }).port, 8001);
    // The grammar tolerates an explicit sign; the range check is what refuses a
    // negative. All three implementations share the pattern /^[+-]?[0-9]+$/.
    assert.equal(app.loadConfig({ env: { NODE_PORT: "+8001" } }).port, 8001);
  });

  it("refuses a port outside the range a socket can bind", () => {
    for (const port of ["-1", "65536", "70000", "999999"]) {
      assert.throws(() => app.loadConfig({ env: { NODE_PORT: port } }), RangeError, port);
    }
    assert.equal(app.loadConfig({ env: { NODE_PORT: "0" } }).port, 0);
    assert.equal(app.loadConfig({ env: { NODE_PORT: "65535" } }).port, 65535);
  });

  it("accepts single-line text and visible-ASCII targets, and nothing else", () => {
    assert.equal(app.isSingleLineText("ordinary text"), true);
    assert.equal(app.isSingleLineText(""), false);
    assert.equal(app.isSingleLineText("a\nb"), false);
    assert.equal(app.isRequestTarget("/health"), true);
    assert.equal(app.isRequestTarget("/heal th"), false, "a space ends the target");
    assert.equal(app.isRequestTarget("/health\u007f"), false, "DEL is not visible ASCII");
    assert.equal(app.isRequestTarget("/health\r\nX"), false, "CRLF cannot forge a line");
    assert.equal(app.isRequestTarget(""), false, "an empty target is not a target");
    // Rootedness is NOT this predicate's concern: the leading slash is supplied by
    // the route resolver, so an unrooted value is a valid target here and becomes
    // "/health" there. Asserted so the division of labour stays deliberate.
    assert.equal(app.isRequestTarget("health"), true);
    assert.equal(app.loadConfig({ env: { HEALTH_PATH: "health" } }).healthPath, "/health");
  });

  it("sanitises a diagnostic to one line of printable characters", () => {
    assert.equal(app.sanitizeForLog("plain"), "plain");
    assert.ok(!app.sanitizeForLog("a\r\nb").includes("\n"));
    assert.ok(!app.sanitizeForLog("a\u001bb").includes("\u001b"));
    assert.ok(!app.sanitizeForLog("a\u007fb").includes("\u007f"));
  });
});

// G. Probe target selection and answer validation. A health probe's caller can act
// only on an exit status, so the probe fails CLOSED: every doubt resolves to
// unhealthy. It dials loopback only, ignores any ambient proxy, bounds what it will
// read, and checks the whole document rather than searching the body for a hopeful
// substring.

describe("G. probe target and answer validation", () => {
  it("honours every loopback spelling exactly and silently", () => {
    const accepted = {
      "127.0.0.1": "127.0.0.1",
      "127.0.0.2": "127.0.0.2",
      "127.255.255.254": "127.255.255.254",
      localhost: "127.0.0.1",
      LOCALHOST: "127.0.0.1",
      "0.0.0.0": "127.0.0.1",
      "": "127.0.0.1",
      // "::" is the IPv6 WILDCARD, not the IPv6 loopback, so it resolves the same
      // way "0.0.0.0" does. Verified identical in all three implementations.
      "::": "127.0.0.1",
      "::1": "[::1]",
      "[::1]": "[::1]",
      "0:0:0:0:0:0:0:1": "[::1]",
      "[0:0:0:0:0:0:0:1]": "[::1]",
    };
    for (const [host, expected] of Object.entries(accepted)) {
      assert.equal(app.probeAuthority(host), expected, host);
    }
  });

  it("replaces any other value with loopback rather than dialling it", () => {
    // A probe that honoured a routable host would report the health of something
    // other than the process it was asked about.
    for (const host of ["example.com", "10.0.0.1", "128.0.0.1", "126.255.255.255", "8.8.8.8"]) {
      const { value, written } = withStderr(() => app.probeAuthority(host));
      assert.equal(value, "127.0.0.1", host);
      // Exactly one line, and it never echoes the value it refused: the host came
      // from configuration, and a configured value reaching a log line verbatim is
      // how a forged line gets written.
      assert.equal(written.split("\n").filter(Boolean).length, 1, written);
      assert.ok(!written.includes(host), `the refused value ${host} must not be echoed`);
      assert.match(written, /probe target is not loopback; probing loopback instead/);
    }
  });

  it("says nothing at all when the configured host is already loopback", () => {
    for (const host of ["127.0.0.1", "localhost", "::1", "0.0.0.0", ""]) {
      const { written } = withStderr(() => app.probeAuthority(host));
      assert.equal(written, "", `${JSON.stringify(host)} must be silent`);
    }
  });

  it("accepts a well-formed answer and refuses every departure from it", () => {
    const document = {
      name: "n",
      version: "1.1.0",
      timestamp: "2026-07-29T08:00:00Z",
      status: "UP",
    };
    const body = (value) => Buffer.from(JSON.stringify(value));
    assert.equal(app.probeRejection(200, body(document)), null);

    assert.equal(
      app.probeRejection(500, body(document)),
      "the endpoint answered status 500",
    );
    assert.equal(
      app.probeRejection(200, body({ ...document, status: "DOWN" })),
      "the status field is not the expected value",
    );
    assert.equal(
      app.probeRejection(200, body({ ...document, name: "" })),
      "the name field is not a non-empty string",
    );
    assert.equal(
      app.probeRejection(200, body({ ...document, version: "1.1" })),
      "the version field is not a three-part dotted numeric version",
    );
    assert.equal(
      app.probeRejection(200, body({ ...document, timestamp: "2026-07-29T08:00:00.500Z" })),
      "the timestamp field is not a whole-second UTC instant",
    );
  });

  it("refuses a truncated body that happens to quote the healthy fragment", () => {
    // The fail-OPEN case a substring test accepts: the bytes contain
    // `"status":"UP"` but are not a JSON document at all.
    const reason = app.probeRejection(200, Buffer.from('{"status":"UP"'));
    assert.equal(reason, "body is not the expected JSON document");
  });

  it("refuses a document whose key set or key order departs from the contract", () => {
    const wrongOrder = '{"version":"1.1.0","name":"n","timestamp":"2026-07-29T08:00:00Z","status":"UP"}';
    const extraKey = '{"name":"n","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP","extra":"x"}';
    const expected =
      'body does not carry exactly the keys ["name","version","timestamp","status"] in order';
    assert.equal(app.probeRejection(200, Buffer.from(wrongOrder)), expected);
    assert.equal(app.probeRejection(200, Buffer.from(extraKey)), expected);
  });

  it("words the key-set reason exactly as the other two implementations word it", () => {
    // Pinned as a literal because an operator greps one deployment's logs. A
    // JSON.stringify of an array would print differently in each language.
    assert.equal(
      app.probeRejection(200, Buffer.from("{}")),
      'body does not carry exactly the keys ["name","version","timestamp","status"] in order',
    );
  });

  it("refuses a repeated member name with the shared malformed-document reason", () => {
    // The reason is pinned as a literal, not merely asserted non-null. A
    // duplicate member is settled WHILE PARSING by both siblings - Python's
    // object_pairs_hook and Java's JsonReader member map - so all three word it
    // "body is not the expected JSON document" and none of them reaches a field
    // rule. Asserting only non-null would let this implementation answer "the
    // status field is not the expected value" for the row below and still pass,
    // and two operators grepping two deployments for the same fault would then
    // find different text.
    const malformed = "body is not the expected JSON document";
    const rows = [
      [
        "last value wins and disagrees",
        '{"name":"n","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP","status":"DOWN"}',
      ],
      [
        "last value wins and agrees",
        '{"name":"n","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"DOWN","status":"UP"}',
      ],
      [
        "repeated first member",
        '{"name":"n","name":"other","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP"}',
      ],
      [
        "repeat spelled with a unicode escape",
        '{"name":"n","\\u006eame":"other","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP"}',
      ],
      [
        "repeat nested inside a member value",
        '{"name":{"a":1,"a":2},"version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP"}',
      ],
      ["repeat inside an array element", '[{"a":1,"a":2}]'],
      [
        "repeated empty-string keys",
        '{"":"a","":"b","name":"n","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP"}',
      ],
    ];
    for (const [label, text] of rows) {
      assert.equal(app.probeRejection(200, Buffer.from(text, "utf8")), malformed, label);
    }
  });

  it("finds a repeated member at any nesting depth, as object_pairs_hook does", () => {
    // Python's hook and Java's reader both fire at EVERY level, so a scan that
    // only examined the top-level object would agree with them on the rows above
    // and disagree the moment a duplicate was one level down. A JSON.parse
    // reviver cannot do this either: duplicates are already collapsed before a
    // reviver ever runs, which is why the check is a separate scan of the text.
    const rest = '"version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP"}';
    for (const depth of [1, 2, 5, 50, 300]) {
      const buried = `{"name":${'{"a":'.repeat(depth)}{"b":1,"b":2}${"}".repeat(depth)},${rest}`;
      assert.equal(
        app.probeRejection(200, Buffer.from(buried, "utf8")),
        "body is not the expected JSON document",
        `a duplicate ${depth} level(s) down must still be found`,
      );
    }
  });

  it("does not mistake a comma, a brace or a quote inside a string for structure", () => {
    // The duplicate scan walks the text itself, so it has to read strings the
    // way JSON does or it would lose track of which object it is inside. These
    // are the rows that would break a naive scanner, and each one is HEALTHY.
    const healthy = (name) =>
      `{"name":${JSON.stringify(name)},"version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP"}`;
    for (const name of ['a,b', "a{b", "a}b", 'a"b', "a:b", "a\\b", "{\"a\":1,\"a\":2}"]) {
      assert.equal(app.probeRejection(200, Buffer.from(healthy(name), "utf8")), null, name);
    }
  });

  it("refuses a body that is not valid UTF-8, rather than substituting U+FFFD", () => {
    // `buffer.toString("utf8")` is LOSSY: it replaces every ill-formed sequence
    // with U+FFFD and never reports one. A peer answering with a schema-shaped
    // document carrying one bad byte inside `name` therefore parses cleanly and
    // satisfies every field rule, so a lossy reader grades it HEALTHY - while
    // Python raises UnicodeDecodeError and Java's CodingErrorAction.REPORT throws
    // on the same bytes. Two implementations refusing and one accepting is not one
    // contract, which is why the decode here is fatal and this test holds it so.
    //
    // The fixtures are raw Buffers because no JavaScript string can express an
    // ill-formed sequence: a string that has already been decoded has already
    // lost the evidence.
    const rest = Buffer.from(
      '","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP"}',
      "utf8",
    );
    const open = Buffer.from('{"name":"', "utf8");
    const rows = [
      ["a truncated two-byte sequence", Buffer.from([0xc3, 0x28])],
      ["a lone continuation byte", Buffer.from([0x80])],
      ["a truncated three-byte sequence", Buffer.from([0xe2, 0x82])],
      ["a truncated four-byte sequence", Buffer.from([0xf0, 0x9f, 0x92])],
      ["an unpaired high surrogate encoded directly", Buffer.from([0xed, 0xa0, 0x80])],
      ["an overlong encoding of U+002F", Buffer.from([0xc0, 0xaf])],
      ["a byte no UTF-8 sequence may contain", Buffer.from([0xfe])],
      ["a five-byte sequence UTF-8 no longer permits", Buffer.from([0xf8, 0x88, 0x80, 0x80, 0x80])],
      ["a value past the last code point", Buffer.from([0xf5, 0x80, 0x80, 0x80])],
    ];

    for (const [label, bad] of rows) {
      const body = Buffer.concat([open, bad, rest]);
      assert.equal(
        app.probeRejection(200, body),
        "body is not the expected JSON document",
        `${label} must be refused, not replacement-decoded`,
      );

      // Proves the fixture is a genuine guard rather than a body any decoder would
      // reject: read LOSSILY, these same bytes are a perfectly valid health
      // document that satisfies every field rule - which is the verdict a lossy
      // reader returns, and the assertion above is what forbids it.
      const lossy = JSON.parse(body.toString("utf8"));
      assert.deepStrictEqual(Object.keys(lossy), EXPECTED_KEYS, label);
      assert.equal(lossy.status, EXPECTED_STATUS, label);
      assert.ok(lossy.name.length > 0, label);
      assert.ok(lossy.name.includes("\uFFFD"), `${label} must decode lossily to U+FFFD`);
    }
  });

  it("keeps a byte-order mark, so a body opening with one stays malformed", () => {
    // The shared decoder is built with `ignoreBOM: true`, which reads backwards:
    // it KEEPS the mark rather than ignoring it. Both call sites need that -
    // java.util.Properties.load keeps it, and JSON.parse refuses a leading
    // U+FEFF in all three runtimes, so keeping it is what holds probe parity.
    const document = '{"name":"n","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP"}';
    const withMark = Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(document, "utf8")]);
    assert.equal(app.probeRejection(200, withMark), "body is not the expected JSON document");
    // Without the mark the very same document is healthy, which isolates the mark
    // as the only reason for the refusal.
    assert.equal(app.probeRejection(200, Buffer.from(document, "utf8")), null);
  });

  it("refuses a body over the ceiling before considering anything else", () => {
    // Size is checked FIRST: an oversized body is refused on its size and never
    // parsed, so the ceiling bounds the work as well as the memory.
    const oversized = Buffer.alloc(app.MAX_PROBE_BODY_BYTES + 1, 0x20);
    assert.equal(
      app.probeRejection(200, oversized),
      `body exceeds the probe limit of ${app.MAX_PROBE_BODY_BYTES} bytes`,
    );
    // Size outranks status, which is what makes the ordering observable.
    assert.match(app.probeRejection(500, oversized), /^body exceeds the probe limit/);
  });

  it("treats a body exactly at the ceiling as readable", () => {
    const document = {
      name: "n",
      version: "1.1.0",
      timestamp: "2026-07-29T08:00:00Z",
      status: "UP",
    };
    const rendered = JSON.stringify(document);
    const padded = Buffer.from(rendered);
    assert.ok(padded.length <= app.MAX_PROBE_BODY_BYTES);
    assert.equal(app.probeRejection(200, padded), null);
  });

  it("refuses a body that is valid JSON but not an object, with the shared reason", () => {
    // Every reason below was produced by running the same bytes through all
    // three implementations and diffing the output, so this table is a
    // transcription of a measured three-way agreement rather than a description
    // of what this one implementation happens to say. Pinned as literals because
    // an operator greps one deployment's logs, not one language's.
    const notAnObject = "body is not a JSON object and carries no status field";
    for (const text of ["[]", '"UP"', "42", "null", "true", "false"]) {
      assert.equal(app.probeRejection(200, Buffer.from(text, "utf8")), notAnObject, text);
    }
    // An empty body never becomes a document at all, so it is settled one step
    // earlier - while parsing - and carries the malformed reason instead.
    assert.equal(
      app.probeRejection(200, Buffer.alloc(0)),
      "body is not the expected JSON document",
    );
  });

  it("words every field rejection exactly as the other two implementations word it", () => {
    const rest = '"version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP"}';
    const rows = [
      ["numeric name", `{"name":1,${rest}`, "the name field is not a non-empty string"],
      ["null name", `{"name":null,${rest}`, "the name field is not a non-empty string"],
      ["boolean name", `{"name":true,${rest}`, "the name field is not a non-empty string"],
      ["object name", `{"name":{},${rest}`, "the name field is not a non-empty string"],
      [
        "numeric status",
        '{"name":"n","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":1}',
        "the status field is not the expected value",
      ],
      [
        "numeric version",
        '{"name":"n","version":1,"timestamp":"2026-07-29T08:00:00Z","status":"UP"}',
        "the version field is not a three-part dotted numeric version",
      ],
      [
        "numeric timestamp",
        '{"name":"n","version":"1.1.0","timestamp":1,"status":"UP"}',
        "the timestamp field is not a whole-second UTC instant",
      ],
    ];
    for (const [label, text, expected] of rows) {
      assert.equal(app.probeRejection(200, Buffer.from(text, "utf8")), expected, label);
    }

    // A name of nothing but spaces is ACCEPTED by all three: the rule is
    // non-empty, not non-blank. Asserted so the shared rule cannot be tightened
    // in one implementation alone, which would make two of them disagree about a
    // deployment whose configured name is a space.
    assert.equal(
      app.probeRejection(200, Buffer.from(`{"name":"   ",${rest}`, "utf8")),
      null,
    );
  });
});

// H. Listener budgets and connection reuse. Node's defaults leave a half-sent
// request holding a socket for a minute and a complete one for five, far longer
// than a health endpoint needs and long enough to be worth a peer's while, so the
// budgets below are set explicitly. The last group asserts the seam every
// implementation shares: a refused request that arrives WITH a body must not
// corrupt the next request on the same connection.

describe("H. listener budgets and connection reuse", () => {
  let server;
  let port;

  before(async () => {
    server = app.createServer(fixedConfig());
    port = await listen(server);
  });

  after(async () => {
    await closeServer(server);
    assert.equal(server.listening, false, "the test server must not be left listening");
  });

  it("bounds the headers, the whole request, the socket and the keep-alive wait", () => {
    // Every one of these is shorter than the Node default it replaces. The request
    // budget is the number app.py applies to its own body drain, so one value
    // governs the same behaviour in both implementations.
    assert.equal(server.headersTimeout, 10000, "headersTimeout");
    assert.equal(server.requestTimeout, 15000, "requestTimeout");
    assert.equal(server.timeout, 30000, "socket timeout");
    assert.equal(server.keepAliveTimeout, 5000, "keepAliveTimeout");
    assert.ok(
      server.headersTimeout < server.requestTimeout,
      "the headers budget must be the tighter of the two, or it never fires",
    );
  });

  /**
   * Sends two requests down ONE connection and resolves with everything received.
   *
   * The gap is long enough that the server has certainly answered the first
   * before the second arrives, so this observes reuse of an idle connection
   * rather than a pipelined burst.
   */
  function reusedConnection(first, second) {
    return new Promise((resolve, reject) => {
      const socket = net.connect(port, LOOPBACK, () => {
        socket.write(first);
        setTimeout(() => socket.write(second), 300);
      });
      const parts = [];
      socket.on("data", (chunk) => parts.push(chunk));
      socket.on("end", () => resolve(Buffer.concat(parts).toString("utf8")));
      socket.on("error", reject);
      socket.setTimeout(REQUEST_TIMEOUT_MS, () => {
        socket.destroy();
        resolve(Buffer.concat(parts).toString("utf8"));
      });
    });
  }

  const REFUSED_WITH_BODY =
    "POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\nxyz";
  const FOLLOWING_GET =
    "GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n";

  it("does not let a refused request's body corrupt the next request", async () => {
    // Left unread, those three bytes are consumed as the start of the next request
    // line: the method parses as `xyzGET`, and the legitimate request behind it is
    // never answered. Node dumps an unconsumed request itself once the response
    // finishes; app.py drains explicitly and User.java installs a request-time
    // bound. This pins the behaviour all three share.
    const received = await reusedConnection(REFUSED_WITH_BODY, FOLLOWING_GET);
    assert.equal(received.split("HTTP/1.1 ").length - 1, 2, received);
    assert.match(received, /405 Method Not Allowed/);
    assert.match(received, /200 OK/);
    assert.match(received, /"status":"UP"/);
  });

  it("never parses a leftover byte as a request line", async () => {
    const received = await reusedConnection(REFUSED_WITH_BODY, FOLLOWING_GET);
    assert.ok(!received.includes("501"), "no unsupported-method answer");
    assert.ok(!received.toLowerCase().includes("<html"), "no HTML error body");
    assert.ok(!/^Server:/im.test(received), "no Server header");
    assert.ok(!/^Date:/im.test(received), "no Date header");
  });

  it("answers a body on the health route itself and keeps the connection usable", async () => {
    const received = await reusedConnection(
      "GET /health HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\nxyz",
      FOLLOWING_GET,
    );
    assert.equal(received.split("HTTP/1.1 200 OK").length - 1, 2, received);
  });

  it("writes no diagnostic while serving a drained exchange", async () => {
    // A slow or sloppy client is routine, not news. A mode of this program that
    // writes an unexpected diagnostic is a mode that fails a clean-output check.
    const original = process.stderr.write;
    let written = "";
    process.stderr.write = (chunk) => {
      written += chunk;
      return true;
    };
    try {
      await reusedConnection(REFUSED_WITH_BODY, FOLLOWING_GET);
    } finally {
      process.stderr.write = original;
    }
    assert.equal(written, "");
  });
});

describe("E. configuration precedence", () => {
  /** A properties file whose every value differs from the built-in defaults. */
  const fileValues = [
    "# a comment line, which must be ignored",
    "! the alternative comment marker, also ignored",
    "",
    "app.name=name-from-file",
    "app.version=9.8.7",
    "health.path=/from-file",
    "app.host=127.0.0.1",
    "node.port=19001",
  ];

  let file;
  let missing;

  before(() => {
    file = writePropertiesFile(fileValues);
    missing = missingPropertiesPath();
  });

  it("falls back to the built-in defaults when neither source supplies a value", () => {
    // A missing properties file is not an error: the defaults are a complete
    // configuration on their own, so the application still serves a valid
    // payload rather than refusing to start.
    //
    // What is under test here is the FALL-THROUGH, not the version string, so
    // the resolved values are compared against the published defaults table -
    // that comparison is precisely the claim "resolution reached the built-in
    // default". Restating the version as a literal here would instead pin the
    // product version in a sixth place, so a legitimate release bump would
    // fail this test for a reason unrelated to correctness. Version agreement
    // is a separate concern with its own dedicated gate. The value is still
    // constrained, by the independent format assertions below.
    const config = app.loadConfig({ file: missing, env: {} });
    assert.equal(config.name, app.DEFAULTS["app.name"]);
    assert.equal(config.version, app.DEFAULTS["app.version"]);
    assert.equal(config.healthPath, app.DEFAULTS["health.path"]);
    assert.equal(config.host, app.DEFAULTS["app.host"]);
    assert.equal(config.port, Number(app.DEFAULTS["node.port"]));

    // The defaults must themselves satisfy the response contract, otherwise a
    // deployment with no configuration at all would serve an invalid payload.
    assert.ok(config.name.length > 0, "the default name must not be empty");
    assert.match(config.version, VERSION_PATTERN);
    assert.equal(config.healthPath, "/health");
    assert.equal(config.port, CONFIGURED_DEFAULT_PORT);
  });

  it("lets the properties file override every built-in default", () => {
    const config = app.loadConfig({ file, env: {} });
    assert.equal(config.name, "name-from-file");
    assert.equal(config.version, "9.8.7");
    assert.equal(config.healthPath, "/from-file");
    assert.equal(config.host, "127.0.0.1");
    assert.equal(config.port, 19001);
  });

  it("lets an environment variable override the properties file", () => {
    // The top rung of the precedence chain. The environment map is injected, so
    // this asserts the real resolution order without touching process.env.
    const config = app.loadConfig({
      file,
      env: {
        APP_NAME: "name-from-env",
        APP_VERSION: "1.2.3",
        HEALTH_PATH: "/from-env",
        APP_HOST: "0.0.0.0",
        NODE_PORT: "19002",
      },
    });
    assert.equal(config.name, "name-from-env");
    assert.equal(config.version, "1.2.3");
    assert.equal(config.healthPath, "/from-env");
    assert.equal(config.host, "0.0.0.0");
    assert.equal(config.port, 19002);
  });

  it("lets the universal PORT outrank NODE_PORT and the properties file", () => {
    // PORT has one extra rung above the rest of the chain so that a
    // single-application container can be told which port to bind with the one
    // variable every platform already sets.
    const withBoth = app.loadConfig({ file, env: { PORT: "19500", NODE_PORT: "19600" } });
    assert.equal(withBoth.port, 19500);

    const portOnly = app.loadConfig({ file, env: { PORT: "19700" } });
    assert.equal(portOnly.port, 19700);

    const nodePortOnly = app.loadConfig({ file, env: { NODE_PORT: "19800" } });
    assert.equal(nodePortOnly.port, 19800);

    // With neither variable the file still wins over the default.
    assert.equal(app.loadConfig({ file, env: {} }).port, 19001);
  });

  it("treats only an empty environment variable as absent, never a supplied one", () => {
    // The contract requires a non-empty name and a dotted version, so a variable
    // exported as the empty string must fall through to the next source instead
    // of producing a payload that violates the contract.
    const emptied = app.loadConfig({ file, env: { APP_NAME: "", APP_VERSION: "" } });
    assert.equal(emptied.name, "name-from-file");
    assert.equal(emptied.version, "9.8.7");

    // A whitespace-only variable, by contrast, WAS supplied, so it wins its rung
    // and is used exactly as supplied. That is not a nicety: app.py resolves with
    // `if override:` and User.java with `!fromEnvironment.isEmpty()`, so both of
    // them treat "   " as a supplied value. Normalising it away here would make
    // this the one implementation of the three that answers differently for the
    // same environment, and - because the same helper feeds the port - would let
    // a supplied but unusable PORT be silently replaced by the default instead of
    // refused. Empty means absent; anything else means the operator said so.
    const spaced = app.loadConfig({ file, env: { APP_NAME: "   ", APP_VERSION: " 1.2.3 " } });
    assert.equal(spaced.name, "   ", "a whitespace-only name is supplied, not absent");
    assert.equal(spaced.version, " 1.2.3 ", "a supplied value is not trimmed on the way through");
  });

  it("refuses a supplied whitespace-only port instead of silently defaulting", () => {
    // The companion of the assertion above, and the reason it matters. A
    // whitespace-only PORT reaches resolvePort as a supplied value, trims to the
    // empty string, fails the digit test and is refused - naming the value the
    // operator actually exported. Falling through to 8001 here would leave a
    // healthy-looking process listening on a port nobody is watching, which is
    // exactly what app.py (`int(str(value).strip())` raising ValueError) and
    // User.java (`Integer.parseInt(raw.trim())` raising NumberFormatException)
    // refuse to do.
    assert.throws(
      () => app.loadConfig({ file, env: { PORT: "   " } }),
      { name: "RangeError", message: /expected an ASCII decimal integer/ },
      "a whitespace-only PORT must be refused, not replaced by the default",
    );
    assert.throws(
      () => app.loadConfig({ file: missing, env: { NODE_PORT: "\t" } }),
      { name: "RangeError", message: /expected an ASCII decimal integer/ },
      "a whitespace-only NODE_PORT must be refused just as PORT is",
    );

    // Surrounding whitespace around a real number is still tolerated, because
    // both siblings trim before parsing. Trimming for the parse and trimming for
    // the presence test are different decisions, and only the first is correct.
    assert.equal(
      app.loadConfig({ file: missing, env: { PORT: " 8080 " } }).port,
      8080,
      "a padded numeric port must parse, matching parseInt(raw.trim()) and int(v.strip())",
    );
  });

  it("adds a missing leading slash to a configured health path", () => {
    // So a value written as "healthz" still matches the target "/healthz" that
    // a client actually sends.
    assert.equal(app.loadConfig({ file: missing, env: { HEALTH_PATH: "healthz" } }).healthPath, "/healthz");
    assert.equal(app.loadConfig({ file: missing, env: { HEALTH_PATH: "/healthz" } }).healthPath, "/healthz");
  });

  it("refuses an invalid port rather than falling through to the next source", () => {
    // Fail closed. Listening on NaN would silently bind an arbitrary ephemeral
    // port, and quietly serving the documented default is barely better: an
    // operator who mistypes PORT asked for a specific port, and substituting a
    // different one leaves every probe aimed at the port they asked for pointing
    // at nothing while the process reports itself up. So the winning candidate is
    // rejected outright instead of being skipped.
    //
    // Each rejection is asserted to NAME the offending value, because a refusal an
    // operator cannot trace back to the setting that carried it is only half a
    // diagnostic. All three implementations reject the same grammar, which is what
    // keeps their configuration behaviour identical.
    assert.throws(
      () => app.loadConfig({ file, env: { PORT: "not-a-port" } }),
      { name: "RangeError", message: /not-a-port/ },
      "a non-numeric port must be refused, naming the value",
    );
    assert.throws(
      () => app.loadConfig({ file: missing, env: { PORT: "70000" } }),
      { name: "RangeError", message: /70000/ },
      "a port above the legal range must be refused, naming the value",
    );
    assert.throws(
      () => app.loadConfig({ file: missing, env: { PORT: "-1" } }),
      { name: "RangeError", message: /-1/ },
      "a negative port must be refused, naming the value",
    );
    assert.throws(
      () => app.loadConfig({ file: missing, env: { PORT: "0x50" } }),
      { name: "RangeError", message: /0x50/ },
      "a hexadecimal typo must be refused rather than read as 80",
    );

    // A legal value still resolves, so the rejections above are about legality
    // and not about the variable being ignored; and the file port is still what
    // resolves when no override is present, so the highest rung of the chain is
    // the only thing the rejections exercise.
    assert.equal(app.loadConfig({ file: missing, env: { PORT: "8080" } }).port, 8080);
    assert.equal(app.loadConfig({ file, env: {} }).port, 19001);
    assert.equal(app.loadConfig({ file: missing, env: {} }).port, CONFIGURED_DEFAULT_PORT);
  });

  it("returns a frozen configuration, so a caller cannot mutate shared state", () => {
    const config = app.loadConfig({ file: missing, env: {} });
    assert.ok(Object.isFrozen(config), "the resolved configuration must be frozen");
    assert.ok(Object.isFrozen(app.DEFAULTS), "the defaults table must be frozen");
    assert.ok(Object.isFrozen(app.ENV_KEYS), "the environment key table must be frozen");
  });

  it("reads the properties file that ships beside the module by default", () => {
    // With no file option the loader must resolve the shared cross-language
    // source of truth relative to the module, not to the process working
    // directory, so a container WORKDIR change still finds it.
    assert.ok(path.isAbsolute(app.CONFIG_FILE), "the config path must be absolute");
    assert.equal(path.basename(app.CONFIG_FILE), "app.config.properties");
    assert.equal(app.CONFIG_FILE, path.join(__dirname, "app.config.properties"));

    // The default path must not be a dangling one: the shared source of truth
    // has to be present where the loader looks for it.
    assert.ok(fs.existsSync(app.CONFIG_FILE), "the shipped properties file must exist");

    // The resolved values are compared against the shipped file's OWN parsed
    // content rather than against restated literals. Two reasons, and the
    // second is the important one:
    //
    //   1. A literal here would pin the application name and version in this
    //      test file, adding a drift site outside the single source of truth.
    //
    //   2. More seriously, a literal could not fail for the reason this test
    //      name claims. The shipped file currently restates the built-in
    //      defaults value for value, so "read the file" and "fell back to the
    //      defaults" produce identical strings - a value assertion cannot tell
    //      them apart. The discriminating power therefore lives in the
    //      module-relative path assertions above and in this agreement check,
    //      which fails if the loader ever parses that file incorrectly or
    //      resolves a different one.
    const shipped = app.parseProperties(fs.readFileSync(app.CONFIG_FILE, "utf8"));
    const config = app.loadConfig({ env: {} });
    assert.equal(config.name, shipped["app.name"]);
    assert.equal(config.version, shipped["app.version"]);
    assert.equal(config.healthPath, shipped["health.path"]);
    assert.equal(config.host, shipped["app.host"]);
    assert.equal(config.port, Number(shipped["node.port"]));

    // Independent format constraints, so the file cannot ship a value that
    // would violate the response contract.
    assert.ok(config.name.length > 0, "the configured name must not be empty");
    assert.match(config.version, VERSION_PATTERN);
  });

  it("parses the shared grammar fixtures exactly as java.util.Properties does", () => {
    // The cross-language contract: one file must mean one thing in three
    // languages. Every expectation in SHARED_PROPERTIES_FIXTURES came out of
    // java.util.Properties.load, which is how User.java reads the same file, and
    // the identical table - same labels, same text, same expectations - appears
    // in test_app.py and UserTest.java. A suite that asserted what its own parser
    // happens to do could not detect the divergence it exists to prevent, which is
    // why every expectation here is the reference implementation's and not this
    // one's.
    for (const [label, text, expected] of SHARED_PROPERTIES_FIXTURES) {
      const parsed = app.parseProperties(text);
      assert.deepEqual(
        Object.keys(parsed).sort(),
        Object.keys(expected).sort(),
        `${label}: key set`,
      );
      for (const key of Object.keys(expected)) {
        assert.equal(parsed[key], expected[key], `${label}: value of ${JSON.stringify(key)}`);
      }
    }
  });

  it("refuses a malformed unicode escape rather than reading it literally", () => {
    // The one condition under which Properties.load rejects a document outright.
    for (const [label, text] of SHARED_MALFORMED_PROPERTIES) {
      assert.throws(() => app.parseProperties(text), RangeError, label);
    }
  });

  it("warns once and uses the defaults when the file is malformed", () => {
    const file = writePropertiesFile(["app.name=x\\u12"]);
    const { value, written } = withStderr(() => app.loadConfig({ file, env: {} }));
    assert.equal(value.name, app.DEFAULTS["app.name"]);
    assert.equal(written, `index.js: ${MALFORMED_CONFIG_WARNING}\n`);
  });

  it("warns once and uses the defaults when the file cannot be read", () => {
    // A directory standing where the file should be is the portable way to make
    // a read fail: a permission bit does not stop the root user a container build
    // commonly runs as, so it would make the test pass for the wrong reason.
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "health-unreadable-"));
    temporaryDirectories.push(directory);
    const { value, written } = withStderr(() => app.loadConfig({ file: directory, env: {} }));
    assert.equal(value.name, app.DEFAULTS["app.name"]);
    assert.equal(written, `index.js: ${UNREADABLE_CONFIG_WARNING}\n`);
  });

  it("treats bytes that are not UTF-8 as a read failure, never as U+FFFD", () => {
    // `fs.readFileSync(file, "utf8")` would substitute U+FFFD and report nothing,
    // turning a file Java refuses to read at all into a configuration full of
    // replacement characters - and that configuration reaches the published name
    // field, so the read here is fatal instead.
    const file = writePropertiesBytes(Buffer.from([0x61, 0x3d, 0xc3, 0x28, 0x0a]));
    const { value, written } = withStderr(() => app.loadConfig({ file, env: {} }));
    assert.equal(value.name, app.DEFAULTS["app.name"]);
    assert.equal(written, `index.js: ${UNREADABLE_CONFIG_WARNING}\n`);
  });

  it("does not mutate the real process environment", () => {
    // Precedence is exercised exclusively through injected maps. A suite that
    // wrote to process.env would leak state into every later test in the file
    // and into anything the runner spawns afterwards.
    for (const key of CONFIG_ENV_KEYS) {
      assert.equal(process.env[key], ENV_SNAPSHOT[key], `${key} must be untouched by the suite`);
    }
  });
});

// I. Mode dispatch through the real entry point. What is under test is the WIRING
// at the foot of index.js: that `--serve` reaches the listener, that `--probe`
// reaches the self-check, and that the self-check's verdict becomes the process
// exit status an orchestrator reads. None of that can be established by calling
// serve() or probe() directly - those tests are above and would pass even if the
// dispatcher ignored both flags - and none of it in process, because the exit
// status IS the contract and only a real child has one.
//
// Every child runs with the health variables stripped from its environment and this
// suite's own values put in, so a developer with HEALTH_PATH exported cannot fail
// the suite; and on a port the kernel has just confirmed free, so the application
// already running on its configured port cannot fail it either.
//
// Standard output is asserted EMPTY for both modes: it carries this program's legacy
// output and is hashed by a committed baseline, so a mode that wrote one line to it
// would break that hash while looking perfectly healthy.

describe("I. mode dispatch through the real entry point", () => {
  /** Every child started here, so the hook below can end one a test left running. */
  const children = [];
  /** Every hostile server started here, closed by the same hook. */
  const servers = [];

  after(async () => {
    for (const child of children) {
      if (child.exitCode === null && child.signalCode === null) {
        child.kill();
      }
    }
    for (const server of servers) {
      await new Promise((resolve) => (server.listening ? server.close(() => resolve()) : resolve()));
    }
  });

  /**
   * The ambient environment with every health variable removed, then `overrides`.
   */
  function childEnv(overrides) {
    const environment = { ...process.env };
    for (const key of CONFIG_ENV_KEYS) {
      delete environment[key];
    }
    return { ...environment, ...overrides };
  }

  /**
   * Starts a child that is expected to keep running, streams captured in strings.
   *
   * @returns {{child: import("node:child_process").ChildProcess,
   *            output: {stdout: string, stderr: string}}} The child and its streams.
   */
  function startChild(args, environment) {
    const child = spawn(process.execPath, args, { cwd: __dirname, env: environment });
    children.push(child);
    const output = { stdout: "", stderr: "" };
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => (output.stdout += chunk));
    child.stderr.on("data", (chunk) => (output.stderr += chunk));
    return { child, output };
  }

  /**
   * Runs a child that ends on its own and resolves its whole outcome.
   *
   * Asynchronous on purpose. spawnSync would block this process's event loop,
   * which would stop an in-process hostile server from ever accepting the
   * child's connection - measured: every such probe timed out instead of
   * reaching the answer under test.
   *
   * @returns {Promise<{status: number|null, stdout: string, stderr: string}>} Outcome.
   */
  function runChild(args, environment) {
    return new Promise((resolve, reject) => {
      const { child, output } = startChild(args, environment);
      child.once("error", reject);
      child.once("close", (status) => resolve({ status, ...output }));
    });
  }

  /**
   * Waits until `port` accepts a connection, failing with the child's stderr if
   * the child died instead of serving.
   *
   * @returns {Promise<void>} Resolves once the port accepts.
   */
  async function awaitListener(child, output, port) {
    const deadline = Date.now() + CHILD_TIMEOUT_MS;
    while (Date.now() < deadline) {
      if (child.exitCode !== null) {
        assert.fail(`the server exited instead of serving: ${output.stderr}`);
      }
      const accepted = await new Promise((resolve) => {
        const socket = net.connect(port, LOOPBACK);
        socket.once("connect", () => {
          socket.destroy();
          resolve(true);
        });
        socket.once("error", () => resolve(false));
      });
      if (accepted) {
        return;
      }
      await pause(50);
    }
    assert.fail(`nothing accepted on port ${port} within ${CHILD_TIMEOUT_MS} ms`);
  }

  /**
   * Ends a server child and resolves once it has gone.
   */
  function stopChild(child) {
    if (child.exitCode !== null) {
      return Promise.resolve();
    }
    return new Promise((resolve) => {
      child.once("close", () => resolve());
      child.kill();
    });
  }

  /**
   * Binds a server on loopback that answers every request with `body` verbatim.
   *
   * The point is to hand the probe an answer the real implementation would never
   * produce - bytes that are not UTF-8, or a document naming a member twice -
   * which is the only way to exercise the probe's refusal path end to end.
   *
   * @param {Buffer} body Exact response body.
   * @returns {Promise<{server: import("node:http").Server, port: number}>} The server.
   */
  function serveExactBody(body) {
    return new Promise((resolve) => {
      const server = http.createServer((_request, response) => {
        response.writeHead(200, {
          "Content-Type": EXPECTED_CONTENT_TYPE,
          "Content-Length": body.length,
        });
        response.end(body);
      });
      servers.push(server);
      server.listen(0, LOOPBACK, () => resolve({ server, port: server.address().port }));
    });
  }

  it("the --serve flag serves the endpoint and writes nothing to stdout", async () => {
    const port = await unusedPort();
    const environment = childEnv({ APP_HOST: LOOPBACK, PORT: String(port) });
    const { child, output } = startChild(["index.js", "--serve"], environment);
    await awaitListener(child, output, port);

    const response = await request({ port, path: "/health" });
    assert.equal(response.status, 200);
    const payload = JSON.parse(response.body);
    assert.deepStrictEqual(Object.keys(payload), EXPECTED_KEYS);
    assert.equal(payload.status, EXPECTED_STATUS);

    await stopChild(child);
    assert.equal(output.stdout, "", "--serve must not write to standard output");
    assert.ok(!output.stdout.includes("12"), "the legacy output must not appear in serve mode");
    // The startup banner names the port actually bound and the route actually
    // answered, so it cannot promise an endpoint that does not exist.
    assert.match(output.stderr, /health endpoint listening on http:\/\/127\.0\.0\.1:\d+\/health\n$/);
    assert.ok(output.stderr.includes(String(port)));
  });

  it("the --probe flag exits 0 against the running listener, in silence", async () => {
    const port = await unusedPort();
    const environment = childEnv({ APP_HOST: LOOPBACK, PORT: String(port) });
    const { child, output } = startChild(["index.js", "--serve"], environment);
    await awaitListener(child, output, port);

    const probed = await runChild(["index.js", "--probe"], environment);
    await stopChild(child);
    assert.equal(probed.status, 0, probed.stderr);
    assert.equal(probed.stdout, "");
    assert.equal(probed.stderr, "", "a healthy probe is silent");
  });

  it("the --probe flag exits 1 when nothing is listening", async () => {
    // Fail closed. A probe's caller acts on this status and only this.
    const environment = childEnv({ APP_HOST: LOOPBACK, PORT: String(await unusedPort()) });
    const probed = await runChild(["index.js", "--probe"], environment);
    assert.equal(probed.status, 1);
    assert.equal(probed.stdout, "");
    assert.equal(probed.stderr.split("\n").filter(Boolean).length, 1, probed.stderr);
    assert.match(probed.stderr, /probe could not reach/);
  });

  it("the --probe flag follows the configured health path", async () => {
    // Both modes read the same configuration, or the probe grades a stranger.
    const port = await unusedPort();
    const served = childEnv({ APP_HOST: LOOPBACK, PORT: String(port), HEALTH_PATH: "/healthz" });
    const { child, output } = startChild(["index.js", "--serve"], served);
    await awaitListener(child, output, port);

    const agreeing = await runChild(["index.js", "--probe"], served);
    const disagreeing = await runChild(
      ["index.js", "--probe"],
      childEnv({ APP_HOST: LOOPBACK, PORT: String(port), HEALTH_PATH: "/health" }),
    );
    await stopChild(child);
    assert.equal(agreeing.status, 0, agreeing.stderr);
    assert.equal(disagreeing.status, 1);
    assert.match(disagreeing.stderr, /the endpoint answered status 404/);
  });

  it("the --probe flag exits 1 when the answer is not valid UTF-8", async () => {
    // D1, END TO END. The in-process assertions above prove probeRejection refuses
    // these bytes; this proves the refusal reaches the exit status a health probe's
    // caller reads. A lossy decode would turn one bad byte into U+FFFD, the document
    // would then satisfy every field rule, and the child would exit 0.
    const malformed = Buffer.concat([
      Buffer.from('{"name":"', "utf8"),
      Buffer.from([0xc3, 0x28]),
      Buffer.from('","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP"}', "utf8"),
    ]);
    const { server, port } = await serveExactBody(malformed);
    const probed = await runChild(
      ["index.js", "--probe"],
      childEnv({ APP_HOST: LOOPBACK, PORT: String(port) }),
    );
    await new Promise((resolve) => server.close(() => resolve()));

    assert.equal(probed.status, 1, "a body that is not UTF-8 must probe as unhealthy");
    assert.equal(probed.stdout, "");
    assert.equal(
      probed.stderr,
      "index.js: probe rejected: body is not the expected JSON document\n",
    );
  });

  it("the --probe flag exits 1 when the answer names a member twice", async () => {
    // D8, END TO END, with the reason pinned: a duplicate is settled while parsing,
    // so the child must report the malformed-document reason and not a field reason.
    const duplicated = Buffer.from(
      '{"name":"n","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP","status":"DOWN"}',
      "utf8",
    );
    const { server, port } = await serveExactBody(duplicated);
    const probed = await runChild(
      ["index.js", "--probe"],
      childEnv({ APP_HOST: LOOPBACK, PORT: String(port) }),
    );
    await new Promise((resolve) => server.close(() => resolve()));

    assert.equal(probed.status, 1);
    assert.equal(
      probed.stderr,
      "index.js: probe rejected: body is not the expected JSON document\n",
    );
  });

  it("the --probe flag exits 0 against a well-formed answer from the same harness", async () => {
    // The control for the two rows above: the hostile harness itself is not what
    // makes them fail. Same server, same code path, a well-formed body, exit 0.
    const wellFormed = Buffer.from(
      '{"name":"n","version":"1.1.0","timestamp":"2026-07-29T08:00:00Z","status":"UP"}',
      "utf8",
    );
    const { server, port } = await serveExactBody(wellFormed);
    const probed = await runChild(
      ["index.js", "--probe"],
      childEnv({ APP_HOST: LOOPBACK, PORT: String(port) }),
    );
    await new Promise((resolve) => server.close(() => resolve()));

    assert.equal(probed.status, 0, probed.stderr);
    assert.equal(probed.stderr, "");
  });

  it("the --serve flag fails closed on an unusable port", async () => {
    // An orchestrator must never see a success status from a dead listener.
    const probed = await runChild(
      ["index.js", "--serve"],
      childEnv({ APP_HOST: LOOPBACK, PORT: "not-a-port" }),
    );
    assert.equal(probed.status, 1);
    assert.equal(probed.stdout, "");
    assert.match(probed.stderr, /cannot start the health server/);
    assert.equal(probed.stderr.split("\n").filter(Boolean).length, 1, probed.stderr);
  });

  it("the --serve flag refuses an unpublishable configuration before binding", async () => {
    // Validation happens before the bind, so nothing is ever served from it.
    const probed = await runChild(
      ["index.js", "--serve"],
      childEnv({ APP_HOST: LOOPBACK, APP_VERSION: "one.two" }),
    );
    assert.equal(probed.status, 1);
    assert.equal(probed.stdout, "");
    assert.match(probed.stderr, /app\.version/);
  });

  it("an unrecognised flag still produces the legacy output and exits 0", async () => {
    // The dispatcher's default branch is the original program. A flag it does not
    // know must fall through to it rather than being treated as a mode.
    const probed = await runChild(["index.js", "--nonsense"], childEnv({}));
    assert.equal(probed.status, 0, probed.stderr);
    assert.equal(probed.stdout, LEGACY_STDOUT);
    assert.equal(Buffer.byteLength(probed.stdout), LEGACY_BYTE_LENGTH);
    assert.equal(probed.stderr, "");
  });
});
