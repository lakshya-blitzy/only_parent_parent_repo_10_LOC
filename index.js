const http = require("node:http");

// Identity reported by the health endpoint. A name and a version describe the
// artifact rather than the host it runs on, so neither is environment-overridable.
const APP_NAME = "calculator-app";
const APP_VERSION = "1.0.0";

// Bind defaults, overridable through the HEALTH_HOST and HEALTH_PORT environment
// variables so that the endpoint needs no configuration file. Loopback only: this
// program has no deployment target, so keeping the listener off external
// interfaces is the correct default.
const DEFAULT_HOST = "127.0.0.1";
const DEFAULT_PORT = 3000;

// The only route served, and the only methods answered on it.
const HEALTH_PATH = "/health";
const HEALTH_METHODS = "GET, HEAD";

function add(a, b) {
  return a + b;
}

const result = add(5, 7);
console.log(result);
console.log(result);
console.log(result);
console.log(result);
console.log(result);

function rawResponse(status, reason, token) {
  // A request the runtime could not parse never yields a response object, so this one
  // reply has to be assembled as raw HTTP/1.1 text and written straight to the socket
  // rather than through sendJson below.
  const body = '{"status":"' + token + '"}';
  return "HTTP/1.1 " + status + " " + reason + "\r\n" +
    // Every other response here gets its Date from the runtime. This one is built by
    // hand, so it has to supply its own: RFC 9110 requires an origin server with a
    // clock to date its 4xx responses, and the runtime's bare default does not.
    "Date: " + new Date().toUTCString() + "\r\n" +
    "Content-Type: application/json\r\n" +
    "Content-Length: " + Buffer.byteLength(body) + "\r\n" +
    "Cache-Control: no-store\r\n" +
    "Connection: close\r\n" +
    "\r\n" +
    body;
}

function clientErrorResponse(code) {
  // The runtime's own status choices are kept: 431 for an oversized header block, 408
  // for a connection that never completed a request, 400 for anything else it could
  // not parse. Only the body is upgraded, from the bare, bodyless default to the same
  // compact JSON document every other path returns. A switch rather than a lookup
  // table, so an unexpected code can only ever fall through to the default.
  switch (code) {
    case "HPE_HEADER_OVERFLOW":
      return rawResponse(431, "Request Header Fields Too Large", "REQUEST_HEADER_FIELDS_TOO_LARGE");
    case "ERR_HTTP_REQUEST_TIMEOUT":
      return rawResponse(408, "Request Timeout", "REQUEST_TIMEOUT");
    default:
      return rawResponse(400, "Bad Request", "BAD_REQUEST");
  }
}

function healthTimestamp() {
  // RFC 3339 / ISO 8601 UTC with millisecond precision and a trailing "Z", for
  // example 2026-07-31T08:43:36.492Z. toISOString already renders exactly that, so
  // no truncation is needed here, unlike the Python and Java implementations of this
  // same contract. Built per call, never cached.
  return new Date().toISOString();
}

function healthPayload() {
  // Exactly four string members and nothing else: no hostname, no filesystem path,
  // no environment data, no diagnostics. String keys keep their insertion order, so
  // JSON.stringify emits the contract order name, version, timestamp, status.
  return {
    name: APP_NAME,
    version: APP_VERSION,
    timestamp: healthTimestamp(),
    status: "UP"
  };
}

function requestPath(target) {
  // Route on the path alone, so /health?probe=1 still matches while /health/ and
  // every other path do not.
  return target.split("?")[0].split("#")[0];
}

function sendJson(response, status, payload, withBody, allow) {
  // Every response leaves through here, so the wire contract cannot drift between
  // routes. The serialisation is deliberately plain: JSON.stringify emits no padding,
  // which is what keeps this body byte-identical to the Python and Java bodies apart
  // from the name and the timestamp.
  const body = JSON.stringify(payload);
  const headers = {
    // Deliberately application/json and not the health-check draft's
    // application/health+json, whose media-type registration was never completed:
    // every client, monitor and browser parses this one with no special handling.
    "Content-Type": "application/json",
    // The length a GET would return, sent for HEAD as well so that both methods
    // answer with identical headers. Buffer.byteLength, never String.length: the two
    // part company the moment a value stops being ASCII.
    "Content-Length": Buffer.byteLength(body),
    // The timestamp is generated per request, so a cached copy is worthless. That is
    // why no freshness lifetime is offered here, unlike the draft's own example.
    "Cache-Control": "no-store"
  };
  if (allow !== undefined) {
    headers["Allow"] = allow;
  }
  response.writeHead(status, headers);
  // A response to HEAD carries the headers of the equivalent GET, no body.
  response.end(withBody ? body : undefined);
}

function handleRequest(request, response) {
  // One resource is served. Everything else is answered with a compact JSON error
  // document that reports nothing back about the request.
  const path = requestPath(request.url || "");
  const withBody = request.method !== "HEAD";
  if (path !== HEALTH_PATH) {
    sendJson(response, 404, { status: "NOT_FOUND" }, withBody);
    return;
  }
  if (request.method !== "GET" && request.method !== "HEAD") {
    sendJson(response, 405, { status: "METHOD_NOT_ALLOWED" }, withBody, HEALTH_METHODS);
    return;
  }
  sendJson(response, 200, healthPayload(), withBody);
}

function healthPort(requested) {
  // An unset, empty or whitespace-only HEALTH_PORT means "not overridden", which is
  // not a fault: the documented default applies, silently.
  if (requested === "") {
    return DEFAULT_PORT;
  }
  const port = Number(requested);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    // An override that is present but cannot be honoured is a configuration fault,
    // reported as null so that the caller stops startup. Falling back to the default
    // would answer a typo by silently moving the endpoint off the port that was asked
    // for: the process would report itself UP while the probe watching the intended
    // port saw nothing at all.
    return null;
  }
  return port;
}

function bindFailureReason(error) {
  // Node appends the address it was asked to bind to the message of a listen error,
  // for example "listen EADDRINUSE: address already in use 127.0.0.1:3000". The
  // caller already names that address, so drop the duplicate and keep the reason on
  // one readable line. No stack trace ever reaches the operator.
  const message = error.message || String(error);
  if (!error.address) {
    return message;
  }
  const suffix = error.port ? " " + error.address + ":" + error.port : " " + error.address;
  return message.endsWith(suffix) ? message.slice(0, message.length - suffix.length) : message;
}

function startHealthServer() {
  // Everything this function reports goes to file descriptor 2, so that descriptor 1
  // carries only the output the five console writes above have always produced.
  const host = (process.env.HEALTH_HOST || "").trim() || DEFAULT_HOST;
  const requested = (process.env.HEALTH_PORT || "").trim();
  const port = healthPort(requested);
  if (port === null) {
    // Reported in the same shape as the bind failure below, one readable line and a
    // non-zero status, so that no listener is ever started on an address nobody asked
    // for. JSON.stringify quotes the offending value, which keeps a stray space or
    // control character both visible and confined to this single line.
    process.stderr.write(APP_NAME + ": invalid HEALTH_PORT " + JSON.stringify(requested) +
      ": expected an integer from 1 to 65535\n");
    process.exitCode = 1;
    return;
  }
  const server = http.createServer(handleRequest);
  let stopping = false;
  const shutdown = function () {
    // One closure serves both signals, and it runs its body once however many
    // signals arrive.
    if (stopping) {
      return;
    }
    stopping = true;
    process.stderr.write(APP_NAME + ": shutting down\n");
    // Dropping both handlers restores the default action for a second interrupt and
    // lets the event loop drain, so the process ends with status 0 by itself once the
    // socket is closed, with every diagnostic already flushed. process.exit() would
    // risk truncating those writes whenever descriptor 2 is a pipe.
    process.removeListener("SIGINT", shutdown);
    process.removeListener("SIGTERM", shutdown);
    // Releases the listening socket, so the port is free from here on.
    server.close();
    // A keep-alive connection sitting between requests would otherwise hold the loop
    // open until it timed out. Guarded because this API arrived in Node 18.2.
    if (typeof server.closeIdleConnections === "function") {
      server.closeIdleConnections();
    }
  };
  server.on("error", function (error) {
    // Binding is the first operation in this program that can fail for reasons
    // outside its control, most often because the port is already taken. One readable
    // line and a non-zero status serve the operator better than a stack dump. No
    // signal handler is registered yet and no socket is listening, so the event loop
    // drains and the process ends with this status on its own.
    process.stderr.write(APP_NAME + ": cannot bind " + host + ":" + port + ": " +
      bindFailureReason(error) + "\n");
    process.exitCode = 1;
  });
  server.on("clientError", function (error, socket) {
    // A request Node could not parse never reaches the router. Answer it with JSON
    // like every other path, echoing nothing the client sent, and simply discard a
    // connection the peer has already dropped.
    process.stderr.write(APP_NAME + ": client error: " + error.message + "\n");
    if (error.code === "ECONNRESET" || !socket.writable) {
      socket.destroy();
      return;
    }
    socket.end(clientErrorResponse(error.code));
  });
  server.listen(port, host, function () {
    process.stderr.write(APP_NAME + " " + APP_VERSION + " serving " + HEALTH_PATH +
      " on http://" + host + ":" + port + "\n");
    // Registered only once the listener is up: before that there is nothing to shut
    // down, and a signal handle would keep a failed start alive instead of letting it
    // exit with the status reported above.
    process.on("SIGINT", shutdown);
    process.on("SIGTERM", shutdown);
  });
}

// Opt-in server: the endpoint runs only when --serve is passed, so the five console
// writes above stay byte-for-byte what they have always been and the default
// invocation still opens no socket and exits as soon as they are done.
if (process.argv.slice(2).includes("--serve")) { startHealthServer(); }
