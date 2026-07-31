const http = require("node:http");

const APP_NAME = "calculator-app";
const APP_VERSION = "1.0.0";

// Loopback only: with no deployment target, the listener stays off external interfaces.
const DEFAULT_HOST = "127.0.0.1";
const DEFAULT_PORT = 3000;

const HEALTH_PATH = "/health";
const HEALTH_METHODS = "GET, HEAD";

// One explicit grammar for the overrides - ASCII blanks trimmed, and a port written in
// ASCII decimal digits only - so every application reading them accepts the same values.
const ASCII_BLANKS = /^[ \t\n\v\f\r]+|[ \t\n\v\f\r]+$/g;
const PORT_DIGITS = /^[0-9]+$/;
const PORT_MAX_DIGITS = 5;

// Bounds and shapes the request line rejectedMethodResponse() re-reads when the runtime's
// parser refuses a method token: a 65536-byte window, a method token, a printable target.
const MAX_REQUEST_LINE = 65536;
const METHOD_TOKEN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;
const REQUEST_TARGET = /^[!-~]+$/;

function add(a, b) {
  return a + b;
}

const result = add(5, 7);
console.log(result);
console.log(result);
console.log(result);
console.log(result);
console.log(result);

function rawResponse(status, reason, token, allow) {
  // A parse failure and a CONNECT are handed a socket rather than a response object, so
  // these replies are assembled as raw HTTP/1.1 text.
  const body = '{"status":"' + token + '"}';
  return "HTTP/1.1 " + status + " " + reason + "\r\n" +
    // Built by hand, so this response supplies its own Date.
    "Date: " + new Date().toUTCString() + "\r\n" +
    "Content-Type: application/json\r\n" +
    "Content-Length: " + Buffer.byteLength(body) + "\r\n" +
    "Cache-Control: no-store\r\n" +
    (allow === undefined ? "" : "Allow: " + allow + "\r\n") +
    "Connection: close\r\n" +
    "\r\n" +
    body;
}

function malformedRequestResponse(code) {
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
  // toISOString() already renders millisecond UTC with a trailing Z, which is the contract.
  return new Date().toISOString();
}

function healthPayload() {
  // Insertion order is the contract order, and these four members are the whole document.
  return {
    name: APP_NAME,
    version: APP_VERSION,
    timestamp: healthTimestamp(),
    status: "UP"
  };
}

function requestPath(target) {
  // Only the query string is dropped, so /health?probe=1 matches while /health/ and
  // /health#extra are targets this program does not serve.
  return target.split("?")[0];
}

function sendJson(response, status, payload, withBody, allow, close) {
  // Every response built from a response object leaves through here; the raw-socket
  // paths above assemble their own text. JSON.stringify emits no padding, which is
  // what the compact wire contract requires.
  const body = JSON.stringify(payload);
  const headers = {
    "Content-Type": "application/json",
    // Buffer.byteLength, never String.length: the two part company as soon as a value
    // stops being ASCII.
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store"
  };
  if (allow !== undefined) {
    headers["Allow"] = allow;
  }
  if (close) {
    // Closing discards an unread request body, so it cannot be read as the next request
    // or hold this connection open after the listener has been asked to stop.
    headers["Connection"] = "close";
  }
  response.writeHead(status, headers);
  response.end(withBody ? body : undefined);
}

function handleRequest(request, response) {
  const path = requestPath(request.url || "");
  const withBody = request.method !== "HEAD";
  // The method is tested before the path so that a rejected method is answered the same
  // way on every target, not only on the one resource this application serves.
  if (request.method !== "GET" && request.method !== "HEAD") {
    rejectMethod(response, path, withBody);
    return;
  }
  if (path !== HEALTH_PATH) {
    sendJson(response, 404, { status: "NOT_FOUND" }, withBody);
    return;
  }
  sendJson(response, 200, healthPayload(), withBody);
}

function rejectMethod(response, path, withBody) {
  // The served resource answers 405 with its Allow header, every other target the same
  // 404, and either way the connection is closed.
  if (path !== HEALTH_PATH) {
    sendJson(response, 404, { status: "NOT_FOUND" }, withBody, undefined, true);
    return;
  }
  sendJson(response, 405, { status: "METHOD_NOT_ALLOWED" }, withBody, HEALTH_METHODS, true);
}

function connectResponse(request) {
  // CONNECT never reaches the request handler, so it is routed here on the same terms.
  return rejectionResponse(requestPath(request.url || ""));
}

function rejectionResponse(path) {
  // The one routing rule for an unsupported method, in raw text.
  if (path !== HEALTH_PATH) {
    return rawResponse(404, "Not Found", "NOT_FOUND");
  }
  return rawResponse(405, "Method Not Allowed", "METHOD_NOT_ALLOWED", HEALTH_METHODS);
}

function rejectedMethodResponse(error) {
  // An unknown but well-formed method is refused by the parser before the router sees it,
  // so a complete request line inside the bounded window is rejected here rather than
  // called malformed; every other parse failure keeps the runtime's own 4xx.
  if (error.code !== "HPE_INVALID_METHOD" || !Buffer.isBuffer(error.rawPacket)) {
    return null;
  }
  const head = error.rawPacket.subarray(0, MAX_REQUEST_LINE).toString("latin1");
  const end = head.indexOf("\r\n");
  if (end < 0) {
    return null;
  }
  const parts = head.slice(0, end).split(" ");
  if (parts.length !== 3 || !METHOD_TOKEN.test(parts[0]) ||
      !REQUEST_TARGET.test(parts[1]) ||
      (parts[2] !== "HTTP/1.1" && parts[2] !== "HTTP/1.0")) {
    return null;
  }
  return rejectionResponse(requestPath(parts[1]));
}

function trimBlanks(value) {
  return value.replace(ASCII_BLANKS, "");
}

function healthHost(requested) {
  if (requested === "") {
    return DEFAULT_HOST;
  }
  if (/[\p{C}\p{Z}]/u.test(requested)) {
    // A host name or address carries no blanks and no control characters, so anything
    // else is refused rather than repaired: \p{C} covers controls and formats, \p{Z}
    // every kind of blank and line separator.
    return null;
  }
  return requested;
}

function logSafe(text) {
  // Escaping every control and line-separator character keeps a diagnostic to one line
  // and stops supplied text from forging a log record.
  return text.replace(/[\p{C}\p{Zl}\p{Zp}]/gu, function (character) {
    return "\\u" + character.codePointAt(0).toString(16).padStart(4, "0");
  });
}

function healthPort(requested) {
  if (requested === "") {
    return DEFAULT_PORT;
  }
  // One to five ASCII decimal digits and nothing else, checked before any conversion, so
  // no hexadecimal, exponent or fullwidth form can reach Number().
  if (requested.length <= PORT_MAX_DIGITS && PORT_DIGITS.test(requested)) {
    const port = Number(requested);
    if (port > 0 && port < 65536) {
      return port;
    }
  }
  // A present but unusable override stops startup rather than silently binding the
  // default.
  return null;
}

function bindFailureReason(error) {
  // A listen error's message ends with the address it was asked to bind, which the caller
  // already names, so the duplicate suffix is dropped.
  const message = error.message || String(error);
  if (!error.address) {
    return message;
  }
  const suffix = error.port ? " " + error.address + ":" + error.port : " " + error.address;
  return message.endsWith(suffix) ? message.slice(0, message.length - suffix.length) : message;
}

function startHealthServer() {
  // Stopping is armed before the first startup step, so a signal arriving while the
  // overrides are read or the socket is bound still leaves through the shutdown path
  // below rather than taking the runtime's default action.
  let server = null;
  let stopping = false;
  const closeListener = function () {
    // Safe before the socket is up as well as after: close() cancels a bind in flight.
    if (server === null) {
      return;
    }
    server.close();
    // close() waits for existing connections, so idle ones are ended first and the rest
    // after, and no single client can defer the shutdown a supervisor asked for.
    if (typeof server.closeIdleConnections === "function") {
      server.closeIdleConnections();
    }
    if (typeof server.closeAllConnections === "function") {
      server.closeAllConnections();
    }
  };
  const dropSignalHandlers = function (handler) {
    // Restores the default action for a second signal and lets the event loop drain, so
    // diagnostics are never truncated by an explicit exit.
    process.removeListener("SIGINT", handler);
    process.removeListener("SIGTERM", handler);
  };
  const shutdown = function () {
    // One closure for both signals, and its body runs once however many arrive.
    if (stopping) {
      return;
    }
    stopping = true;
    process.stderr.write(logSafe(APP_NAME + ": shutting down") + "\n");
    dropSignalHandlers(shutdown);
    closeListener();
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
  const requestedHost = trimBlanks(process.env.HEALTH_HOST || "");
  const host = healthHost(requestedHost);
  if (host === null) {
    // One readable line and a non-zero status; the rejected value is never quoted back.
    process.stderr.write(logSafe(APP_NAME + ": invalid HEALTH_HOST: expected a host " +
      "name or address with no blanks and no control characters") + "\n");
    process.exitCode = 1;
    dropSignalHandlers(shutdown);
    return;
  }
  const requested = trimBlanks(process.env.HEALTH_PORT || "");
  const port = healthPort(requested);
  if (port === null) {
    process.stderr.write(logSafe(APP_NAME + ": invalid HEALTH_PORT: expected 1 to " +
      PORT_MAX_DIGITS + " decimal digits denoting a port from 1 to 65535") + "\n");
    process.exitCode = 1;
    dropSignalHandlers(shutdown);
    return;
  }
  server = http.createServer(handleRequest);
  server.on("error", function (error) {
    // One readable line and a non-zero status instead of a stack dump. A host that arrived
    // in HEALTH_HOST is named by its variable and reported by error code, because the
    // runtime's own message would quote the value it could not resolve.
    const where = host === DEFAULT_HOST ? host + ":" + port :
      "the address named by HEALTH_HOST, port " + port;
    const reason = host === DEFAULT_HOST ? bindFailureReason(error) :
      (error.code || "bind failed");
    process.stderr.write(logSafe(APP_NAME + ": cannot bind " + where + ": " + reason) + "\n");
    process.exitCode = 1;
    dropSignalHandlers(shutdown);
  });
  server.on("clientError", function (error, socket) {
    // A request the parser refused never reaches the router, so it is answered here as
    // raw text, and a connection the peer already dropped is discarded.
    const rejection = rejectedMethodResponse(error);
    process.stderr.write(logSafe(APP_NAME + (rejection === null ?
      ": client error: " : ": unsupported method: ") + error.message) + "\n");
    if (error.code === "ECONNRESET" || !socket.writable) {
      socket.destroy();
      return;
    }
    socket.end(rejection === null ? malformedRequestResponse(error.code) : rejection);
  });
  server.on("connect", function (request, socket) {
    // The runtime emits CONNECT here with a socket rather than a response object, and
    // destroys the connection if nothing answers it.
    socket.on("error", function (socketError) {
      process.stderr.write(logSafe(APP_NAME + ": connect socket error: " +
        socketError.message) + "\n");
      socket.destroy();
    });
    if (!socket.writable) {
      socket.destroy();
      return;
    }
    socket.end(connectResponse(request));
  });
  // listen() completes on a later turn of the event loop, so the callback below only has
  // to cope with a stop requested while the bind was in flight; the signal handlers
  // themselves were armed at the top of this function.
  server.listen(port, host, function () {
    if (stopping) {
      closeListener();
      return;
    }
    process.stderr.write(logSafe(APP_NAME + " " + APP_VERSION + " serving " + HEALTH_PATH +
      " on http://" + host + ":" + port) + "\n");
  });
}

// Opt-in: without --serve the five console writes above are the whole program.
if (process.argv.slice(2).includes("--serve")) { startHealthServer(); }
