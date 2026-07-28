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
 * The value handed to server.listen must be a number, and it must be a valid
 * one: listening on NaN would silently bind an arbitrary ephemeral port, which
 * is far worse than reporting the bad value and using the documented default.
 * Each rejected candidate is named on stderr so the misconfiguration is
 * visible, and resolution then continues down the chain.
 *
 * @param {Array<unknown>} candidates Values in precedence order.
 * @returns {number} A valid TCP port number.
 */
function resolvePort(candidates) {
  for (const candidate of candidates) {
    const value = firstNonEmpty(candidate);
    if (value === undefined) {
      continue;
    }
    const port = Number(value);
    if (Number.isInteger(port) && port >= PORT_MIN && port <= PORT_MAX) {
      return port;
    }
    warn(`ignoring invalid port value ${JSON.stringify(value)}`);
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
 * Everything from the first "?" is dropped, so "/health?verbose=1" matches, and
 * exactly one trailing slash is removed when something is left in front of it,
 * so "/health/" matches while "/health//" deliberately does not. The function
 * is pure, which is what lets the tests assert its behaviour directly without
 * a server.
 *
 * @param {string} rawUrl Raw request target, e.g. req.url.
 * @returns {string} Normalised path, always starting with "/".
 */
function normalizePath(rawUrl) {
  const raw = typeof rawUrl === "string" && rawUrl !== "" ? rawUrl : "/";
  const queryIndex = raw.indexOf("?");
  const withoutQuery = queryIndex === -1 ? raw : raw.slice(0, queryIndex);
  if (withoutQuery === "") {
    return "/";
  }
  if (withoutQuery.length > 1 && withoutQuery.endsWith("/")) {
    return withoutQuery.slice(0, -1);
  }
  return withoutQuery;
}

/* -------------------------------------------------------------------------- *
 * HTTP server
 * -------------------------------------------------------------------------- */

/**
 * Writes a JSON response with exactly the contract headers.
 *
 * `sendDate` is switched off because Node would otherwise add a Date header
 * that neither the Python nor the Java implementation emits; suppressing it
 * gives the three implementations an identical header-name set. No Server
 * header is ever set, so the runtime version is not advertised.
 *
 * @param {import("node:http").ServerResponse} res Response to write.
 * @param {number} statusCode HTTP status code.
 * @param {string} body Already-rendered JSON document.
 * @param {Record<string, string>} [extraHeaders] Additional headers, e.g. Allow.
 * @returns {void}
 */
function writeJson(res, statusCode, body, extraHeaders) {
  res.sendDate = false;
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
 * Creates the health server.
 *
 * Routing is deliberately explicit rather than table-driven: GET on the
 * configured path answers 200 with the payload, any other method answers 405
 * with an Allow header, and any other path answers 404. The configured path is
 * itself normalised once here, so a value written with a trailing slash still
 * matches the requests a client actually sends.
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

  const server = http.createServer((req, res) => {
    if (req.method !== ALLOWED_METHODS) {
      writeJson(res, 405, METHOD_NOT_ALLOWED_BODY, { Allow: ALLOWED_METHODS });
      return;
    }
    if (normalizePath(req.url) !== routePath) {
      writeJson(res, 404, NOT_FOUND_BODY);
      return;
    }
    writeJson(res, 200, renderPayload(resolved));
  });

  // A malformed request line or header block never reaches the handler above.
  // Answer with a bare 400 and close, echoing nothing back from the error.
  server.on("clientError", (error, socket) => {
    if ((error && error.code === "ECONNRESET") || !socket.writable) {
      socket.destroy();
      return;
    }
    socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
  });

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
    warn(`could not bind ${host}:${port} (${error.code || error.message})`);
    process.exitCode = 1;
  });

  server.listen(port, host, () => {
    const address = server.address();
    const boundPort = address && typeof address === "object" ? address.port : port;
    warn(`health endpoint listening on http://${host}:${boundPort}${config.healthPath}`);
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
  const config = options.config === undefined ? loadConfig(options) : options.config;
  const host = probeHost(options.host === undefined ? config.host : options.host);
  const port = options.port === undefined ? config.port : resolvePort([String(options.port)]);
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
    serve();
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

