/**
 * index.test.js - unit and integration tests for the JavaScript health endpoint.
 *
 * THE FILENAME IS A CORRECTNESS REQUIREMENT, NOT A STYLE CHOICE.
 * Node's built-in runner collects files whose name carries a `.test.js` or
 * `_test.js` suffix. A file named `index.spec.js` is silently ignored - the
 * runner collects zero tests and still exits 0, which is a suite that reports
 * success while executing nothing. That behaviour was reproduced against this
 * repository before this file was written: with no test file present,
 * `node --test --test-reporter=tap` printed "1..0 / # tests 0 / # pass 0" and
 * exited 0. It is the whole reason the CI gate asserts a collected count
 * greater than zero and requests the TAP reporter explicitly (the default
 * reporter prints decorative glyphs rather than a greppable count line).
 * Do not rename this file.
 *
 * WHAT IS COVERED
 *   A. Preserved legacy behaviour - `add`, the public export surface, the
 *      absence of an import-time side effect, and the byte-exact default stdout.
 *   B. The frozen health payload contract - field set, field ORDER, value
 *      formats, and the compact rendering.
 *   C. Path normalisation, asserted directly as a pure function.
 *   D. Routing, status codes and response headers over a live server bound to
 *      an ephemeral port.
 *   E. Configuration precedence: environment > properties file > built-in
 *      default, with `PORT` outranking `NODE_PORT`.
 *
 * ENGINEERING STANDARDS THIS FILE IS HELD TO (no user-specified rules exist for
 * this project; these are the enterprise standards the plan declares binding):
 *   S1 Backward compatibility is non-negotiable. The preserved `add` behaviour,
 *      the byte-exact 15-byte default stdout and the absence of an import-time
 *      side effect are all asserted here, so a regression fails a gate rather
 *      than reaching a reviewer.
 *   S2 Repository conventions are preserved: CommonJS `require` (the manifest
 *      declares `"type": "commonjs"`), two-space indentation, semicolons, and a
 *      flat root sibling rather than a `tests/` subdirectory.
 *   S3 Zero-dependency self-containment: every import below is a Node built-in.
 *      No jest, mocha, supertest or chai; no lockfile; no node_modules.
 *   S6 Fail closed, and assert non-deterministic values by FORMAT, never by
 *      value. `timestamp` is the only non-deterministic field in the payload and
 *      is matched against a regular expression only, so no gate is time-flaky.
 *   S8 Least disclosure on a network-reachable surface: the absence of the
 *      `Date` and `Server` headers is asserted, because header suppression is
 *      easily regressed by a well-meaning refactor.
 *
 * PORTABILITY: only long-stable built-ins are used, because the committed Node
 * pin and the locally installed runtime are not the same minor line. Nothing
 * here depends on an API newer than the manifest's `engines.node` floor.
 */

const { describe, it, before, after } = require("node:test");
const assert = require("node:assert/strict");
const { execFileSync, spawnSync } = require("node:child_process");
const fs = require("node:fs");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");

// The module under test. Requiring it must be free of side effects; group A
// proves that in a child process, where a stray write cannot be missed.
const app = require("./index.js");

/* -------------------------------------------------------------------------- *
 * Frozen contract constants
 *
 * These are duplicated here on purpose rather than imported from the module.
 * A test that imports the value it is asserting proves only self-consistency:
 * if the implementation's status literal changed to "DOWN", an imported
 * constant would change with it and the assertion would still pass. Spelling
 * the expected values out independently is what makes these real assertions.
 * -------------------------------------------------------------------------- */

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

/* -------------------------------------------------------------------------- *
 * Child-process helpers - used to observe stdout exactly as a shell would
 * -------------------------------------------------------------------------- */

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
 *
 * @param {string[]} args Arguments passed to the Node binary.
 * @returns {string} The child's stdout, decoded as UTF-8.
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
 * @param {string[]} args Arguments passed to the Node binary.
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

/* -------------------------------------------------------------------------- *
 * Server and HTTP helpers
 * -------------------------------------------------------------------------- */

/**
 * Starts a server on an ephemeral port on loopback and resolves the real port.
 *
 * Port 0 asks the kernel for any free port, which is what makes this suite safe
 * to run while the application itself is bound to its configured port. The
 * bound port is read back from server.address() rather than assumed.
 *
 * @param {import("node:http").Server} server An unstarted server.
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
 * @param {import("node:http").Server} server The server to close.
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
 * @param {{headers: Record<string, string>}} response A response.
 * @param {string} name Header name in any casing.
 * @returns {string|undefined} The header value, or undefined when absent.
 */
function headerValue(response, name) {
  return response.headers[name.toLowerCase()];
}

/**
 * Reports whether a header was present on the wire, case-insensitively.
 *
 * @param {{headerNames: string[]}} response A response.
 * @param {string} name Header name in any casing.
 * @returns {boolean} True when the wire carried the header.
 */
function hasHeader(response, name) {
  return response.headerNames.includes(name.toLowerCase());
}

/* -------------------------------------------------------------------------- *
 * Temporary properties files - used to exercise configuration precedence
 * -------------------------------------------------------------------------- */

/** Every temporary directory created by the suite, removed by the hook below. */
const temporaryDirectories = [];

/**
 * Writes a throwaway properties file and returns its path.
 *
 * The file is created under the system temporary directory, never inside the
 * repository, so no test run can leave an untracked artifact behind and dirty
 * the clean-working-tree gate.
 *
 * @param {string[]} lines Properties-file lines, written verbatim.
 * @returns {string} Absolute path of the file.
 */
function writePropertiesFile(lines) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "health-config-"));
  temporaryDirectories.push(directory);
  const file = path.join(directory, "app.config.properties");
  fs.writeFileSync(file, `${lines.join("\n")}\n`, "utf8");
  return file;
}

/**
 * Returns a path that is guaranteed not to exist, to exercise the
 * missing-file fallback without depending on the absence of a real path.
 *
 * @returns {string} A non-existent absolute path.
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

/* ========================================================================== *
 * A. Preserved legacy behaviour (S1)
 * ========================================================================== */

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
    // Before this feature the module declared no exports at all and `add` was
    // not consumable as a library. Every name below is part of the published
    // surface and is asserted to exist with the expected type.
    const expectedFunctions = [
      "add",
      "loadConfig",
      "parseProperties",
      "currentTimestamp",
      "buildPayload",
      "healthPayload",
      "renderPayload",
      "normalizePath",
      "createServer",
      "buildServer",
      "serve",
      "probe",
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
  });

  it("publishes the documented aliases as the very same functions", () => {
    // Both names are documented, so a consumer written against either resolves.
    assert.equal(app.healthPayload, app.buildPayload);
    assert.equal(app.buildServer, app.createServer);
  });

  it("requiring the module writes nothing to stdout", () => {
    // The headline backward-compatibility assertion. The five writes used to
    // happen at module scope, so merely importing the file produced output and
    // made it untestable. They now live behind a main-module guard. A child
    // process is used because an in-process check cannot distinguish "never
    // written" from "written before the test started".
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


/* ========================================================================== *
 * B. The frozen health payload contract
 * ========================================================================== */

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

/* ========================================================================== *
 * C. Path normalisation, asserted as a pure function
 * ========================================================================== */

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

  it("always returns a path beginning with a slash", () => {
    for (const target of ["/health", "/health/", "", "?x=1", "/nope", undefined]) {
      assert.ok(app.normalizePath(target).startsWith("/"), `${target} should normalise to an absolute path`);
    }
  });
});


/* ========================================================================== *
 * D. Routing, status codes and headers over a live server
 * ========================================================================== */

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
    // This is the exact code path a container health check runs, which is why it
    // is worth asserting in process: slim and JRE base images ship neither curl
    // nor wget, so the application probes itself. probe() resolves a code rather
    // than calling process.exit, so calling it here cannot kill the runner.
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


/* ========================================================================== *
 * E. Configuration precedence: environment > properties file > default
 * ========================================================================== */

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

  it("treats a blank environment variable as absent rather than as an override", () => {
    // The contract requires a non-empty name and a dotted version, so an empty
    // or whitespace-only variable must fall through to the next source instead
    // of producing a payload that violates the contract.
    const config = app.loadConfig({ file, env: { APP_NAME: "", APP_VERSION: "   " } });
    assert.equal(config.name, "name-from-file");
    assert.equal(config.version, "9.8.7");
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

  it("parses Java-native properties text, including its awkward cases", () => {
    const parsed = app.parseProperties(
      [
        "# comment",
        "! bang comment",
        "   ",
        "app.name=trimmed  ",
        "  spaced.key  =  spaced value  ",
        "empty.value=",
        "weird=a=b=c",
        "line-with-no-separator",
        "=value-with-no-key",
      ].join("\n"),
    );
    assert.equal(parsed["app.name"], "trimmed");
    assert.equal(parsed["spaced.key"], "spaced value");
    assert.equal(parsed["empty.value"], "");
    // Only the first "=" separates key from value, so a value may contain "=".
    assert.equal(parsed.weird, "a=b=c");
    assert.equal("line-with-no-separator" in parsed, false);
    assert.equal("" in parsed, false);
    assert.equal("# comment" in parsed, false);
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

