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

// The blanks stripped from an override before it is read, and the digits a port may be
// written with. Both sets are spelled out rather than left to String.prototype.trim()
// and Number(), because those two disagree with the Python facilities app.py would
// otherwise reach for: trim() removes U+FEFF where str.strip() does not and leaves
// U+0085 where str.strip() removes it, and Number() accepts hexadecimal, octal, binary
// and exponent forms, so HEALTH_PORT=0x4be0 silently started this application on port
// 19424 while app.py refused the same value. An override has to mean the same thing to
// every application that reads it, so the grammar is stated here instead of inherited
// from a language.
const ASCII_BLANKS = /^[ \t\n\v\f\r]+|[ \t\n\v\f\r]+$/g;
const PORT_DIGITS = /^[0-9]+$/;
const PORT_MAX_DIGITS = 5;

// The shape a request line must have for this program to answer a request the runtime's
// own parser turned away, in rejectedMethodResponse below. MAX_REQUEST_LINE is the window
// that line has to end inside, and it is deliberately the 65536 bytes app.py's own
// http.server allows a request line, so the two applications agree about every request
// line a client can send rather than only about short ones; it also means a packet that
// carries no request line at all is never scanned past that point. An RFC 9110 method is
// one or more tchar and is case-sensitive, so "get" names a method this application does
// not serve rather than a spelling of GET. Both patterns are anchored single character
// classes, so matching is linear in the length of the bounded input. A request target is
// only routed and then discarded, so it need only be recognisable: visible ASCII, no
// spaces and no control characters.
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
  // A request the runtime could not parse, and a CONNECT the runtime hands straight to
  // a socket, never yield a response object, so these replies have to be assembled as
  // raw HTTP/1.1 text and written straight to the socket rather than through sendJson
  // below.
  const body = '{"status":"' + token + '"}';
  return "HTTP/1.1 " + status + " " + reason + "\r\n" +
    // Every other response here gets its Date from the runtime. This one is built by
    // hand, so it has to supply its own: RFC 9110 requires an origin server with a
    // clock to date its 4xx responses, and the runtime's bare default does not.
    "Date: " + new Date().toUTCString() + "\r\n" +
    "Content-Type: application/json\r\n" +
    "Content-Length: " + Buffer.byteLength(body) + "\r\n" +
    "Cache-Control: no-store\r\n" +
    // Carried only where the contract calls for it, so a method rejected on /health
    // arrives with the same Allow header sendJson would have given it.
    (allow === undefined ? "" : "Allow: " + allow + "\r\n") +
    "Connection: close\r\n" +
    "\r\n" +
    body;
}

function malformedRequestResponse(code) {
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
  // every other path do not. A query string is the only component dropped here. A "#"
  // is deliberately left in place: a fragment is not part of a request-target and a
  // conforming client never sends one, so treating /health#anything as an alias of
  // /health would invent a second route rather than tolerate a real one, and would
  // answer UP on a target this program does not serve. app.py draws the line in the
  // same place.
  return target.split("?")[0];
}

function sendJson(response, status, payload, withBody, allow, close) {
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
  if (close) {
    // Ending the connection also discards whatever is left of the request body, which
    // is what keeps an unread payload from being misread as the start of the next
    // request here, and from holding this connection open after the listener has been
    // asked to stop.
    headers["Connection"] = "close";
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
  // The twin of app.py's reject_method, and of rejectionResponse above: the served
  // resource answers with the method-rejection contract and its Allow header, every
  // other target with the same 404 document, and either way the connection is closed.
  // Closing is what keeps a client that announced a request body it has not finished
  // sending from holding this connection - and with it the process, once the listener
  // has been asked to stop - alive.
  if (path !== HEALTH_PATH) {
    sendJson(response, 404, { status: "NOT_FOUND" }, withBody, undefined, true);
    return;
  }
  sendJson(response, 405, { status: "METHOD_NOT_ALLOWED" }, withBody, HEALTH_METHODS, true);
}

function connectResponse(request) {
  // Mirrors handleRequest's routing for the one method the runtime never delivers to
  // it. CONNECT is neither GET nor HEAD, so /health can only ever answer it with the
  // shared method-rejection contract, and any other target gets the same 404 document
  // a normal request would.
  return rejectionResponse(requestPath(request.url || ""));
}

function rejectionResponse(path) {
  // The one routing rule for a method this application does not serve, wherever the
  // request came from: the served resource answers with the method-rejection contract
  // and its Allow header, every other target with the same 404 document a request for
  // it would get. It is the raw-text twin of handleRequest's rejected-method branch,
  // and app.py's reject_method draws the line in exactly the same place.
  if (path !== HEALTH_PATH) {
    return rawResponse(404, "Not Found", "NOT_FOUND");
  }
  return rawResponse(405, "Method Not Allowed", "METHOD_NOT_ALLOWED", HEALTH_METHODS);
}

function rejectedMethodResponse(error) {
  // The runtime's parser turns a request away before the router ever sees it when the
  // method is not one of the tokens it knows - and an unknown but well-formed method is
  // exactly what "any other method" means. Left alone, "FROB /health" was answered 400
  // Bad Request while app.py answered the same bytes 405 with an Allow header, so the
  // shared contract held only for the verbs this runtime happens to recognise. Recognise
  // that one parser failure here, and only from a complete request line inside the
  // bounded window above, so the contract holds for any method a client can name. Every
  // other parse failure is a genuinely malformed request and keeps its own 4xx.
  if (error.code !== "HPE_INVALID_METHOD" || !Buffer.isBuffer(error.rawPacket)) {
    return null;
  }
  const head = error.rawPacket.subarray(0, MAX_REQUEST_LINE).toString("latin1");
  const end = head.indexOf("\r\n");
  if (end < 0) {
    // No complete request line arrived inside the window - a truncated write, or bytes
    // that are not a request line at all - so there is nothing to route.
    return null;
  }
  const parts = head.slice(0, end).split(" ");
  if (parts.length !== 3 || !METHOD_TOKEN.test(parts[0]) ||
      !REQUEST_TARGET.test(parts[1]) ||
      (parts[2] !== "HTTP/1.1" && parts[2] !== "HTTP/1.0")) {
    // Not a request line this application can answer on its own terms: no version, an
    // unparseable target, a method carrying bytes a method may not carry, or a protocol
    // that is not HTTP/1.x. Those keep the runtime's own diagnosis below.
    return null;
  }
  // The parser knows GET and HEAD, so a method token it refused can never be either and
  // this is always a rejection. Nothing the client sent is echoed back: the target is
  // used to choose between two fixed documents and is then discarded.
  return rejectionResponse(requestPath(parts[1]));
}

function trimBlanks(value) {
  // The one place an override's surrounding blanks are removed, so HEALTH_HOST and
  // HEALTH_PORT are read on identical terms and on the same terms app.py reads them.
  return value.replace(ASCII_BLANKS, "");
}

function healthHost(requested) {
  // An unset, empty or blank-only HEALTH_HOST means "not overridden", which is not a
  // fault: the documented default applies, silently.
  if (requested === "") {
    return DEFAULT_HOST;
  }
  if (/[\p{C}\p{Z}]/u.test(requested)) {
    // A host name, an IPv4 or IPv6 literal and an IPv6 zone identifier are all made of
    // printable, non-blank characters, so anything else is refused right here, before
    // the value can reach a socket or a diagnostic: \p{C} covers every control and
    // format character, \p{Z} every kind of blank and line separator. A carriage
    // return or a line feed inside the value would otherwise let whoever set the
    // variable forge extra lines on descriptor 2, and an interior blank cannot name a
    // host in any case. Reported as null so that the caller stops startup instead of
    // repairing the value and binding the endpoint to an address nobody asked for.
    // app.py applies the same rule, so an override is accepted or rejected identically
    // whichever application reads it.
    return null;
  }
  return requested;
}

function logSafe(text) {
  // Every line this program writes to file descriptor 2 is rendered through here
  // first. A carriage return, a line feed or a Unicode line separator reaching a log
  // stream lets whoever supplied the text forge additional records, and a terminal
  // escape reaching a terminal is acted on rather than printed, so each control,
  // format and line-separator character is replaced by its escape sequence instead.
  // Printable text - which is all a host name, an address, an operator-facing sentence
  // or a runtime error message legitimately consists of - is returned unchanged, so
  // every diagnostic reads exactly as written and occupies exactly one line.
  return text.replace(/[\p{C}\p{Zl}\p{Zp}]/gu, function (character) {
    return "\\u" + character.codePointAt(0).toString(16).padStart(4, "0");
  });
}

function healthPort(requested) {
  // An unset, empty or blank-only HEALTH_PORT means "not overridden", which is not a
  // fault: the documented default applies, silently.
  if (requested === "") {
    return DEFAULT_PORT;
  }
  // The grammar is checked before any conversion, and it is exactly the grammar app.py
  // applies: one to five ASCII decimal digits and nothing else. Anything a reader might
  // expect Number() to take - "0x4be0", "0o17700", "1e3", "13e3" - is refused by both
  // applications alike, and the digit class is ASCII so a fullwidth digit cannot slip
  // through either.
  if (requested.length <= PORT_MAX_DIGITS && PORT_DIGITS.test(requested)) {
    const port = Number(requested);
    if (port > 0 && port < 65536) {
      return port;
    }
  }
  // An override that is present but cannot be honoured is a configuration fault,
  // reported as null so that the caller stops startup. Falling back to the default
  // would answer a typo by silently moving the endpoint off the port that was asked
  // for: the process would report itself UP while the probe watching the intended
  // port saw nothing at all.
  return null;
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
  const requestedHost = trimBlanks(process.env.HEALTH_HOST || "");
  const host = healthHost(requestedHost);
  if (host === null) {
    // Reported in the same shape as the bind failure below, one readable line and a
    // non-zero status, so that no listener is ever started on an address nobody asked
    // for. The rejected value is deliberately not quoted back: an environment variable
    // is a common place for a secret to be pasted by mistake, and a diagnostic is the
    // one part of this program that is routinely collected, forwarded and kept, so
    // echoing the value would write whatever was supplied into a log this program does
    // not control. Naming the variable and the rule it broke is all an operator needs
    // to correct it, and they already have the value: they set it. app.py withholds it
    // for the same reason and in the same words. Every line still leaves through
    // logSafe, so a diagnostic can never span more than one line.
    process.stderr.write(logSafe(APP_NAME + ": invalid HEALTH_HOST: expected a host " +
      "name or address with no blanks and no control characters") + "\n");
    process.exitCode = 1;
    return;
  }
  const requested = trimBlanks(process.env.HEALTH_PORT || "");
  const port = healthPort(requested);
  if (port === null) {
    // Reported in the same shape as the bind failure below, one readable line and a
    // non-zero status, so that no listener is ever started on an address nobody asked
    // for. The rejected value is withheld for the reason given above: an environment
    // variable can carry a secret, and a diagnostic outlives the process that wrote it.
    process.stderr.write(logSafe(APP_NAME + ": invalid HEALTH_PORT: expected 1 to " +
      PORT_MAX_DIGITS + " decimal digits denoting a port from 1 to 65535") + "\n");
    process.exitCode = 1;
    return;
  }
  const server = http.createServer(handleRequest);
  let stopping = false;
  const closeListener = function () {
    // Releases the listening socket, so the port is free from here on. Safe to call
    // before the socket is up as well as after: with nothing bound yet, close() cancels
    // the pending bind rather than leaving one behind.
    server.close();
    // server.close() stops new connections but waits for every existing one, so on its
    // own a keep-alive connection sitting between requests, a request whose head never
    // finished arriving, or a client that announced a body and then went quiet would all
    // hold the event loop open - and with it this process - until the peer gave up.
    // Closing idle connections first lets a connection between requests end tidily;
    // closing the rest then ends the ones still mid-request, so the shutdown a
    // supervisor asked for cannot be deferred by any one client. Both are guarded
    // because they arrived in Node 18.2, and app.py's server_close() with its daemon
    // request threads ends just as promptly.
    if (typeof server.closeIdleConnections === "function") {
      server.closeIdleConnections();
    }
    if (typeof server.closeAllConnections === "function") {
      server.closeAllConnections();
    }
  };
  const dropSignalHandlers = function (handler) {
    // Restores the default action for a second interrupt and lets the event loop drain,
    // so the process ends with the status it already has once the socket is closed, with
    // every diagnostic flushed. process.exit() would risk truncating those writes
    // whenever descriptor 2 is a pipe. The handler is passed in rather than closed over,
    // so this reads in the order it runs and nothing is referenced before it exists.
    process.removeListener("SIGINT", handler);
    process.removeListener("SIGTERM", handler);
  };
  const shutdown = function () {
    // One closure serves both signals, and it runs its body once however many
    // signals arrive - including a signal that arrives before the listener is up, which
    // is why it is registered before listen() below rather than from its callback.
    if (stopping) {
      return;
    }
    stopping = true;
    process.stderr.write(logSafe(APP_NAME + ": shutting down") + "\n");
    dropSignalHandlers(shutdown);
    closeListener();
  };
  server.on("error", function (error) {
    // Binding is the first operation in this program that can fail for reasons
    // outside its control, most often because the port is already taken. One readable
    // line and a non-zero status serve the operator better than a stack dump. No socket
    // is listening, so once the two signal handlers are dropped the event loop drains
    // and the process ends with this status on its own. Dropping them matters for what
    // they would otherwise say rather than for the exit: a signal arriving after a
    // failed start would announce a shutdown of a listener that never existed.
    //
    // Naming the address is what makes the line actionable, so the default is named in
    // full: that literal is declared at the top of this file, and repeating a value
    // already present in the source discloses nothing. A host that arrived in
    // HEALTH_HOST is named only by its variable, because the checks above accept any
    // printable, blank-free word as a host name and only the resolver can discover that
    // the word names nothing. Such a value must not reach this record, and here that
    // takes two steps rather than one: the runtime puts the host it could not resolve
    // into the message itself, as in "getaddrinfo ENOTFOUND <host>", so the reason is
    // reported by its error code instead. The code is a fixed symbol such as ENOTFOUND
    // or EADDRINUSE, which names the failure exactly and cannot carry anything the
    // environment supplied. The port is always named because it is validated to be
    // nothing but decimal digits.
    const where = host === DEFAULT_HOST ? host + ":" + port :
      "the address named by HEALTH_HOST, port " + port;
    const reason = host === DEFAULT_HOST ? bindFailureReason(error) :
      (error.code || "bind failed");
    process.stderr.write(logSafe(APP_NAME + ": cannot bind " + where + ": " + reason) + "\n");
    process.exitCode = 1;
    dropSignalHandlers(shutdown);
  });
  server.on("clientError", function (error, socket) {
    // A request Node could not parse never reaches the router. Answer it with JSON
    // like every other path, echoing nothing the client sent, and simply discard a
    // connection the peer has already dropped. A well-formed request naming a method
    // the parser does not know is not malformed at all, so it is answered with the
    // method-rejection contract rather than with a parse error.
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
    // An HTTP CONNECT never reaches the request handler: the runtime emits it here
    // instead, and with nothing listening it destroys the connection, so the client
    // receives no answer at all rather than the 405 the contract promises for every
    // method other than GET and HEAD. Answer it from here, as raw text, because this
    // event is handed a socket rather than a response object.
    socket.on("error", function (socketError) {
      // A peer that hangs up while this reply is being written would otherwise raise
      // an unhandled socket error and end the process with it.
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
  // Registered before listen() rather than from its callback. listen() completes on a
  // later turn of the event loop, so a supervisor's SIGTERM arriving in between used to
  // find no handler at all: the runtime took its default action and the process died on
  // the signal with no notice on descriptor 2 and a signal exit status, even though it
  // had been asked to stop politely. Registering here closes that window, because a
  // signal delivered during this synchronous startup is not dispatched until the turn
  // ends - by which point shutdown is armed. It cannot keep a failed start alive either:
  // a signal listener does not hold the event loop open, and the bind-failure path above
  // drops both listeners anyway.
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
  server.listen(port, host, function () {
    if (stopping) {
      // A stop was requested while the bind was still in flight. Whether or not the
      // socket came up in the meantime, closing it here is what guarantees the port is
      // released and the event loop can drain, so the shutdown already announced is the
      // one that actually happens.
      closeListener();
      return;
    }
    process.stderr.write(logSafe(APP_NAME + " " + APP_VERSION + " serving " + HEALTH_PATH +
      " on http://" + host + ":" + port) + "\n");
  });
}

// Opt-in server: the endpoint runs only when --serve is passed, so the five console
// writes above stay byte-for-byte what they have always been and the default
// invocation still opens no socket and exits as soon as they are done.
if (process.argv.slice(2).includes("--serve")) { startHealthServer(); }
