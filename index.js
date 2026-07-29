/**
 * index.js - JavaScript application for only_parent_parent_repo_10_LOC.
 *
 * The file keeps its original job (printing the result of add(5, 7) five times)
 * and gains a uniform /health endpoint that is byte-compatible with the Python
 * and Java implementations in this repository.
 *
 * Three invocation modes, dispatched from the main-module guard at the bottom:
 *
 *   node index.js            legacy behaviour - prints "12" five times, exits 0
 *   node index.js --serve    binds host:port and serves the health endpoint
 *   node index.js --probe    requests its own endpoint, exits 0 (up) or 1 (down)
 *
 * The default mode is byte-identical to the pre-existing behaviour: it is
 * hashed by a committed baseline, so nothing outside the default branch may
 * ever write to stdout. Every diagnostic in this file goes to stderr.
 *
 * The health response is a strict subset of the vocabulary in the IETF draft
 * "Health Check Response Format for HTTP APIs" (draft-inadarei-api-health-check-06):
 * a JSON body, a `status` field, and a 2xx code for a passing status, with
 * no-store caching so a health answer is never served from a cache. Two
 * deviations from that draft are deliberate: the response uses the plain
 * `application/json` media type rather than the draft's health-specific type,
 * because that is what generic tooling and the repository's verification
 * script expect; and HEAD is answered with 405, because the endpoint is
 * GET-only by design and no identified consumer issues a HEAD request.
 *
 * Reading and framing a request is the runtime's job. `node:http` is the whole
 * HTTP implementation here; this file supplies a plain request handler that
 * decides which of exactly three responses to write, and nothing else:
 *
 *   200 OK                     GET on the configured health route
 *   404 Not Found              any other target
 *   405 Method Not Allowed     any other method (+ Allow: GET)
 *
 * The module is dependency-free: everything below comes from the Node
 * standard library, so `node index.js` works on a bare runtime with no
 * install step, no node_modules directory and no lockfile.
 */

const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

function add(a, b) {
  return a + b;
}

/* -------------------------------------------------------------------------- *
 * Configuration - single source of truth with a fixed override precedence
 * -------------------------------------------------------------------------- */

/**
 * Absolute path of the shared cross-language configuration file.
 *
 * Resolved relative to this file rather than to the process working directory
 * so that `node /some/where/index.js` and a container `WORKDIR` change both
 * still find the properties file that ships beside the script.
 */
const CONFIG_FILE = path.join(__dirname, "app.config.properties");

/**
 * Built-in defaults, keyed by their properties-file key. These are the last
 * resort in the precedence chain and are the values the endpoint reports when
 * neither an environment variable nor the properties file supplies one.
 */
const DEFAULTS = Object.freeze({
  "app.name": "only_parent_parent_repo_10_LOC",
  "app.version": "1.1.0",
  "health.path": "/health",
  "app.host": "0.0.0.0",
  "node.port": "8001",
});

/**
 * Environment variable that overrides each properties key. Documented for
 * operators in .env.example; asserted by the CI configuration gate.
 */
const ENV_KEYS = Object.freeze({
  "app.name": "APP_NAME",
  "app.version": "APP_VERSION",
  "health.path": "HEALTH_PATH",
  "app.host": "APP_HOST",
  "node.port": "NODE_PORT",
});

/**
 * Universal port variable. It outranks NODE_PORT and the properties file so a
 * single-application container can be told which port to bind with the one
 * variable every platform already sets (twelve-factor convention).
 */
const UNIVERSAL_PORT_ENV = "PORT";

/** Inclusive bounds of a valid TCP port number. */
const PORT_MIN = 0;
const PORT_MAX = 65535;

/**
 * Bind addresses that mean "every interface". A probe cannot connect to a
 * wildcard address, so these are translated to loopback in probe().
 */
const WILDCARD_HOSTS = Object.freeze(["", "*", "0.0.0.0", "::", "[::]"]);

/** Loopback address used by the self-probe when the bind host is a wildcard. */
const LOOPBACK_HOST = "127.0.0.1";

/**
 * The IPv6 loopback authority. Bracketed, because a URL authority carrying a
 * bare `::1` would have its colons read as a port separator.
 */
const LOOPBACK_AUTHORITY_V6 = "[::1]";

/**
 * Every spelling of IPv6 loopback that is accepted, because a properties file
 * may write the address compressed or expanded and both name the same interface.
 */
const IPV6_LOOPBACK_FORMS = Object.freeze([
  "::1",
  "[::1]",
  "0:0:0:0:0:0:0:1",
  "[0:0:0:0:0:0:0:1]",
]);

/**
 * The one host NAME treated as loopback. RFC 6761 reserves it for exactly that,
 * and it is MAPPED to the numeric address rather than resolved, so a hosts-file
 * entry cannot redirect a self-check off this machine.
 */
const LOOPBACK_NAME = "localhost";

/**
 * Every IPv4 address in 127.0.0.0/8 is loopback, so a listener deliberately
 * bound to, say, 127.0.0.2 is still probed at the address it is actually on.
 */
const IPV4_LOOPBACK_PREFIX = "127.";

/**
 * The shared port grammar: an optional sign, then ASCII digits, and nothing
 * else. Written as an explicit character class rather than `\d` so that the
 * intent survives a future `u` flag - and so that it reads as the same rule the
 * Python and Java implementations apply, which is the point of pinning it.
 */
const PORT_GRAMMAR = /^[+-]?[0-9]+$/;

/** The frozen three-part dotted version contract from the response schema. */
const VERSION_GRAMMAR = /^[0-9]+\.[0-9]+\.[0-9]+$/;

/**
 * The frozen timestamp contract: a fixed-width UTC instant truncated to whole
 * seconds with a `Z` designator. Used only to assert the SHAPE of a field, never
 * its value - the timestamp is the one non-deterministic part of the payload.
 */
const TIMESTAMP_GRAMMAR = /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$/;

/**
 * The character range a request target may be made of: visible US-ASCII only.
 * Space and every control character are therefore excluded, which is what stops
 * a configured route from carrying a CR and an LF - a header-injection
 * primitive the moment such a value reaches a request line or a log line.
 */
const TARGET_MIN_CHAR = 0x21;
const TARGET_MAX_CHAR = 0x7e;

/**
 * The key set and the key ORDER the probe requires of an answer, and the single
 * wording all three implementations use when an answer does not carry them. The
 * reason is pinned as a constant because an operator greps one deployment's
 * logs rather than one language's, and this is the easiest reason in the set to
 * drift: every language has a different natural way to print a list.
 */
const PAYLOAD_KEYS = Object.freeze(["name", "version", "timestamp", "status"]);
const PROBE_KEY_SET_REASON =
  'body does not carry exactly the keys ["name","version","timestamp","status"] in order';

/**
 * Largest health answer the probe will read. The contract body is 108 bytes, so
 * this is roughly seventy times the largest legitimate answer and exists only to
 * bound MEMORY: an endpoint that streams without end must be refused rather than
 * accumulated. The same ceiling is applied in app.py and User.java.
 */
const MAX_PROBE_BODY_BYTES = 8192;

/**
 * Listener timeouts, all four of them, because Node's defaults are wrong for a
 * health endpoint in two directions at once.
 *
 * `headersTimeout` defaults to 60 s and `requestTimeout` to 300 s, so a client
 * that opens a connection and then trickles can hold a socket - and the memory
 * behind it - for five minutes; on a container whose only job is to answer a
 * 108-byte document in milliseconds, that is a slow-loris budget rather than a
 * grace period. `server.timeout` defaults to 0, meaning an established socket
 * that goes quiet is never reclaimed at all.
 *
 * `connectionsCheckingInterval` is how often the runtime sweeps for connections
 * that have outlived those budgets; it is a CONSTRUCTOR option rather than a
 * property, so it is passed to createServer() where it takes effect.
 */
const CONNECTION_CHECK_INTERVAL_MS = 500;
const HEADERS_TIMEOUT_MS = 10000;
const REQUEST_TIMEOUT_MS = 15000;
const SOCKET_TIMEOUT_MS = 30000;
const KEEP_ALIVE_TIMEOUT_MS = 5000;

/**
 * Returns the first argument that is a supplied string, verbatim; undefined
 * when there is none.
 *
 * An environment variable set to the empty string is treated as absent rather
 * than as an override to the empty string: the response contract requires a
 * non-empty name and a dotted version, so an empty value must fall through to
 * the next source instead of producing a payload that violates the contract.
 *
 * ONLY the empty string is absent, and the winning value is returned exactly as
 * it was supplied. Both halves of that sentence are correctness requirements
 * rather than preferences, because the same precedence chain exists in app.py
 * and User.java and all three must agree on every input:
 *
 *   * app.py resolves with `if override:` and User.java with
 *     `!fromEnvironment.isEmpty()`, so a whitespace-only value is a SUPPLIED
 *     value in both. Trimming before the presence test - which this function
 *     used to do - silently erased it here, and a supplied-but-invalid PORT of
 *     "   " then fell through to the built-in default instead of being
 *     rejected. That is precisely the failure fail-closed exists to prevent:
 *     the operator asked for a specific port, so a healthy process listening
 *     somewhere they are not watching is worse than a refusal. Whitespace is
 *     now carried through to `resolvePort`, which rejects it.
 *   * Neither sibling trims the value it returns either, so trimming here would
 *     make a configured name, version, path or host differ across the three
 *     implementations for the same input. Values that legitimately need
 *     trimming are trimmed at the point of use: `resolvePort` trims before
 *     parsing, exactly as User.java's `raw.trim()` and app.py's
 *     `int(str(value).strip())` do, and the properties parser already trims
 *     what it reads from the file.
 *
 * @param {...unknown} candidates Values in precedence order.
 * @returns {string|undefined} The first supplied value, exactly as supplied.
 */
function firstNonEmpty(...candidates) {
  for (const candidate of candidates) {
    if (typeof candidate === "string" && candidate !== "") {
      return candidate;
    }
  }
  return undefined;
}

/**
 * Writes a diagnostic to stderr.
 *
 * Every message this module emits goes through here, and two guarantees follow
 * from that being a single exit rather than a convention every call site has to
 * remember.
 *
 * Nothing reaches stdout: stdout carries the legacy output that a committed hash
 * asserts byte for byte, so a single stray console.log would break backward
 * compatibility.
 *
 * And no caller can forge a log line. The text is stripped of control characters
 * by sanitizeForLog before the newline is appended, so a configured value
 * carrying a CR and an LF cannot produce a second line in whatever collects this
 * process's stderr, and a terminal escape sequence cannot rewrite what an
 * operator sees. sanitizeForLog is declared further down the file; function
 * declarations are hoisted, so the call below resolves.
 *
 * @param {string} message Human-readable diagnostic, without a trailing newline.
 * @returns {void}
 */
function warn(message) {
  process.stderr.write(`index.js: ${sanitizeForLog(String(message))}\n`);
}

/**
 * Parses Java-native `key=value` properties text.
 *
 * Blank lines are skipped, `#` and `!` introduce comments, and only the first
 * `=` separates key from value so a value may itself contain `=`. Keys and
 * values are trimmed; a line with no `=` and a line with an empty key are both
 * ignored rather than producing a bogus entry.
 *
 * @param {string} text Raw file contents.
 * @returns {Record<string, string>} Parsed key/value pairs.
 */
function parseProperties(text) {
  const props = {};
  for (const rawLine of String(text).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line === "" || line.startsWith("#") || line.startsWith("!")) {
      continue;
    }
    const separator = line.indexOf("=");
    if (separator === -1) {
      continue;
    }
    const key = line.slice(0, separator).trim();
    if (key === "") {
      continue;
    }
    props[key] = line.slice(separator + 1).trim();
  }
  return props;
}

/**
 * Reads and parses the properties file, tolerating its absence.
 *
 * A missing file is not an error: the built-in defaults are a complete
 * configuration on their own, so the application still starts and still serves
 * a valid payload. Any other failure (unreadable file, bad permissions) is
 * reported on stderr because it is a real misconfiguration an operator should
 * see, and it still falls back to the defaults rather than refusing to start.
 *
 * @param {string} file Path of the properties file.
 * @returns {Record<string, string>} Parsed pairs, or an empty object.
 */
function readProperties(file) {
  try {
    return parseProperties(fs.readFileSync(file, "utf8"));
  } catch (error) {
    if (error && error.code !== "ENOENT") {
      // The category is reported and nothing else. The path is a deployment
      // detail and the error message embeds it, so neither reaches the line -
      // the file is one command away from the operator reading it.
      warn("cannot read the configuration file; using defaults");
    }
    return {};
  }
}

/**
 * Ensures a request path starts with a slash, so a value configured as
 * "healthz" still matches the request target "/healthz".
 *
 * @param {string} value Configured path.
 * @returns {string} Path guaranteed to begin with "/".
 */
function withLeadingSlash(value) {
  return value.startsWith("/") ? value : `/${value}`;
}

/**
 * Resolves the listener port from candidates given in precedence order.
 *
 * The highest-precedence candidate that is present wins, and if that value is
 * not a legal port the function throws rather than falling through to a lower
 * one. Failing closed is the point: an operator who sets PORT=8O01 with a
 * letter O has asked for a specific port, and quietly serving the default
 * instead would leave a health probe pointed at nothing while the process
 * reported itself up. Silently binding NaN - which listen() turns into an
 * arbitrary ephemeral port - would be worse still.
 *
 * Parsing is a digit test rather than Number(), because Number("0x50") is 80:
 * a hexadecimal typo would otherwise resolve to a real but unintended port.
 * The accepted grammar is the same one Integer.parseInt and int() accept, so
 * all three implementations reject the same values.
 *
 * Surrounding whitespace is removed before that test and nowhere else, which is
 * what makes PORT=" 8080 " resolve to 8080 here just as it does in User.java's
 * `Integer.parseInt(raw.trim())` and app.py's `int(str(value).strip())` - while
 * PORT="   " trims to the empty string, fails the digit test and is REJECTED
 * rather than skipped. A supplied value is always graded; only a value that was
 * never supplied at all falls through to the next candidate.
 *
 * Port 0 is legal: it is how a test binds an ephemeral port and reads the
 * assignment back from the server.
 *
 * @param {Array<unknown>} candidates Values in precedence order.
 * @returns {number} A valid TCP port number.
 * @throws {RangeError} When the winning candidate is not a port in 0-65535.
 */
function resolvePort(candidates) {
  for (const candidate of candidates) {
    const supplied = firstNonEmpty(candidate);
    if (supplied === undefined) {
      continue;
    }
    const value = supplied.trim();
    if (!PORT_GRAMMAR.test(value)) {
      throw new RangeError(
        `invalid port ${JSON.stringify(supplied)}: expected an ASCII decimal integer`,
      );
    }
    const port = Number(value);
    if (port < PORT_MIN || port > PORT_MAX) {
      throw new RangeError(`invalid port ${port}: outside the range ${PORT_MIN}-${PORT_MAX}`);
    }
    return port;
  }
  return Number(DEFAULTS["node.port"]);
}

/**
 * Loads the effective configuration.
 *
 * Precedence, highest first: environment variable, then the properties file,
 * then the built-in default. The port has one extra rung above all of those -
 * the universal PORT variable - so PORT beats NODE_PORT beats `node.port`
 * beats 8001.
 *
 * A variable set to the empty string is the ONLY value treated as absent; every
 * other supplied value wins its rung and is used exactly as supplied. app.py and
 * User.java resolve the same chain the same way, so the three implementations
 * agree on every input - including the awkward ones, where a supplied but
 * unusable port is refused by all three rather than quietly replaced.
 *
 * Both the file path and the environment map are injectable so that callers
 * (notably the unit tests) can assert the precedence chain without mutating
 * process.env for the whole process.
 *
 * @param {{file?: string, env?: Record<string, string|undefined>}} [options]
 * @returns {Readonly<{name: string, version: string, healthPath: string, host: string, port: number}>}
 */
function loadConfig(options = {}) {
  const file = options.file === undefined ? CONFIG_FILE : options.file;
  const env = options.env === undefined ? process.env : options.env || {};
  const props = readProperties(file);
  const pick = (key) => firstNonEmpty(env[ENV_KEYS[key]], props[key], DEFAULTS[key]);

  return Object.freeze({
    name: pick("app.name"),
    version: pick("app.version"),
    healthPath: withLeadingSlash(pick("health.path")),
    host: pick("app.host"),
    port: resolvePort([
      env[UNIVERSAL_PORT_ENV],
      env[ENV_KEYS["node.port"]],
      props["node.port"],
      DEFAULTS["node.port"],
    ]),
  });
}

/**
 * Returns true when text is a non-empty string carrying no control character.
 *
 * The rule for the two configured values that are neither a route nor a number:
 * a name that appears in the published payload, and a host that appears in a
 * diagnostic. For both, the only real constraint is that they can be printed on
 * one line - which is also what stops either of them forging a second one.
 *
 * @param {unknown} text Candidate value.
 * @returns {boolean} True when the value is safe to publish and to print.
 */
function isSingleLineText(text) {
  if (typeof text !== "string" || text === "") {
    return false;
  }
  for (let index = 0; index < text.length; index += 1) {
    const code = text.charCodeAt(index);
    if (code < 0x20 || code === 0x7f) {
      return false;
    }
  }
  return true;
}

/**
 * Returns true when a target is made only of characters allowed in one.
 *
 * Every character must be visible US-ASCII. Applied to the CONFIGURED route
 * rather than to an inbound request - reading an inbound request is the platform
 * server's job - so that a health path carrying a space, a CR or an LF is
 * refused where it is configured instead of becoming an injected request line in
 * probe().
 *
 * @param {unknown} target Candidate request target.
 * @returns {boolean} True when every character is visible US-ASCII.
 */
function isRequestTarget(target) {
  if (typeof target !== "string" || target === "") {
    return false;
  }
  for (let index = 0; index < target.length; index += 1) {
    const code = target.charCodeAt(index);
    if (code < TARGET_MIN_CHAR || code > TARGET_MAX_CHAR) {
      return false;
    }
  }
  return true;
}

/**
 * Refuses a configuration this endpoint must not publish.
 *
 * Configuration is an input, and every value it carries ends up either in the
 * public health document or in the route that serves it. Without this check a
 * non-empty but malformed value was accepted verbatim: `APP_VERSION` of
 * `not-a-version` was served inside a 200 response whose `status` field read
 * `UP`, so the endpoint attested to its own health while describing itself in a
 * form no consumer of the frozen contract could parse. A health endpoint that
 * reports success while breaking its own contract is worse than one that refuses
 * to start, because nothing downstream can tell.
 *
 * Four rules, identical in app.py and User.java:
 *
 *   - `name` is non-empty and carries no control character. It is a payload
 *     field, and a control character in it would also break the single-line
 *     startup banner and diagnostics.
 *   - `version` matches VERSION_GRAMMAR exactly.
 *   - `healthPath` begins with "/" and is a valid request target.
 *   - `host` is non-empty and carries no control character.
 *
 * Enforced at both of the points where a bad value would otherwise become
 * observable: creating the server, before the socket is bound, so a misconfigured
 * process never listens at all; and running the probe, so a probe cannot report
 * healthy a configuration the server would refuse to serve.
 *
 * No message quotes the offending value. The key names the setting, which is all
 * an operator needs in order to find it, and withholding the value is what lets
 * probe() print this message verbatim without a configured string reaching a log
 * line. The port is deliberately NOT checked here: resolvePort already grades it
 * at the point of use, where the failure can be reported as the transport fault
 * it is.
 *
 * @param {{name?: string, version?: string, healthPath?: string, host?: string}} config
 *   Configuration to check, normally from loadConfig.
 * @returns {void}
 * @throws {RangeError} On the first rule that fails.
 */
function validateConfig(config) {
  if (config === undefined || config === null || typeof config !== "object") {
    throw new RangeError("no configuration was resolved");
  }
  if (!isSingleLineText(config.name)) {
    throw new RangeError(
      "invalid app.name: it must be non-empty text with no control character",
    );
  }
  if (typeof config.version !== "string" || !VERSION_GRAMMAR.test(config.version)) {
    throw new RangeError(
      "invalid app.version: it must be a three-part dotted numeric version",
    );
  }
  if (
    typeof config.healthPath !== "string" ||
    !config.healthPath.startsWith(ROOT_PATH) ||
    !isRequestTarget(config.healthPath)
  ) {
    throw new RangeError("invalid health.path: it is not a valid request target");
  }
  if (!isSingleLineText(config.host)) {
    throw new RangeError(
      "invalid app.host: it must be non-empty text with no control character",
    );
  }
}

/* -------------------------------------------------------------------------- *
 * Health payload - the frozen response contract
 * -------------------------------------------------------------------------- */

/** The single value the `status` field may take while the process is serving. */
const HEALTH_STATUS = "UP";

/** Response media type. Plain JSON by design - see the file header. */
const CONTENT_TYPE = "application/json";

/** A health answer must never be served from a cache. */
const CACHE_CONTROL = "no-cache, no-store, must-revalidate";

/** The only method the endpoint accepts, advertised in the 405 `Allow` header. */
const ALLOWED_METHODS = "GET";

/**
 * Error bodies. They are rendered once at load time because they are constant,
 * and they deliberately carry no detail: the requested path, the method and any
 * stack trace are withheld so an unauthenticated caller learns nothing about
 * the deployment from a failed request.
 */
const NOT_FOUND_BODY = JSON.stringify({ error: "Not Found" });
const METHOD_NOT_ALLOWED_BODY = JSON.stringify({ error: "Method Not Allowed" });

/* -------------------------------------------------------------------------- *
 * Health payload construction and route normalisation
 * -------------------------------------------------------------------------- */

/** Root path, and the value an empty or query-only target normalises to. */
const ROOT_PATH = "/";

/**
 * Marks an absolute-form request target, as in `GET http://host/health`.
 *
 * RFC 9112 section 3.2.2 requires a server to accept this form even though almost
 * no client emits it, and all three implementations reduce it to its path so that
 * the same request reaches the same route in every one of them.
 *
 * @type {string}
 */
const SCHEME_SEPARATOR = "://";

/**
 * Introduces a URI fragment.
 *
 * A real request target never carries one - RFC 9110 section 7.1 has the client
 * strip it before sending - so this is stripped defensively, and because the same
 * function normalises the CONFIGURED health path, where one could be written by
 * hand.
 *
 * @type {string}
 */
const FRAGMENT_MARKER = "#";

/**
 * Current UTC instant, truncated to whole seconds, with a trailing "Z".
 *
 * toISOString() yields milliseconds ("...T13:47:08.123Z"); stripping the
 * fractional part gives the fixed-width form the Python and Java
 * implementations also emit, which keeps the rendered body the same length in
 * all three languages. This is the only non-deterministic field in the
 * payload, which is why every automated assertion against it checks the format
 * and never the value.
 *
 * @param {Date} [now] Instant to format; defaults to the current time.
 * @returns {string} e.g. "2026-07-28T13:47:08Z".
 */
function currentTimestamp(now = new Date()) {
  return now.toISOString().replace(/\.\d{3}Z$/, "Z");
}

/**
 * Builds the health payload.
 *
 * The four keys are inserted in the contract order - name, version, timestamp,
 * status - because JSON.stringify serialises own string keys in insertion
 * order, so the object literal below is what fixes the field order on the
 * wire. Nothing else is reported: four fields and no more is the whole
 * disclosure of this network-reachable surface.
 *
 * @param {ReturnType<typeof loadConfig>} [config] Effective configuration.
 * @returns {{name: string, version: string, timestamp: string, status: string}}
 */
function buildPayload(config) {
  const resolved = config === undefined || config === null ? loadConfig() : config;
  return {
    name: resolved.name,
    version: resolved.version,
    timestamp: currentTimestamp(),
    status: HEALTH_STATUS,
  };
}

/**
 * Renders the health payload as compact JSON.
 *
 * No replacer and no space argument are passed, so the output carries no
 * whitespace and matches the Python (compact separators) and Java (hand-built
 * string) renderings byte for byte.
 *
 * Accepts either an already-built payload or a configuration object, so callers
 * that have a payload in hand do not have to rebuild it - and so a caller that
 * has neither can simply call renderPayload().
 *
 * @param {object} [source] A payload (recognised by its `status` field) or a config.
 * @returns {string} Compact JSON document.
 */
function renderPayload(source) {
  const payload =
    source !== null && typeof source === "object" && typeof source.status === "string"
      ? source
      : buildPayload(source);
  return JSON.stringify(payload);
}

/**
 * Normalises a request target to a comparable path.
 *
 * Two steps, in order and identical to normalisePath in User.java and
 * normalize_path in app.py: drop everything from the first "?", then remove
 * exactly one trailing slash when something is left in front of it.
 *
 * What the function deliberately does NOT do matters as much as what it does.
 * It performs no percent-decoding, so "/health%2f" stays distinct from
 * "/health/"; it resolves no dot segments, so "/health/../health" does not
 * reach the route; and it collapses no leading slashes, so "//health" and
 * "///health" are distinct from "/health". Each of those transformations would
 * widen the route to targets an operator did not configure.
 *
 * The function is pure and total - an absent, empty or query-only target
 * normalises to the root rather than throwing - which is what lets the tests
 * assert its behaviour directly without a server.
 *
 * @param {string} rawUrl Raw request target, e.g. req.url.
 * @returns {string} Normalised path.
 */
function normalizePath(rawUrl) {
  const raw = typeof rawUrl === "string" && rawUrl !== "" ? rawUrl : ROOT_PATH;
  let normalised = stripAuthority(raw);
  const queryIndex = normalised.indexOf("?");
  if (queryIndex >= 0) {
    normalised = normalised.slice(0, queryIndex);
  }
  const fragmentIndex = normalised.indexOf(FRAGMENT_MARKER);
  if (fragmentIndex >= 0) {
    normalised = normalised.slice(0, fragmentIndex);
  }
  if (normalised.length > 1 && normalised.endsWith(ROOT_PATH)) {
    normalised = normalised.slice(0, -1);
  }
  return normalised === "" ? ROOT_PATH : normalised;
}

/**
 * Reports whether a character is an unaccented ASCII letter.
 *
 * @param {string} current A single character.
 * @returns {boolean} `true` for A-Z and a-z only.
 */
function isAsciiLetter(current) {
  return (current >= "a" && current <= "z") || (current >= "A" && current <= "Z");
}

/**
 * Reports whether a string is a URI scheme as RFC 3986 defines one.
 *
 * ALPHA followed by any number of ALPHA, DIGIT, `+`, `-` or `.`.
 *
 * @param {string} candidate The text before `://` in a request target.
 * @returns {boolean} `true` when it could be a scheme.
 */
function isScheme(candidate) {
  if (candidate === "" || !isAsciiLetter(candidate.charAt(0))) {
    return false;
  }
  for (let index = 1; index < candidate.length; index += 1) {
    const current = candidate.charAt(index);
    const allowed =
      isAsciiLetter(current) ||
      (current >= "0" && current <= "9") ||
      current === "+" ||
      current === "-" ||
      current === ".";
    if (!allowed) {
      return false;
    }
  }
  return true;
}

/**
 * Reduces an absolute-form request target to its path component.
 *
 * `GET http://host:8001/health HTTP/1.1` is a request shape RFC 9112 section
 * 3.2.2 requires a server to accept, and it is the only shape in which the target
 * carries a scheme and an authority. Reducing it here is what makes the absolute
 * form reach the same route the origin form reaches - and what makes this module
 * agree with `app.py` and `User.java`, which perform the identical reduction.
 *
 * The scheme is VALIDATED before anything is stripped, and that ordering is the
 * whole safety of this function: a relative target whose query string happens to
 * contain `://` - a redirect parameter such as `/health?next=http://elsewhere/` -
 * has `/health?next=http` before the separator, which is not a scheme, so it is
 * returned completely untouched.
 *
 * @param {string} target The request target as it arrived.
 * @returns {string} The path component of an absolute-form target, or the target.
 */
function stripAuthority(target) {
  const separator = target.indexOf(SCHEME_SEPARATOR);
  if (separator <= 0 || !isScheme(target.slice(0, separator))) {
    return target;
  }
  const authorityStart = separator + SCHEME_SEPARATOR.length;
  const pathStart = target.indexOf("/", authorityStart);
  return pathStart < 0 ? ROOT_PATH : target.slice(pathStart);
}

/**
 * Strips control characters from text that is about to be written to stderr.
 *
 * Configuration values reach the startup banner, and a value carrying CR, LF or
 * an escape sequence could forge a log line or drive a terminal escape. Each
 * offending byte is replaced with "?" so the diagnostic stays one readable line.
 *
 * @param {string} text Text to sanitise.
 * @returns {string} Text with every control character replaced.
 */
function sanitizeForLog(text) {
  let sanitized = "";
  const source = String(text);
  for (let index = 0; index < source.length; index += 1) {
    const code = source.charCodeAt(index);
    sanitized += code < 0x20 || code === 0x7f ? "?" : source.charAt(index);
  }
  return sanitized;
}

/* -------------------------------------------------------------------------- *
 * HTTP server
 * -------------------------------------------------------------------------- */

/**
 * Writes a JSON response carrying exactly the contract headers.
 *
 * Two suppressions are load-bearing rather than cosmetic. `sendDate` is
 * switched off because Node would otherwise add a `Date` header that the Python
 * implementation does not emit. `Connection` is removed for the same reason:
 * Node adds `Connection: keep-alive` plus a `Keep-Alive` header on a persistent
 * HTTP/1.1 response, and removing the header before writeHead() stops both from
 * being computed. Persistence itself is unaffected - it is governed by the
 * parser's own keep-alive decision rather than by the header suppressed here -
 * so the connection is still reused across requests. No `Server` header is ever
 * set, so the runtime version is not advertised.
 *
 * The result is the same three-header set the other two implementations emit:
 * Content-Type, Cache-Control and Content-Length, plus Allow on a 405.
 *
 * `Content-Length` is the BYTE length rather than the character count, so a
 * multi-byte character in a configured value cannot desynchronise the
 * advertised length from the body.
 *
 * @param {import("node:http").ServerResponse} res Response to write.
 * @param {number} statusCode 200, 404 or 405.
 * @param {string} body Already-rendered JSON document.
 * @param {Record<string, string>} [extraHeaders] Additional headers, e.g. Allow.
 * @returns {void}
 */
function writeJson(res, statusCode, body, extraHeaders) {
  res.sendDate = false;
  res.removeHeader("Connection");
  const headers = {
    "Content-Type": CONTENT_TYPE,
    "Cache-Control": CACHE_CONTROL,
    "Content-Length": Buffer.byteLength(body),
  };
  if (extraHeaders) {
    Object.assign(headers, extraHeaders);
  }
  res.writeHead(statusCode, headers);
  res.end(body);
}

/**
 * Builds an unstarted HTTP server for the health endpoint.
 *
 * The handler is three lines of routing over `node:http`: a method other than
 * GET is 405 with an `Allow` header, a target that does not normalise to the
 * configured route is 404, and everything else is the health document. There is
 * no fourth outcome, and nothing here reads the request body - the endpoint
 * answers from configuration and the clock alone.
 *
 * Configuration is resolved ONCE here rather than per request, so every response
 * a given server produces describes the same application; the Python and Java
 * implementations snapshot at construction for the same reason. Reloading is
 * what a restart is for.
 *
 * It is also VALIDATED here, before any socket exists. A server that bound a
 * port and then answered 200/UP with a malformed version would have published
 * the very thing the validation exists to refuse, and would have looked healthy
 * while doing it - so the refusal happens at construction, where the caller's own
 * catch block learns about the typo rather than a monitoring system three layers
 * away.
 *
 * The four listener budgets are applied here too, for the reason given at their
 * declaration: Node's defaults let a client that trickles hold a socket for five
 * minutes and never reclaim one that goes quiet.
 *
 * The server is returned rather than started, so the tests can drive it on an
 * ephemeral port (listen(0)) without competing for the configured one.
 *
 * @param {ReturnType<typeof loadConfig>} [config] Effective configuration.
 * @returns {import("node:http").Server} An unstarted HTTP server.
 * @throws {RangeError} When the configuration cannot be published.
 */
function createServer(config) {
  const resolved = config === undefined || config === null ? loadConfig() : config;
  validateConfig(resolved);
  const routePath = normalizePath(resolved.healthPath);

  const server = http.createServer(
    {
      connectionsCheckingInterval: CONNECTION_CHECK_INTERVAL_MS,
      headersTimeout: HEADERS_TIMEOUT_MS,
      requestTimeout: REQUEST_TIMEOUT_MS,
      keepAliveTimeout: KEEP_ALIVE_TIMEOUT_MS,
    },
    (req, res) => {
      if (req.method !== ALLOWED_METHODS) {
        writeJson(res, 405, METHOD_NOT_ALLOWED_BODY, { Allow: ALLOWED_METHODS });
        return;
      }
      if (normalizePath(req.url) !== routePath) {
        writeJson(res, 404, NOT_FOUND_BODY);
        return;
      }
      writeJson(res, 200, renderPayload(resolved));
    },
  );
  // An established socket that goes quiet is never reclaimed at Node's default of
  // 0, so the inactivity ceiling is set explicitly. It is a property rather than
  // a constructor option, which is why it is applied here.
  server.timeout = SOCKET_TIMEOUT_MS;
  return server;
}

/* -------------------------------------------------------------------------- *
 * Process modes - serve and probe
 * -------------------------------------------------------------------------- */

/** How long a graceful shutdown may take before the process exits anyway. */
const SHUTDOWN_GRACE_MS = 1000;

/**
 * Registers a graceful shutdown on the signals a container runtime sends.
 *
 * Open keep-alive sockets would otherwise keep server.close() pending, so they
 * are closed first; the unref'd fallback timer guarantees the process still
 * exits promptly if a socket refuses to go away, and being unref'd it never
 * holds the event loop open on its own.
 *
 * Termination convention. Because the shutdown completes normally, this process
 * exits 0 for both SIGINT and SIGTERM. The other two implementations report
 * their own runtime's convention for the same signals - app.py exits 0 on
 * SIGINT and is terminated by SIGTERM (a shell reports 143), and the JVM in
 * User.java reports 130 and 143 - so the exit STATUS is the one place these
 * three servers deliberately differ. Everything an orchestrator depends on is
 * identical: the listener is closed, the port is released, and stdout stays
 * empty. Overriding a platform convention to align the numbers would buy
 * nothing, so the difference is documented instead.
 *
 * @param {import("node:http").Server} server Server to close.
 * @returns {() => void} The shutdown handler, for symmetry and testability.
 */
function registerShutdown(server) {
  const shutdown = () => {
    if (typeof server.closeAllConnections === "function") {
      server.closeAllConnections();
    }
    server.close(() => {
      process.exit(0);
    });
    setTimeout(() => {
      process.exit(0);
    }, SHUTDOWN_GRACE_MS).unref();
  };
  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.once(signal, shutdown);
  }
  return shutdown;
}

/**
 * Starts the health server.
 *
 * The single startup line goes to stderr, never stdout: stdout carries the
 * legacy output that a committed hash asserts byte for byte, so --serve must
 * leave it completely empty. The bound port is read back from the server so the
 * line is accurate even when the caller asked for port 0.
 *
 * @param {{config?: object, host?: string, port?: number|string,
 *          file?: string, env?: Record<string, string|undefined>}} [options]
 * @returns {import("node:http").Server} The listening server.
 * @throws {RangeError} When the configuration cannot be published.
 */
function serve(options = {}) {
  const config = options.config === undefined ? loadConfig(options) : options.config;
  const host = options.host === undefined ? config.host : options.host;
  const port = options.port === undefined ? config.port : resolvePort([String(options.port)]);
  const server = createServer(config);

  server.on("error", (error) => {
    // Almost always a port already in use. Report it as one readable line and
    // fail closed: an orchestrator that cannot bind must not see a success code.
    // The runtime error CODE is reported and its message is not: a code is a
    // fixed enumerated value, whereas a message can carry resolver-derived text.
    // The exit code is set rather than forced so the event loop drains and this
    // diagnostic is flushed before the process goes away.
    warn(
      `cannot start the health server: could not bind ${String(host)}:${port} ` +
        `(${error.code || "bind failed"})`,
    );
    process.exitCode = 1;
  });

  server.listen(port, host, () => {
    const address = server.address();
    const boundPort = address && typeof address === "object" ? address.port : port;
    // Configured values reach this line, and warn() strips control characters
    // from everything it writes: a health path carrying a CR and an LF would
    // otherwise forge an extra startup line in whatever collects this process's
    // stderr. The route printed is the NORMALISED one the listener actually
    // answers on, not the raw configured string, so the banner cannot promise a
    // route that does not exist.
    const route = normalizePath(config.healthPath);
    warn(`health endpoint listening on http://${String(host)}:${boundPort}${route}`);
  });

  registerShutdown(server);
  return server;
}

/* -------------------------------------------------------------------------- *
 * Self-check
 *
 * The container HEALTHCHECK, written in-process precisely so that the image
 * needs no HTTP client of its own: the slim Node image ships neither curl nor
 * wget, and adding one would enlarge the image, widen its attack surface and
 * hand a post-exploitation attacker a download-and-run helper.
 *
 * A probe is a CLIENT, and a client is only as safe as its behaviour against a
 * peer that does not cooperate. Three properties are therefore built in rather
 * than assumed: the destination is selected from a loopback allowlist and never
 * derived from configuration, the exchange is bounded in time AND in bytes, and
 * the verdict comes from parsing the document against the frozen contract rather
 * than from looking for a fragment inside it.
 * -------------------------------------------------------------------------- */

/**
 * Returns true when every character is an ASCII digit and there is at least one.
 *
 * ASCII only, deliberately: a near-miss address spelled with an Arabic-Indic
 * digit must not be graded loopback.
 *
 * @param {string} candidate Candidate octet.
 * @returns {boolean} True for a run of one or more ASCII digits.
 */
function isAsciiDigits(candidate) {
  if (candidate === "") {
    return false;
  }
  for (let index = 0; index < candidate.length; index += 1) {
    const code = candidate.charCodeAt(index);
    if (code < 0x30 || code > 0x39) {
      return false;
    }
  }
  return true;
}

/**
 * Returns true when a string is a dotted-quad IPv4 address in 127.0.0.0/8.
 *
 * Written out rather than delegated to a general address parser for the same
 * reason normalizePath is written out rather than delegated to a URL parser: a
 * general parser accepts spellings this module has no reason to accept -
 * `127.1`, `0x7f.0.0.1`, a bare decimal integer - and each of them is a
 * different way to write a destination the allowlist would then have to reason
 * about. Four decimal octets or nothing.
 *
 * @param {string} candidate Configured host, already trimmed.
 * @returns {boolean} True only for `127.b.c.d` with four octets in 0-255.
 */
function isIpv4Loopback(candidate) {
  if (!candidate.startsWith(IPV4_LOOPBACK_PREFIX)) {
    return false;
  }
  const octets = candidate.split(".");
  if (octets.length !== 4) {
    return false;
  }
  return octets.every(
    (octet) => isAsciiDigits(octet) && octet.length <= 3 && Number(octet) <= 255,
  );
}

/**
 * Returns the loopback authority the probe is permitted to connect to.
 *
 * This is an ALLOWLIST, and that is the whole point. `app.host` is an input: it
 * comes from a properties file and from `APP_HOST` in the environment, both of
 * which an operator, an orchestrator or a compromised sidecar can set. Rewriting
 * only the wildcard spellings and using everything else verbatim - which is what
 * this function used to do - made the container HEALTHCHECK a general-purpose
 * outbound HTTP client aimed wherever that input pointed: a probe that reports
 * healthy because some other machine is healthy, and an egress request the
 * deployment never asked for. So the destination is not derived from the
 * configured value at all; it is SELECTED from a fixed set of loopback forms, and
 * a value outside that set is replaced rather than honoured.
 *
 *   | Configured host                    | Probe destination                  |
 *   | ---------------------------------- | ---------------------------------- |
 *   | unset, empty, whitespace, 0.0.0.0, | 127.0.0.1 - a wildcard names every |
 *   | ::, [::], *                        | interface, not a destination       |
 *   | localhost                          | 127.0.0.1 - mapped, never resolved |
 *   | anything in 127.0.0.0/8            | itself                             |
 *   | ::1, [::1], the expanded form      | [::1] - bracketed for the URL      |
 *   | anything else                      | 127.0.0.1, with one warning; the   |
 *   |                                    | configured value is never logged   |
 *
 * Replacing rather than refusing is deliberate. A refusal would report the
 * application unhealthy because its BIND address is unusual, which is a
 * misdiagnosis: the listener may well be serving perfectly on an interface this
 * probe is not allowed to dial. Probing loopback answers the question the probe
 * is actually asking - is the process in this container serving? - and the
 * warning is what tells an operator the configured value was not used. app.py and
 * User.java apply the identical table.
 *
 * @param {string|undefined} host Configured bind address.
 * @returns {string} 127.0.0.1, [::1], or a 127.0.0.0/8 address as configured.
 */
function probeAuthority(host) {
  const candidate = (typeof host === "string" ? host : "").trim();
  const lowered = candidate.toLowerCase();
  if (WILDCARD_HOSTS.includes(lowered) || lowered === LOOPBACK_NAME) {
    return LOOPBACK_HOST;
  }
  if (IPV6_LOOPBACK_FORMS.includes(lowered)) {
    return LOOPBACK_AUTHORITY_V6;
  }
  if (isIpv4Loopback(candidate)) {
    return candidate;
  }
  warn("probe target is not loopback; probing loopback instead");
  return LOOPBACK_HOST;
}

/** The two-character escapes RFC 8259 defines, other than \\uXXXX. */
const JSON_SIMPLE_ESCAPES = Object.freeze({
  '"': '"',
  "\\": "\\",
  "/": "/",
  b: "\b",
  f: "\f",
  n: "\n",
  r: "\r",
  t: "\t",
});

/**
 * Advances past JSON insignificant whitespace.
 *
 * @param {string} text Document being read.
 * @param {number} at Cursor.
 * @returns {number} The first index at or after `at` that is not whitespace.
 */
function skipJsonWhitespace(text, at) {
  let cursor = at;
  while (cursor < text.length) {
    const character = text.charAt(cursor);
    if (character !== " " && character !== "\t" && character !== "\n" && character !== "\r") {
      return cursor;
    }
    cursor += 1;
  }
  return cursor;
}

/**
 * Reads one RFC 8259 string literal.
 *
 * @param {string} text Document being read.
 * @param {number} at Index of the opening quotation mark.
 * @returns {{value: string, cursor: number}|null} The decoded value and the
 *   index after the closing quote, or null when the literal is malformed.
 */
function readJsonString(text, at) {
  if (text.charAt(at) !== '"') {
    return null;
  }
  let cursor = at + 1;
  let value = "";
  while (cursor < text.length) {
    const character = text.charAt(cursor);
    if (character === '"') {
      return { value, cursor: cursor + 1 };
    }
    if (character === "\\") {
      const escape = text.charAt(cursor + 1);
      if (escape === "u") {
        const hex = text.slice(cursor + 2, cursor + 6);
        if (!/^[0-9a-fA-F]{4}$/.test(hex)) {
          return null;
        }
        value += String.fromCharCode(Number.parseInt(hex, 16));
        cursor += 6;
      } else if (Object.prototype.hasOwnProperty.call(JSON_SIMPLE_ESCAPES, escape)) {
        value += JSON_SIMPLE_ESCAPES[escape];
        cursor += 2;
      } else {
        return null;
      }
      continue;
    }
    if (character.charCodeAt(0) < 0x20) {
      // An unescaped control character is not legal inside a JSON string.
      return null;
    }
    value += character;
    cursor += 1;
  }
  return null;
}

/**
 * Reads a flat JSON object whose every member value is a string.
 *
 * JSON.parse would be shorter, but it resolves a repeated key by keeping the
 * LAST occurrence and says nothing about it, which silently turns a
 * contradictory document into a plausible one: a body carrying
 * `"status":"DOWN","status":"UP"` parses as healthy. RFC 8259 calls such an
 * object's behaviour unpredictable, so the probe refuses it rather than picking a
 * member on the endpoint's behalf. app.py refuses it through an
 * `object_pairs_hook` and User.java through the same algorithm as this reader, so
 * all three refuse the same documents.
 *
 * Anything that is not exactly a flat object of string values yields null: a
 * nested object, an array, a numeric or boolean member, a missing quote, a
 * repeated key, or a single byte of trailing content after the closing brace.
 *
 * @param {string} text Candidate JSON document.
 * @returns {Map<string, string>|null} The members in order, or null.
 */
function readFlatStringObject(text) {
  const members = new Map();
  let cursor = skipJsonWhitespace(text, 0);
  if (text.charAt(cursor) !== "{") {
    return null;
  }
  cursor = skipJsonWhitespace(text, cursor + 1);
  if (text.charAt(cursor) === "}") {
    return skipJsonWhitespace(text, cursor + 1) === text.length ? members : null;
  }
  for (;;) {
    const key = readJsonString(text, cursor);
    if (key === null) {
      return null;
    }
    cursor = skipJsonWhitespace(text, key.cursor);
    if (text.charAt(cursor) !== ":") {
      return null;
    }
    cursor = skipJsonWhitespace(text, cursor + 1);
    const value = readJsonString(text, cursor);
    if (value === null || members.has(key.value)) {
      return null;
    }
    members.set(key.value, value.value);
    cursor = skipJsonWhitespace(text, value.cursor);
    const delimiter = text.charAt(cursor);
    if (delimiter === ",") {
      cursor = skipJsonWhitespace(text, cursor + 1);
      continue;
    }
    if (delimiter === "}") {
      return skipJsonWhitespace(text, cursor + 1) === text.length ? members : null;
    }
    return null;
  }
}

/**
 * Grades the four contract fields of an already-shaped document.
 *
 * Split out so that the ordering of the field rules is stated in exactly one
 * place: `status` is examined before the three descriptive fields, so an endpoint
 * reporting itself down is reported as down rather than as whichever of its other
 * fields happened also to be wrong.
 *
 * @param {Record<string, unknown>} document Parsed health document.
 * @returns {string|null} A fixed-category reason, or null when all four hold.
 */
function fieldRejection(document) {
  if (document.status !== HEALTH_STATUS) {
    return "the status field is not the expected value";
  }
  if (typeof document.name !== "string" || document.name === "") {
    return "the name field is not a non-empty string";
  }
  if (typeof document.version !== "string" || !VERSION_GRAMMAR.test(document.version)) {
    return "the version field is not a three-part dotted numeric version";
  }
  if (typeof document.timestamp !== "string" || !TIMESTAMP_GRAMMAR.test(document.timestamp)) {
    return "the timestamp field is not a whole-second UTC instant";
  }
  return null;
}

/**
 * Returns why an answer fails to prove health, or null when it proves it.
 *
 * Separated from the transport so that every rule below is reachable by a direct
 * call, and so that all three implementations can be held to the same wording: an
 * operator greps one deployment's logs, not one language's.
 *
 * The rules, in the order they are applied - and the order is part of the
 * contract, because it decides which of two simultaneous faults is reported:
 *
 *   1. the body fits inside MAX_PROBE_BODY_BYTES;
 *   2. the response code is exactly 200 - the IETF health-check draft couples a
 *      passing status to a 2xx code, and this contract narrows that to one code;
 *   3. the body is JSON, with nothing trailing it;
 *   4. the body is a JSON OBJECT;
 *   5. it carries exactly PAYLOAD_KEYS, in that order;
 *   6. the four field rules of fieldRejection;
 *   7. no key appears twice and every member is a string - the check the strict
 *      reader exists for, applied last because it is the only one that cannot be
 *      stated against a parsed document.
 *
 * A parse is what makes this fail closed. The defect this replaces tested the raw
 * body for the fragment `"status":"UP"`, so a truncated, unparseable body that
 * happened to contain those bytes was graded healthy; every rule here is stated
 * against a parsed document instead.
 *
 * @param {number} status HTTP status code the endpoint answered with.
 * @param {Buffer|string} body Response body as received.
 * @returns {string|null} A fixed-category reason, or null when the answer is good.
 */
function probeRejection(status, body) {
  const buffer = Buffer.isBuffer(body)
    ? body
    : Buffer.from(body === undefined || body === null ? "" : String(body));
  if (buffer.length > MAX_PROBE_BODY_BYTES) {
    return `body exceeds the probe limit of ${MAX_PROBE_BODY_BYTES} bytes`;
  }
  if (status !== 200) {
    return `the endpoint answered status ${Number(status)}`;
  }
  const text = buffer.toString("utf8");
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    return "body is not the expected JSON document";
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    return "body is not a JSON object and carries no status field";
  }
  const keys = Object.keys(parsed);
  if (keys.length !== PAYLOAD_KEYS.length || keys.some((key, at) => key !== PAYLOAD_KEYS[at])) {
    return PROBE_KEY_SET_REASON;
  }
  const fieldReason = fieldRejection(parsed);
  if (fieldReason !== null) {
    return fieldReason;
  }
  if (readFlatStringObject(text) === null) {
    return "body is not the expected JSON document";
  }
  return null;
}

/** Self-probe deadline. Short, because a health check must answer quickly. */
const PROBE_TIMEOUT_MS = 2500;

/**
 * Requests this application's own health endpoint and reports the verdict as a
 * process exit code.
 *
 * This is what the container HEALTHCHECK runs, and it is deliberately strict: 0
 * is returned only when the endpoint answers 200 AND the body satisfies the
 * frozen contract. Every other outcome - refused connection, expired deadline,
 * wrong status code, oversized body, unparseable body, a document that merely
 * looks right, anything unforeseen - yields 1, because a probe that cannot PROVE
 * health must not report it.
 *
 * The exchange is bounded twice, because either bound alone can be defeated:
 *
 *   - an ABSOLUTE deadline, armed BEFORE the request object exists, so a name
 *     resolution or a connect that hangs is inside the budget rather than ahead
 *     of it. The request-level `timeout` option is an inactivity timer and cannot
 *     do this: a peer that sends one byte just inside every interval satisfies
 *     all of them and keeps the probe alive for as long as it likes;
 *   - a ceiling of MAX_PROBE_BODY_BYTES, enforced as chunks ARRIVE rather than
 *     after the body is complete, so an endpoint that streams without end is
 *     bounded in memory as well as in time.
 *
 * `http.request` is used rather than `fetch`, and that is also a security choice
 * rather than a stylistic one: `http.request` consults no proxy configuration,
 * whereas an environment-aware client can be redirected by an injected
 * `HTTP_PROXY` - which was demonstrated in the Python implementation to let a
 * fabricated document answer a self-check on behalf of a process that was not
 * running at all. A loopback self-check must never be proxied.
 *
 * The verdict is returned rather than passed to process.exit so the unit tests
 * can call probe() without killing the test runner.
 *
 * @param {{config?: object, host?: string, port?: number|string, timeout?: number,
 *          file?: string, env?: Record<string, string|undefined>}} [options]
 * @returns {Promise<number>} 0 when healthy, 1 otherwise.
 */
function probe(options = {}) {
  let config;
  try {
    config = options.config === undefined ? loadConfig(options) : options.config;
  } catch {
    // loadConfig only throws for an unusable port in the resolved configuration.
    // Reporting it as "unreachable" would send an operator looking for a network
    // fault instead of at the typo, so it is named as the configuration fault it
    // is - without naming the offending value.
    warn("probe cannot run: the configured port is unusable");
    return Promise.resolve(1);
  }
  // The same validation the server applies, applied here too. A probe that
  // accepted a configuration the server refuses would report a process healthy
  // that cannot start, which is the most misleading verdict available. This runs
  // FIRST among the checks below, so a value carrying a CR and an LF is refused
  // before it can be interpolated into anything.
  try {
    validateConfig(config);
  } catch (error) {
    warn(`probe cannot run: ${(error && error.message) || "the configuration is unusable"}`);
    return Promise.resolve(1);
  }
  // The destination is selected from a fixed allowlist rather than taken from
  // configuration; a value outside it is replaced, not honoured.
  const host = probeAuthority(options.host === undefined ? config.host : options.host);
  let port;
  try {
    port = options.port === undefined ? config.port : resolvePort([String(options.port)]);
  } catch {
    warn("probe cannot run: the configured port is unusable");
    return Promise.resolve(1);
  }
  // The NORMALISED route, which is the one the listener actually answers on, so
  // the probe cannot ask for a target the endpoint would 404.
  const route = normalizePath(config.healthPath);
  if (!isRequestTarget(route)) {
    warn("probe cannot run: the configured health path is not a valid request target");
    return Promise.resolve(1);
  }
  const timeout = options.timeout === undefined ? PROBE_TIMEOUT_MS : Number(options.timeout);
  const target = `http://${host}:${port}${route}`;

  return new Promise((resolve) => {
    let settled = false;
    let request = null;
    let deadline = null;

    /**
     * Settles the verdict exactly once and releases everything the probe holds.
     *
     * Every exit from the exchange comes through here, which is what makes the
     * 0/1 contract total: the timer is cleared so the process is not held open,
     * the request is destroyed so no descriptor outlives the verdict, and a
     * second call from a later event is ignored.
     *
     * @param {number} code 0 when healthy, 1 otherwise.
     * @param {string} [detail] Fixed-category diagnostic for an unhealthy verdict.
     * @returns {void}
     */
    const finish = (code, detail) => {
      if (settled) {
        return;
      }
      settled = true;
      if (deadline !== null) {
        clearTimeout(deadline);
        deadline = null;
      }
      if (detail !== undefined) {
        warn(detail);
      }
      if (request !== null) {
        try {
          request.destroy();
        } catch {
          // The request was already torn down by the peer or by the runtime.
          // There is nothing left to release and nothing to report.
        }
      }
      resolve(code);
    };

    deadline = setTimeout(
      () => finish(1, "probe rejected: no response within the probe deadline"),
      timeout,
    );

    request = http.request(
      {
        host,
        port,
        path: route,
        method: ALLOWED_METHODS,
        timeout,
        headers: { Accept: CONTENT_TYPE },
      },
      (res) => {
        let received = 0;
        const chunks = [];
        res.on("data", (chunk) => {
          received += chunk.length;
          // One byte past the ceiling is kept, so probeRejection can still see
          // that the limit was passed and report it as a size fault rather than
          // as a truncated document. Settled before the stream is destroyed:
          // destroying it emits an error of its own, and finish() keeps the first
          // verdict, so the order is what decides whether the reported category
          // is the real reason or a symptom of the teardown.
          if (received > MAX_PROBE_BODY_BYTES) {
            finish(
              1,
              `probe rejected: body exceeds the probe limit of ${MAX_PROBE_BODY_BYTES} bytes`,
            );
            res.destroy();
            return;
          }
          chunks.push(chunk);
        });
        res.on("aborted", () => finish(1, `probe could not reach ${target}: aborted`));
        res.on("error", (error) =>
          finish(1, `probe could not reach ${target}: ${error.code || "read failed"}`),
        );
        res.on("end", () => {
          const rejection = probeRejection(res.statusCode, Buffer.concat(chunks));
          if (rejection === null) {
            finish(0);
            return;
          }
          finish(1, `probe rejected: ${rejection}`);
        });
      },
    );

    request.on("timeout", () => {
      // The inactivity timer, which the absolute deadline above supersedes; it is
      // kept because it releases the socket the moment the peer goes quiet rather
      // than waiting for the deadline to expire.
      finish(1, "probe rejected: no response within the probe deadline");
    });
    // The error CODE is reported and the message is not: a code is a fixed
    // enumerated value, whereas a message can carry resolver-derived text.
    request.on("error", (error) =>
      finish(1, `probe could not reach ${target}: ${(error && error.code) || "request failed"}`),
    );
    request.end();
  });
}

/* -------------------------------------------------------------------------- *
 * Entry point
 * -------------------------------------------------------------------------- *
 * The guard is what makes this file both runnable and importable: requiring it
 * produces no output at all, while running it keeps the behaviour it has always
 * had. An unrecognised flag falls through to the default branch - the legacy
 * invocation never fails, and there is no usage error to print.
 * -------------------------------------------------------------------------- */

if (require.main === module) {
  const args = process.argv.slice(2);
  if (args.includes("--serve")) {
    try {
      serve();
    } catch (error) {
      // A rejected port or an unreadable configuration is fatal: fail closed
      // rather than serving a default nobody asked for. The exit code is set
      // rather than forced so this line is flushed before the process ends.
      warn(`cannot start the health server: ${(error && error.message) || error}`);
      process.exitCode = 1;
    }
  } else if (args.includes("--probe")) {
    probe()
      .then((code) => {
        process.exit(code);
      })
      .catch((error) => {
        // probe() resolves rather than rejecting, so this branch is defence in
        // depth: an unexpected failure must still exit non-zero (fail closed).
        warn(`health probe failed: ${(error && error.message) || error}`);
        process.exit(1);
      });
  } else {
    // The six statements below are the original program, moved verbatim out of
    // module scope and into this branch. Same statements, same order, same
    // count: the five writes are the output contract, hashed by a committed
    // baseline, so they are neither de-duplicated nor collapsed into a loop.
    const result = add(5, 7);
    console.log(result);
    console.log(result);
    console.log(result);
    console.log(result);
    console.log(result);
  }
}

/**
 * Public API. `buildPayload`/`healthPayload` and `createServer`/`buildServer`
 * are the same functions under both of the names the contract documents, so a
 * consumer written against either name resolves.
 *
 * `validateConfig`, `probeAuthority`, `probeRejection` and `MAX_PROBE_BODY_BYTES`
 * are exported because each is a rule the test suite has to be able to state
 * directly. A rule reachable only through a live socket can be asserted for one
 * happy path and guessed at for the rest; reachable as a function, every branch of
 * it is a test - and the same names are reachable in app.py and UserTest.java, so
 * the three suites assert one contract rather than three dialects of it.
 */
module.exports = {
  add,
  loadConfig,
  parseProperties,
  isSingleLineText,
  isRequestTarget,
  validateConfig,
  currentTimestamp,
  buildPayload,
  healthPayload: buildPayload,
  renderPayload,
  normalizePath,
  stripAuthority,
  sanitizeForLog,
  createServer,
  buildServer: createServer,
  serve,
  probe,
  probeAuthority,
  probeRejection,
  CONFIG_FILE,
  DEFAULTS,
  ENV_KEYS,
  HEALTH_STATUS,
  CONTENT_TYPE,
  CACHE_CONTROL,
  MAX_PROBE_BODY_BYTES,
};
