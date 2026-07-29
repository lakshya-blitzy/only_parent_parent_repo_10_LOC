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
 * Request validation is owned by this file rather than delegated to the
 * runtime, because Node's default error paths emit responses that are off the
 * frozen contract: a bare "400 Bad Request" with no body for anything the
 * parser rejects, a chunked 400 carrying a `Date` header for a missing Host,
 * and no response at all for CONNECT. Every one of those paths is intercepted
 * here so that a client always receives the same three headers and the same
 * JSON error shape the Python and Java implementations return:
 *
 *   400 Bad Request                    malformed request line, target or Host
 *   404 Not Found                      any target other than the health route
 *   405 Method Not Allowed             any method other than GET (+ Allow: GET)
 *   414 URI Too Long                   request line >= 65536 bytes
 *   431 Request Header Fields Too Large  header block, line or field cap exceeded
 *   505 HTTP Version Not Supported     well-formed version with major != 1
 *
 * Node's HTTP parser (llhttp) is stricter than the hand-written parsers in
 * app.py and User.java on three inputs, and more lenient on one. The
 * divergences are accepted rather than papered over, because reconstructing a
 * request the primary parser rejected would mean answering 200 on the strength
 * of a second, unproven parser - a fail-open behaviour. Answering 400 is
 * fail-closed, and every one of these responses is still on contract:
 *
 *   bare LF line endings      400 here, 200 in Python/Java. RFC 9112 section
 *                             2.2 makes LF-only recognition a MAY, so both are
 *                             conformant.
 *   obs-fold continuation     400 here, 200 in Python/Java. RFC 9112 section
 *                             5.2 names 400 as the preferred rejection.
 *   invalid Content-Length    400 here for a GET; a non-GET still resolves to
 *                             405 from the request line, matching the others.
 *   >1 leading empty line     200 here, 400 in Python/Java. RFC 9112 section
 *                             2.2 asks a server to ignore "at least one".
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
 * Returns the first argument that is a non-empty string, trimmed; undefined
 * when there is none.
 *
 * An environment variable set to the empty string is treated as absent rather
 * than as an override to the empty string: the response contract requires a
 * non-empty name and a dotted version, so an empty value must fall through to
 * the next source instead of producing a payload that violates the contract.
 *
 * @param {...unknown} candidates Values in precedence order.
 * @returns {string|undefined} The first usable value.
 */
function firstNonEmpty(...candidates) {
  for (const candidate of candidates) {
    if (typeof candidate === "string" && candidate.trim() !== "") {
      return candidate.trim();
    }
  }
  return undefined;
}

/**
 * Writes a diagnostic to stderr.
 *
 * Every message this module emits goes through here, because stdout carries
 * the legacy output that a committed hash asserts byte for byte - a single
 * stray console.log would break backward compatibility.
 *
 * @param {string} message Human-readable diagnostic, without a trailing newline.
 * @returns {void}
 */
function warn(message) {
  process.stderr.write(`index.js: ${message}\n`);
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
      warn(`could not read ${file} (${error.code || error.message}); using defaults`);
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
 * Port 0 is legal: it is how a test binds an ephemeral port and reads the
 * assignment back from the server.
 *
 * @param {Array<unknown>} candidates Values in precedence order.
 * @returns {number} A valid TCP port number.
 * @throws {RangeError} When the winning candidate is not a port in 0-65535.
 */
function resolvePort(candidates) {
  for (const candidate of candidates) {
    const value = firstNonEmpty(candidate);
    if (value === undefined) {
      continue;
    }
    if (!/^[+-]?\d+$/.test(value)) {
      throw new RangeError(`invalid port ${JSON.stringify(value)}: expected an integer`);
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
const BAD_REQUEST_BODY = JSON.stringify({ error: "Bad Request" });
const URI_TOO_LONG_BODY = JSON.stringify({ error: "URI Too Long" });
const HEADERS_TOO_LARGE_BODY = JSON.stringify({
  error: "Request Header Fields Too Large",
});
const VERSION_NOT_SUPPORTED_BODY = JSON.stringify({
  error: "HTTP Version Not Supported",
});

/* -------------------------------------------------------------------------- *
 * Request validation - limits and grammar shared with app.py and User.java
 * -------------------------------------------------------------------------- */

/** Root path, and the value an empty target normalises to. */
const ROOT_PATH = "/";

/** Line terminator, and the version token every response status line carries. */
const CRLF = "\r\n";
const HTTP_VERSION_LINE = "HTTP/1.1";

/** Separator that marks an absolute-form request target ("http://host/path"). */
const SCHEME_SEPARATOR = "://";

/**
 * Size limits, identical to the constants in User.java and app.py so that a
 * request at a boundary is answered with the same status by all three servers.
 *
 * A request line of exactly 65535 bytes is accepted (it is merely routed, and
 * will normally 404); 65536 or more is 414. A single header line of 16384 bytes
 * or more is 431, as is a header block above 16384 bytes in total or a field
 * count above 100.
 */
const MAX_REQUEST_LINE_BYTES = 65536;
const MAX_HEADER_LINE_BYTES = 16384;
const MAX_HEADER_BLOCK_BYTES = 16384;
const MAX_HEADER_FIELDS = 100;

/**
 * Parser buffer cap, set well above every limit above.
 *
 * The default (16 KiB) makes llhttp reject an over-long request line before the
 * handler ever runs, which would answer 431 where the other two servers answer
 * 414. Raising the cap lets an oversized-but-parseable request reach the
 * handler, where the limits above classify it exactly as they do elsewhere. The
 * largest request this policy can accept is a 65535-byte request line plus a
 * 16384-byte header block, so 128 KiB leaves generous headroom while still
 * bounding what a single connection may buffer.
 */
const MAX_HEADER_SIZE = 131072;

/** Idle timeout for a persistent connection, matching the other two servers. */
const KEEP_ALIVE_TIMEOUT_MS = 30000;

/**
 * Largest request body that is read and discarded before an answer is written.
 *
 * The endpoint never uses a request body, but it must still read one. A client
 * that sends "POST /health" with a megabyte of content and "Connection: close"
 * is still uploading when the 405 is ready, and answering-then-closing at that
 * moment resets the connection: the client's send fails and the response it
 * needed is lost. Draining first turns that reset into a readable answer.
 *
 * The drain is bounded so a declared body cannot occupy a connection
 * indefinitely; past the bound the answer is written and the connection is
 * retired, which is the same trade-off app.py and User.java make.
 */
const MAX_DRAIN_BYTES = 8 * 1024 * 1024;

/**
 * Marker under which a decided-but-unwritten answer is parked on its socket.
 *
 * A request whose body is truncated reaches the handler first - so its answer is
 * already known - and only then raises a parser error. Node delivers that error
 * before the request stream reports the abort, so without this marker the error
 * path would overwrite a correct 405 with a 400. Parking the answer lets the
 * error path write what was already decided, which is what app.py and User.java
 * do: they classify from the request line, and a truncated body does not change
 * that classification.
 */
const PENDING_ANSWER = Symbol("pendingAnswer");

/**
 * Punctuation permitted in an RFC 9110 token, which is the grammar a method
 * name must satisfy. A method built only from these characters and ALPHA/DIGIT
 * is well formed but unsupported, so it earns 405; anything else is malformed
 * and earns 400.
 */
const METHOD_TOKEN_SPECIALS = "!#$%&'*+-.^_`|~";

/**
 * A request target must be visible US-ASCII. Space terminates the target, and
 * every other byte outside this range - control characters, TAB, and anything
 * above 0x7E - makes the request line malformed.
 */
const TARGET_MIN_CHAR = 0x21;
const TARGET_MAX_CHAR = 0x7e;

/** A well-formed HTTP version token, captured so the major digit can be read. */
const VERSION_PATTERN = /^HTTP\/(\d+)\.(\d+)$/;

/**
 * The version Node reports for a two-token request line ("GET /health"). That
 * form is HTTP/0.9, which this policy rejects as malformed rather than
 * unsupported, matching app.py and User.java.
 */
const HTTP_09_VERSION = "0.9";

/**
 * Reason phrases for the status codes written directly to a socket, where no
 * ServerResponse object exists to supply one.
 */
const REASON_PHRASES = Object.freeze({
  400: "Bad Request",
  405: "Method Not Allowed",
  414: "URI Too Long",
  431: "Request Header Fields Too Large",
  505: "HTTP Version Not Supported",
});

/** Response body for each status code written directly to a socket. */
const ERROR_BODIES = Object.freeze({
  400: BAD_REQUEST_BODY,
  405: METHOD_NOT_ALLOWED_BODY,
  414: URI_TOO_LONG_BODY,
  431: HEADERS_TOO_LARGE_BODY,
  505: VERSION_NOT_SUPPORTED_BODY,
});

/**
 * The statuses that end the connection and announce it.
 *
 * These four say the byte stream itself could not be understood, so the parser
 * cannot know where the next request would begin and reuse is unsafe. They are
 * the only responses that carry a Connection header; 200, 404 and 405 never do,
 * because a routing or method decision leaves the stream perfectly parseable.
 * The split is by status code alone, so it is identical in all three languages
 * regardless of which internal path produced the response.
 */
const CLOSING_STATUSES = Object.freeze([400, 414, 431, 505]);

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
 * Reports whether a character is an ASCII letter.
 *
 * @param {string} character Single character.
 * @returns {boolean} True for A-Z or a-z.
 */
function isAsciiLetter(character) {
  return (character >= "A" && character <= "Z") || (character >= "a" && character <= "z");
}

/**
 * Reports whether a character is an ASCII digit.
 *
 * @param {string} character Single character.
 * @returns {boolean} True for 0-9.
 */
function isAsciiDigit(character) {
  return character >= "0" && character <= "9";
}

/**
 * Reports whether a string is a URI scheme: ALPHA followed by any number of
 * ALPHA, DIGIT, "+", "-" or ".".
 *
 * Checking the scheme is what stops "//health" being mistaken for an
 * absolute-form target: the text before the "://" must look like a scheme
 * before any authority is stripped.
 *
 * @param {string} candidate Text before the "://" separator.
 * @returns {boolean} True when the text is a syntactically valid scheme.
 */
function isScheme(candidate) {
  if (candidate === "" || !isAsciiLetter(candidate.charAt(0))) {
    return false;
  }
  for (let index = 1; index < candidate.length; index += 1) {
    const current = candidate.charAt(index);
    const allowed =
      isAsciiLetter(current) ||
      isAsciiDigit(current) ||
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
 * Reduces an absolute-form request target to its path.
 *
 * RFC 9112 permits "GET http://host/health HTTP/1.1" against an origin server,
 * and a proxy-aware client may send exactly that. The authority is dropped so
 * the path routes identically to the origin form. A target that is not
 * absolute-form - including "//health", whose "" prefix is not a scheme - is
 * returned unchanged.
 *
 * @param {string} target Raw request target.
 * @returns {string} Target with any scheme and authority removed.
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
 * Normalises a request target to a comparable path.
 *
 * The steps are, in order and identical to normalisePath in User.java and
 * normalize_path in app.py: drop any scheme and authority, drop everything from
 * the first "?", drop everything from the first "#", then remove exactly one
 * trailing slash when something is left in front of it.
 *
 * What the function deliberately does NOT do matters as much as what it does.
 * It performs no percent-decoding, so "/health%2f" stays distinct from
 * "/health/"; it resolves no dot segments, so "/health/../health" does not
 * reach the route; and it collapses no leading slashes, so "//health" and
 * "///health" are distinct from "/health". Each of those transformations would
 * widen the route to targets an operator did not configure.
 *
 * The function is pure, which is what lets the tests assert its behaviour
 * directly without a server.
 *
 * @param {string} rawUrl Raw request target, e.g. req.url.
 * @returns {string} Normalised path, always starting with "/".
 */
function normalizePath(rawUrl) {
  const raw = typeof rawUrl === "string" && rawUrl !== "" ? rawUrl : ROOT_PATH;
  let path = stripAuthority(raw);
  const queryIndex = path.indexOf("?");
  if (queryIndex >= 0) {
    path = path.slice(0, queryIndex);
  }
  const fragmentIndex = path.indexOf("#");
  if (fragmentIndex >= 0) {
    path = path.slice(0, fragmentIndex);
  }
  if (path.length > 1 && path.endsWith(ROOT_PATH)) {
    path = path.slice(0, -1);
  }
  return path === "" ? ROOT_PATH : path;
}

/**
 * Reports whether a method name is a well-formed RFC 9110 token.
 *
 * The distinction decides the status code: a token Node does not recognise
 * ("FOO", or the lowercase "get") is a valid but unsupported method and earns
 * 405 with an Allow header, while a non-token ("<script>alert(1)</script>") is
 * a malformed request line and earns 400. Answering 405 to a non-token would
 * imply the request was understood.
 *
 * @param {string} method Candidate method name.
 * @returns {boolean} True when every character is tchar and the name is non-empty.
 */
function isMethodToken(method) {
  if (typeof method !== "string" || method === "") {
    return false;
  }
  for (const character of method) {
    const allowed =
      isAsciiLetter(character) ||
      isAsciiDigit(character) ||
      METHOD_TOKEN_SPECIALS.includes(character);
    if (!allowed) {
      return false;
    }
  }
  return true;
}

/**
 * Reports whether a request target is non-empty visible US-ASCII.
 *
 * A target carrying a TAB, a control character or any byte above 0x7E is
 * malformed: such a request line cannot be unambiguously delimited, and
 * accepting it would let a caller smuggle a second line past the parser.
 *
 * @param {string} target Candidate request target.
 * @returns {boolean} True when the target is safe to route.
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
 * Writes a JSON response with exactly the contract headers.
 *
 * `sendDate` is switched off because Node would otherwise add a Date header
 * that neither the Python nor the Java implementation emits. `Connection` is
 * removed for the same reason: Node adds `Connection: keep-alive` plus a
 * `Keep-Alive` header on a persistent HTTP/1.1 response, which the other two
 * servers do not, and removing it before writeHead() stops both from being
 * computed. Persistence itself is unaffected - it is governed by the parser's
 * own keep-alive decision, not by the header this function suppresses - so the
 * connection is still reused across requests. No Server header is ever set, so
 * the runtime version is not advertised.
 *
 * The result is the same three-header set in all three languages:
 * Content-Type, Cache-Control and Content-Length, plus Allow on a 405.
 *
 * @param {import("node:http").ServerResponse} res Response to write.
 * @param {number} statusCode HTTP status code.
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
  if (CLOSING_STATUSES.includes(statusCode)) {
    // Setting the header back is what makes Node end the connection after this
    // response, which is the behaviour these four statuses require.
    headers.Connection = "close";
  }
  if (extraHeaders) {
    Object.assign(headers, extraHeaders);
  }
  res.writeHead(statusCode, headers);
  res.end(body);
}

/**
 * Writes a contract error response straight to a socket and closes it.
 *
 * Used on the paths where no ServerResponse exists: a request the parser
 * rejected, and a CONNECT tunnel request. The status line, the three contract
 * headers and the JSON body are identical to what writeJson would have
 * produced, so a caller cannot tell which path answered it - including the
 * Connection header, which is present for the four transport-error statuses and
 * absent for 405, exactly as writeJson and the other two servers arrange it.
 *
 * The socket is always closed afterwards, whether or not the close was
 * announced. A 405 raised from here follows a parser error, so the stream
 * cannot be resumed even though the status alone does not say so; app.py and
 * User.java likewise omit the header on a contract response that happens to be
 * the connection's last.
 *
 * The function is safe to call on a socket that has already been answered or
 * destroyed, which matters because a single malformed request can raise more
 * than one parser error (a bad Transfer-Encoding, for instance, is reported
 * after the response to that request has already been written).
 *
 * @param {import("node:net").Socket} socket Socket to answer.
 * @param {number} statusCode One of the codes in REASON_PHRASES.
 * @param {Record<string, string>} [extraHeaders] Additional headers, e.g. Allow.
 * @returns {void}
 */
function writeTransportError(socket, statusCode, extraHeaders) {
  if (!socket || socket.destroyed || !socket.writable) {
    if (socket && !socket.destroyed) {
      socket.destroy();
    }
    return;
  }
  const body = ERROR_BODIES[statusCode];
  const lines = [
    `${HTTP_VERSION_LINE} ${statusCode} ${REASON_PHRASES[statusCode]}`,
    `Content-Type: ${CONTENT_TYPE}`,
    `Cache-Control: ${CACHE_CONTROL}`,
    `Content-Length: ${Buffer.byteLength(body)}`,
  ];
  if (CLOSING_STATUSES.includes(statusCode)) {
    lines.push("Connection: close");
  }
  if (extraHeaders) {
    for (const [name, value] of Object.entries(extraHeaders)) {
      lines.push(`${name}: ${value}`);
    }
  }
  try {
    socket.end(`${lines.join(CRLF)}${CRLF}${CRLF}${body}`);
  } catch {
    // The peer vanished mid-write. There is nothing left to report to and
    // nothing to clean up beyond the socket itself.
    socket.destroy();
  }
}

/**
 * Reconstructs the byte length of the request line Node parsed.
 *
 * Node exposes the method, the raw target and the version separately rather
 * than the line it read, but the line is exactly those three joined by single
 * spaces, so the length is recoverable. It is needed because the 414 threshold
 * is defined on the request line, and a request line of up to MAX_HEADER_SIZE
 * bytes now reaches the handler rather than being rejected by the parser.
 *
 * @param {import("node:http").IncomingMessage} req Request to measure.
 * @returns {number} Length in bytes of the request line, excluding CRLF.
 */
function requestLineLength(req) {
  return (
    Buffer.byteLength(req.method) +
    1 +
    Buffer.byteLength(req.url) +
    1 +
    Buffer.byteLength(`HTTP/${req.httpVersion}`)
  );
}

/**
 * Reports whether a request's header section exceeds any of the shared caps.
 *
 * Node's parser buffer is deliberately larger than every limit here, so the
 * limits are enforced in this function instead. The block is measured the way
 * the other two servers measure it - each field as "Name: Value" plus CRLF - so
 * a request at a boundary is answered with the same status everywhere. A single
 * over-long field is caught as well as an over-large total, because a 20 KiB
 * value in one header would otherwise slip past a block check that only sums.
 *
 * @param {import("node:http").IncomingMessage} req Request to check.
 * @returns {boolean} True when the request must be answered with 431.
 */
function headersTooLarge(req) {
  const raw = req.rawHeaders;
  if (raw.length / 2 > MAX_HEADER_FIELDS) {
    return true;
  }
  let block = 0;
  for (let index = 0; index < raw.length; index += 2) {
    const text = Buffer.byteLength(raw[index]) + 2 + Buffer.byteLength(raw[index + 1]);
    if (text >= MAX_HEADER_LINE_BYTES) {
      return true;
    }
    block += text + CRLF.length;
    if (block > MAX_HEADER_BLOCK_BYTES) {
      return true;
    }
  }
  return false;
}

/**
 * Reports whether a request declares a body that must be read before answering.
 *
 * A chunked request always has one. A Content-Length request has one unless the
 * length is zero. Every other request - notably the GET this endpoint exists to
 * serve - has none, and is answered without waiting for a stream event.
 *
 * @param {import("node:http").IncomingMessage} req Request to inspect.
 * @returns {boolean} True when a body is declared.
 */
function hasRequestBody(req) {
  if (req.headers["transfer-encoding"] !== undefined) {
    return true;
  }
  const declared = req.headers["content-length"];
  return declared !== undefined && declared !== "0";
}

/**
 * Reads and discards a declared request body, then writes the response.
 *
 * The answer is decided before the drain starts, so a slow or oversized upload
 * cannot change what the client is told - it only changes when the client is
 * told it. Once the bound is reached the answer is written immediately and the
 * connection is retired rather than reading further.
 *
 * @param {import("node:http").IncomingMessage} req Request being drained.
 * @param {import("node:http").ServerResponse} res Response to write.
 * @param {{status: number, body: string, headers?: Record<string, string>}} outcome
 *   The already-decided answer.
 * @returns {void}
 */
function drainThenRespond(req, res, outcome) {
  const socket = req.socket;
  let answered = false;
  let drained = 0;
  function respond() {
    if (answered) {
      return;
    }
    answered = true;
    if (socket) {
      socket[PENDING_ANSWER] = undefined;
    }
    if (res.writableEnded || !socket || socket.destroyed || !socket.writable) {
      return;
    }
    writeJson(res, outcome.status, outcome.body, outcome.headers);
  }
  // Park the answer before the first drain event, so a parser error raised for a
  // truncated body writes this answer instead of a generic 400.
  if (socket) {
    socket[PENDING_ANSWER] = respond;
  }
  req.on("data", (chunk) => {
    drained += chunk.length;
    if (drained > MAX_DRAIN_BYTES) {
      respond();
      socket.destroy();
    }
  });
  req.on("end", respond);
  // A client that vanished outright leaves nothing to answer: retire the parked
  // answer so no later event writes to a dead socket.
  const abandon = () => {
    answered = true;
    if (socket) {
      socket[PENDING_ANSWER] = undefined;
    }
  };
  req.on("aborted", abandon);
  req.on("error", abandon);
}

/**
 * Classifies a request line that Node's parser rejected.
 *
 * The parser error code alone is not enough. A header overflow must be decided
 * from the code, because its raw packet is the mid-stream chunk that overflowed
 * rather than the head of the request. Everything else is decided by re-reading
 * the first line of the raw packet under the same grammar app.py and User.java
 * apply, which is what lets an unsupported-but-valid method still earn 405 and
 * a bad version still earn 505 instead of a blanket 400.
 *
 * HPE_INVALID_VERSION deserves particular care: Node raises it both for a
 * malformed version token and for a request whose lines end in a bare LF, and
 * in the second case the version token is perfectly well formed. The three arms
 * below keep those apart - malformed token 400, well-formed non-1 major 505,
 * well-formed 1.x (the bare-LF case) 400.
 *
 * @param {Error & {code?: string, rawPacket?: Buffer}} error Parser error.
 * @returns {{status: number, headers?: Record<string, string>}} Response to send.
 */
function classifyParserError(error) {
  const code = error && error.code;
  if (code === "HPE_HEADER_OVERFLOW") {
    return { status: 431 };
  }
  const raw = error && error.rawPacket ? error.rawPacket.toString("latin1") : "";
  const lineEnd = raw.indexOf("\n");
  const firstLine = (lineEnd < 0 ? raw : raw.slice(0, lineEnd)).replace(/\r$/, "");
  if (Buffer.byteLength(firstLine) >= MAX_REQUEST_LINE_BYTES) {
    return { status: 414 };
  }
  const parts = firstLine.split(" ");
  if (parts.length !== 3) {
    return { status: 400 };
  }
  const [method, target, version] = parts;
  if (!isMethodToken(method) || !isRequestTarget(target)) {
    return { status: 400 };
  }
  const versionMatch = VERSION_PATTERN.exec(version);
  if (versionMatch === null) {
    return { status: 400 };
  }
  if (Number(versionMatch[1]) !== 1) {
    return { status: 505 };
  }
  if (method !== ALLOWED_METHODS) {
    return { status: 405, headers: { Allow: ALLOWED_METHODS } };
  }
  // A well-formed GET/1.x line that the parser still rejected: bare LF
  // terminators, an obs-fold continuation, or an unparseable Content-Length.
  // The request is malformed even though its first line is not.
  return { status: 400 };
}

/**
 * Creates the health server.
 *
 * Two parser options are set deliberately. `requireHostHeader: false` moves the
 * missing-Host decision into this file: Node's own answer is a chunked 400 that
 * carries a Date header, which is off the contract, so the check is made below
 * instead. `maxHeaderSize` is raised above every size limit so an oversized but
 * parseable request reaches the handler and is classified there rather than by
 * the parser, which would report every overflow as 431 where the other two
 * servers report 414 for an over-long request line.
 *
 * Validation order matches serveExchange in User.java and handle_one_request in
 * app.py exactly, because the status a boundary request receives depends on it:
 * request-line length, then request-line grammar, then version, then header
 * limits, then Host, then method, then route. Host is checked before the method
 * so a hostless POST answers 400 rather than 405.
 *
 * The server is returned rather than started, so the tests can drive it on an
 * ephemeral port (listen(0)) without competing for the configured one.
 *
 * @param {ReturnType<typeof loadConfig>} [config] Effective configuration.
 * @returns {import("node:http").Server} An unstarted HTTP server.
 */
function createServer(config) {
  const resolved = config === undefined || config === null ? loadConfig() : config;
  const routePath = normalizePath(resolved.healthPath);
  const options = { requireHostHeader: false, maxHeaderSize: MAX_HEADER_SIZE };

  /**
   * Decides the answer for one request without writing anything.
   *
   * The eight checks run in the same order as serveExchange in User.java and
   * handle_one_request in app.py. The order is part of the contract: it decides
   * which status a request that violates two rules at once receives.
   *
   * @param {import("node:http").IncomingMessage} req Request to classify.
   * @returns {{status: number, body: string, headers?: Record<string, string>}}
   */
  const classify = (req) => {
    if (requestLineLength(req) >= MAX_REQUEST_LINE_BYTES) {
      return { status: 414, body: URI_TOO_LONG_BODY };
    }
    // Defence in depth: llhttp rejects a non-token method and a target carrying
    // a control character before the handler runs, but a target byte above 0x7E
    // can still arrive here, and the other two servers refuse it.
    if (!isMethodToken(req.method) || !isRequestTarget(req.url)) {
      return { status: 400, body: BAD_REQUEST_BODY };
    }
    // HTTP/0.9 has no version token at all, which this policy treats as a
    // malformed request line; any other major version is understood but
    // unsupported. Node reports 0.9 for a two-token request line, so the two
    // cases are indistinguishable here and both resolve to 400.
    if (req.httpVersion === HTTP_09_VERSION) {
      return { status: 400, body: BAD_REQUEST_BODY };
    }
    if (req.httpVersionMajor !== 1) {
      return { status: 505, body: VERSION_NOT_SUPPORTED_BODY };
    }
    if (headersTooLarge(req)) {
      return { status: 431, body: HEADERS_TOO_LARGE_BODY };
    }
    // Host is mandatory from HTTP/1.1 onwards and optional in 1.0. It is
    // checked before the method so a hostless POST answers 400, not 405.
    if (req.httpVersionMinor >= 1 && req.headers.host === undefined) {
      return { status: 400, body: BAD_REQUEST_BODY };
    }
    if (req.method !== ALLOWED_METHODS) {
      return {
        status: 405,
        body: METHOD_NOT_ALLOWED_BODY,
        headers: { Allow: ALLOWED_METHODS },
      };
    }
    if (normalizePath(req.url) !== routePath) {
      return { status: 404, body: NOT_FOUND_BODY };
    }
    return { status: 200, body: renderPayload(resolved) };
  };

  const server = http.createServer(options, (req, res) => {
    const outcome = classify(req);
    if (hasRequestBody(req)) {
      drainThenRespond(req, res, outcome);
      return;
    }
    writeJson(res, outcome.status, outcome.body, outcome.headers);
  });

  // A malformed request line or header block never reaches the handler above.
  // Answer on contract - same headers, same JSON shape - echoing nothing back
  // from the error, so a caller learns no more from a rejected request than
  // from an accepted one.
  server.on("clientError", (error, socket) => {
    if (error && error.code === "ECONNRESET") {
      socket.destroy();
      return;
    }
    const pending = socket && socket[PENDING_ANSWER];
    if (typeof pending === "function") {
      // This request already reached the handler, so its answer is decided; the
      // error concerns the body that followed, which cannot change it.
      pending();
      return;
    }
    const outcome = classifyParserError(error);
    writeTransportError(socket, outcome.status, outcome.headers);
  });

  // CONNECT never reaches the request handler: Node routes it to this event and
  // closes the socket with no response at all when nothing is listening. The
  // endpoint is GET-only, so the tunnel request is refused the same way any
  // other unsupported method is.
  server.on("connect", (req, socket) => {
    writeTransportError(socket, 405, { Allow: ALLOWED_METHODS });
  });

  // Match the idle timeout the other two servers apply to a kept-alive socket,
  // so a client that opens a connection and never speaks is reclaimed rather
  // than holding a slot indefinitely.
  server.keepAliveTimeout = KEEP_ALIVE_TIMEOUT_MS;

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
 */
function serve(options = {}) {
  const config = options.config === undefined ? loadConfig(options) : options.config;
  const host = options.host === undefined ? config.host : options.host;
  const port = options.port === undefined ? config.port : resolvePort([String(options.port)]);
  const server = createServer(config);

  server.on("error", (error) => {
    // Almost always a port already in use. Report it as one readable line and
    // fail closed: an orchestrator that cannot bind must not see a success code.
    // The exit code is set rather than forced so the event loop drains and this
    // diagnostic is flushed before the process goes away.
    warn(
      `cannot start the health server: could not bind ${sanitizeForLog(String(host))}:${port} ` +
        `(${error.code || error.message})`,
    );
    process.exitCode = 1;
  });

  server.listen(port, host, () => {
    const address = server.address();
    const boundPort = address && typeof address === "object" ? address.port : port;
    // Configured values reach this line, so control characters are stripped
    // from them first: a health path carrying a CR and an LF would otherwise
    // forge an extra startup line in whatever collects this process's stderr.
    // The route printed is the NORMALISED one the listener actually answers on,
    // not the raw configured string, so the banner cannot promise a route that
    // does not exist.
    const route = sanitizeForLog(normalizePath(config.healthPath));
    warn(
      `health endpoint listening on http://${sanitizeForLog(String(host))}:${boundPort}${route}`,
    );
  });

  registerShutdown(server);
  return server;
}

/**
 * Translates a bind address into an address a client can connect to.
 *
 * A wildcard bind means "every interface", and no client can dial a wildcard,
 * so the self-probe targets loopback instead. A concrete bind address is used
 * as configured.
 *
 * @param {string} host Configured bind address.
 * @returns {string} Connectable host.
 */
function probeHost(host) {
  return WILDCARD_HOSTS.includes(host) ? LOOPBACK_HOST : host;
}

/** Self-probe timeout. Short, because a health check must answer quickly. */
const PROBE_TIMEOUT_MS = 2500;

/**
 * Requests this application's own health endpoint and reports the verdict as a
 * process exit code.
 *
 * This is what the container HEALTHCHECK runs. It exists because slim and JRE
 * base images ship neither curl nor wget, and adding one of them would enlarge
 * the image and widen its attack surface - the application already contains an
 * HTTP client, so it checks itself.
 *
 * The check is fail-closed: 0 is returned only when the endpoint answers 200
 * and the parsed body reports status "UP". A connection error, a timeout, a
 * non-200 status, a body that is not JSON and a body with the wrong status all
 * yield 1. Diagnostics go to stderr; the resolved value is returned rather than
 * passed to process.exit so the unit tests can call probe() without killing the
 * test runner.
 *
 * @param {{config?: object, host?: string, port?: number|string, timeout?: number,
 *          file?: string, env?: Record<string, string|undefined>}} [options]
 * @returns {Promise<number>} 0 when healthy, 1 otherwise.
 */
function probe(options = {}) {
  let config;
  let host;
  let port;
  try {
    config = options.config === undefined ? loadConfig(options) : options.config;
    host = probeHost(options.host === undefined ? config.host : options.host);
    port = options.port === undefined ? config.port : resolvePort([String(options.port)]);
  } catch (error) {
    // A misconfigured port would otherwise produce an unparseable URL and be
    // reported as "unreachable", sending an operator looking for a network
    // fault instead of at the typo. Name the offending value and fail closed;
    // the Python and Java probes both do exactly this.
    warn(`probe cannot run: ${(error && error.message) || error}`);
    return Promise.resolve(1);
  }
  const timeout = options.timeout === undefined ? PROBE_TIMEOUT_MS : Number(options.timeout);

  return new Promise((resolve) => {
    let settled = false;
    const finish = (code, detail) => {
      if (settled) {
        return;
      }
      settled = true;
      if (code !== 0) {
        warn(`health probe failed: ${detail}`);
      }
      resolve(code);
    };

    const request = http.request(
      {
        host,
        port,
        path: config.healthPath,
        method: ALLOWED_METHODS,
        timeout,
        headers: { Accept: CONTENT_TYPE },
      },
      (res) => {
        let body = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => {
          body += chunk;
        });
        res.on("aborted", () => finish(1, "response aborted"));
        res.on("end", () => {
          if (res.statusCode !== 200) {
            finish(1, `unexpected status code ${res.statusCode}`);
            return;
          }
          let parsed;
          try {
            parsed = JSON.parse(body);
          } catch {
            finish(1, "response body is not valid JSON");
            return;
          }
          if (parsed && parsed.status === HEALTH_STATUS) {
            finish(0);
            return;
          }
          finish(1, `status field is not "${HEALTH_STATUS}"`);
        });
      },
    );

    request.on("timeout", () => {
      request.destroy();
      finish(1, `no response within ${timeout} ms`);
    });
    request.on("error", (error) => finish(1, error.code || error.message));
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
 */
module.exports = {
  add,
  loadConfig,
  parseProperties,
  currentTimestamp,
  buildPayload,
  healthPayload: buildPayload,
  renderPayload,
  normalizePath,
  createServer,
  buildServer: createServer,
  serve,
  probe,
  CONFIG_FILE,
  DEFAULTS,
  ENV_KEYS,
  HEALTH_STATUS,
  CONTENT_TYPE,
  CACHE_CONTROL,
};

