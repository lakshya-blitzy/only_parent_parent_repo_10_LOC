/**
 * index.js - JavaScript application for only_parent_parent_repo_10_LOC.
 *
 * The file keeps its original job - printing the result of add(5, 7) five times -
 * and gains a uniform /health endpoint whose response is byte-compatible with the
 * Python and Java implementations in this repository.
 *
 * Three invocation modes, dispatched from the main-module guard at the bottom:
 *
 *   node index.js            default behaviour: prints "12" five times, exits 0
 *   node index.js --serve    binds host:port and serves the health endpoint
 *   node index.js --probe    requests its own endpoint, exits 0 (up) or 1 (down)
 *
 * The default mode's stdout is hashed by a committed baseline, so nothing outside
 * the default branch may write to stdout. Every diagnostic goes to stderr.
 *
 * The response is a strict subset of the vocabulary in the IETF draft "Health
 * Check Response Format for HTTP APIs" (draft-inadarei-api-health-check-06): a
 * JSON body, a `status` field, a 2xx code for a passing status, and no-store
 * caching so a health answer is never served from a cache. Two deviations from
 * that draft are deliberate - the plain `application/json` media type rather than
 * the draft's health-specific type, because plain JSON is what generic tooling
 * parses; and HEAD answered 405, because the endpoint is GET-only by design.
 *
 * `node:http` is the entire HTTP implementation. This file supplies a handler
 * that writes exactly one of three responses, and the error bodies are quoted
 * here as the EXACT BYTES on the wire, title case included, because they are
 * part of a frozen contract that app.py and User.java emit byte-identically -
 * a reader who took a lower-cased paraphrase from this comment and wrote a
 * case-sensitive monitor would have one that failed against all three:
 *
 *   200  GET on the configured route   the health document
 *   404  any other target              {"error":"Not Found"}
 *   405  any other method              {"error":"Method Not Allowed"}, Allow: GET
 *
 * SOME REQUESTS NEVER REACH THAT HANDLER, because `node:http` frames and
 * validates them in its own parser first. Every item below was established by
 * execution against all three implementations, and none is reachable from
 * application code - no hook runs before the method token and the framing are
 * decided, and this file registers no `clientError` and no `connect` listener, so
 * the runtime's own answer is the one that goes out. They are recorded rather
 * than smoothed over, because they are where this implementation's bytes differ
 * from app.py's and User.java's:
 *
 *   1. A method token outside the parser's own table is answered
 *      `HTTP/1.1 400 Bad Request` with `Connection: close` as its ONLY field and
 *      a ZERO-BYTE body - no Allow, no Content-Length, no media type, no banner
 *      and nothing echoed from the request. The boundary is the table, not
 *      novelty: LOCK, PURGE, M-SEARCH, PATCH, TRACE and OPTIONS are all in it and
 *      all reach the handler and receive the frozen 405, while FROBNICATE and any
 *      lower- or mixed-case spelling of a known token - `get`, `Get`, `post` - do
 *      not. app.py and User.java treat the token as opaque, classify it as
 *      not-GET, and answer the frozen 405 for every one of those shapes.
 *   2. CONNECT is answered by destroying the connection with no response at all,
 *      in both the authority form and the origin form. With no `connect` listener
 *      no tunnel can be established and none is attempted - the strictest
 *      available outcome for the one method whose purpose is to make a listener
 *      proxy traffic. User.java also writes nothing for the authority form;
 *      app.py answers the frozen 405 for both.
 *   3. Every other framing fault shares the SAME minimal shape as item 1: a
 *      status line, `Connection: close`, and no body. A bare-LF terminator, a
 *      four-token request line, a TAB, VT or FF delimiter, an unparsable
 *      HTTP-version token, whitespace before a field colon, and a header block
 *      ended by EOF all draw that 400; header bytes past `http.maxHeaderSize`
 *      (16,384 by default, so one field of ~16 KB is enough) draw the same shape
 *      with 431. There is no ceiling on the NUMBER of fields - thousands of small
 *      ones are served - where app.py refuses at 100 header lines with 431 and at
 *      65,536 request-line bytes with 414, and User.java refuses at 200 distinct
 *      field names by closing the connection in silence.
 *   4. A two-token `GET /health` request line - the HTTP/0.9 form - is ACCEPTED
 *      and answered with the frozen 200, where app.py and User.java both refuse
 *      it with 400. This is the one place the parser is LOOSER than the other
 *      two rather than stricter. It is left as the runtime decides it because the
 *      response is still the frozen contract, the client that sent it still
 *      learns only the health document, and overriding the parser would mean
 *      re-implementing framing this file deliberately does not own.
 *   5. An HTTP/1.1 request carrying NO `Host` field is refused 400 by the
 *      parser's own host requirement, and that refusal is the one exception to
 *      the shape in item 3: it is written through the normal response path, so it
 *      carries `Connection: close`, a `Date`, and `Transfer-Encoding: chunked`
 *      with an empty chunked body. It is therefore the ONLY response this program
 *      can emit that carries a `Date` - `writeJson` suppresses that field on
 *      every response it composes, and this one it does not compose. An HTTP/1.0
 *      request needs no Host and is served normally. app.py and User.java both
 *      route a hostless 1.1 request on its target and answer the frozen 200, so
 *      RFC 9112 section 3.2 is read strictly here and permissively there. All
 *      three implementations are conformant, because that section requires the
 *      CLIENT to send the field and permits, without requiring, a server to
 *      refuse when it does not.
 *
 * Items 1, 2, 3 and 5 are stricter than the frozen contract rather than looser: an
 * empty 400 or 431, a destroyed connection and a bodiless 400 each disclose
 * strictly less than the 405 or 200 they replace. index.test.js pins all five so
 * the record cannot drift silently, and a runtime upgrade that moves any of them
 * fails a test rather than being discovered by an operator.
 *
 * Everything here comes from the Node standard library, so `node index.js` works
 * on a bare runtime with no install step, no node_modules and no lockfile.
 */

const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

function add(a, b) {
  return a + b;
}

// Configuration - single source of truth with a fixed override precedence.

/**
 * Absolute path of the shared cross-language configuration file.
 *
 * Resolved relative to this file rather than to the process working directory
 * so that `node /some/where/index.js` and a container `WORKDIR` change both
 * still find the properties file that ships beside the script.
 */
const CONFIG_FILE = path.join(__dirname, "app.config.properties");

/**
 * The properties grammar, in the exact terms `java.util.Properties.load` defines
 * it. Whitespace is SPACE, TAB and FORM FEED only; a key is separated from its
 * value by `=`, `:` or whitespace; `#` and `!` introduce a comment. These are
 * what make one shared file mean one thing in three languages rather than three
 * similar things.
 */
const PROPERTIES_WHITESPACE = " \t\f";
const PROPERTIES_SEPARATORS = "=:";
const PROPERTIES_COMMENTS = "#!";

/**
 * A `\uXXXX` escape is exactly four hexadecimal digits. Anything shorter or
 * non-hexadecimal makes the document malformed rather than the escape literal,
 * which is what `Properties.load` does and therefore what this must do.
 */
const PROPERTIES_ESCAPE_WIDTH = 4;
const PROPERTIES_HEX_GRAMMAR = /^[0-9a-fA-F]{4}$/;
const PROPERTIES_MALFORMED_ESCAPE = "malformed \\uxxxx encoding";

/**
 * The decoder every byte sequence this program reads passes through: the
 * properties file, and the body of a probe answer.
 *
 * Fatal, so a sequence that is not valid UTF-8 raises instead of becoming U+FFFD.
 * Both call sites need that for the same reason: silent replacement turns bytes
 * the sibling implementations refuse into text that looks legitimate here.
 *
 * `ignoreBOM: true` KEEPS a leading U+FEFF rather than stripping it - the option
 * name reads backwards. Both call sites need that too. `Properties.load` keeps
 * the mark, so stripping it would make the same file produce a different first
 * key here than in the other two implementations; and a probe answer that opens
 * with a byte-order mark is refused as malformed by all three.
 */
const STRICT_UTF8_DECODER = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });

/**
 * The two diagnostics the loader can emit, worded identically in all three
 * implementations so one operator-facing message means one condition everywhere.
 * An ABSENT file emits neither: it is the normal case.
 */
const CONFIG_UNREADABLE_WARNING = "cannot read the configuration file; using defaults";
const CONFIG_MALFORMED_WARNING = "the configuration file is malformed; using defaults";

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
 * Environment variable that overrides each properties key. The same five
 * variables are honoured, under the same names, by app.py and User.java.
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
 *
 * Because it is the bound that stops an endless stream it is deliberately NOT
 * raised to fit a large configuration, and that has an operational consequence
 * worth stating where the number is defined. The rendered document is
 * `73 + len(app.name) + len(app.version)` bytes of UTF-8 - 73 being the four
 * keys, the punctuation, the fixed-width instant and the status - so a
 * configuration whose name and version together exceed 8119 bytes makes this
 * application's OWN healthy answer larger than the probe will read. The probe
 * then fails closed on a healthy process, and a container health check reading
 * its exit status restarts a container that was working. The direction of failure
 * is the safe one and 8192 is generous against a 108-byte default; the budget is
 * documented in app.config.properties and .env.example, where an operator sets
 * the value.
 */
const MAX_PROBE_BODY_BYTES = 8192;

/**
 * Listener timeouts - all four, because Node's defaults are wrong for a health
 * endpoint in two directions at once. `headersTimeout` defaults to 60 s and
 * `requestTimeout` to 300 s, so a client that opens a connection and then
 * trickles can hold a socket for five minutes; `server.timeout` defaults to 0,
 * so an established socket that goes quiet is never reclaimed at all.
 *
 * `connectionsCheckingInterval` is how often the runtime sweeps for connections
 * that have outlived those budgets. It is a CONSTRUCTOR option rather than a
 * property, which is why it is passed to createServer().
 */
const CONNECTION_CHECK_INTERVAL_MS = 500;
const HEADERS_TIMEOUT_MS = 10000;
const REQUEST_TIMEOUT_MS = 15000;
const SOCKET_TIMEOUT_MS = 30000;
const KEEP_ALIVE_TIMEOUT_MS = 5000;

/**
 * Returns the first argument that is a supplied string, verbatim; undefined when
 * there is none.
 *
 * An environment variable set to the empty string is absent rather than an
 * override to the empty string: the contract requires a non-empty name and a
 * dotted version, so an empty value falls through to the next source instead of
 * producing a payload that violates it.
 *
 * ONLY the empty string is absent, and the winner is returned exactly as it was
 * supplied. Both halves are correctness requirements. app.py resolves with
 * `if override:` and User.java with `!fromEnvironment.isEmpty()`, so a
 * whitespace-only value is a SUPPLIED value in all three and is carried through
 * to resolvePort, which REJECTS it rather than falling back to a port the
 * operator did not ask for. Neither sibling trims what it returns either, so
 * trimming here would make a configured name, version, path or host differ
 * across the three for one input. Values that need trimming are trimmed at the
 * point of use.
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
 * from that being one exit rather than a per-call-site convention. Nothing
 * reaches stdout, which carries the legacy output a committed hash asserts byte
 * for byte. And no caller can forge a log line: the text is stripped of control
 * characters by sanitizeForLog before the newline is appended, so a configured
 * value carrying CR and LF cannot produce a second line and an escape sequence
 * cannot rewrite what an operator sees. sanitizeForLog is declared further down;
 * function declarations are hoisted, so the call below resolves.
 */
function warn(message) {
  process.stderr.write(`index.js: ${sanitizeForLog(String(message))}\n`);
}

/**
 * Splits properties text into natural lines on CRLF, LF or CR.
 *
 * Deliberately not `split(/\r?\n/)`, which leaves a lone CR inside a line, and
 * deliberately not a broader line-break class: `java.util.Properties` recognises
 * only these three terminators, and FORM FEED in particular is whitespace WITHIN
 * a line for it, so treating it as a break would truncate a value the Java
 * loader reads whole.
 */
function splitNaturalLines(text) {
  const lines = [];
  let current = "";
  let index = 0;
  while (index < text.length) {
    const char = text.charAt(index);
    if (char === "\r") {
      lines.push(current);
      current = "";
      index += text.charAt(index + 1) === "\n" ? 2 : 1;
      continue;
    }
    if (char === "\n") {
      lines.push(current);
      current = "";
      index += 1;
      continue;
    }
    current += char;
    index += 1;
  }
  lines.push(current);
  return lines;
}

/**
 * Counts the backslashes that end a natural line.
 *
 * An ODD count continues the logical line onto the next natural line; an EVEN
 * count means the final backslash was itself escaped and the line ends here.
 */
function trailingBackslashes(text) {
  let count = 0;
  let at = text.length - 1;
  while (at >= 0 && text.charAt(at) === "\\") {
    count += 1;
    at -= 1;
  }
  return count;
}

/**
 * Returns the first index at or after `start` that is not properties whitespace.
 */
function skipPropertiesWhitespace(text, start) {
  let at = start;
  while (at < text.length && PROPERTIES_WHITESPACE.includes(text.charAt(at))) {
    at += 1;
  }
  return at;
}

/**
 * Resolves the escape sequences `java.util.Properties.load` resolves.
 *
 * `\t`, `\n`, `\r` and `\f` become their control characters and `\uXXXX` becomes
 * its code unit. Every OTHER escaped character becomes itself, which is how an
 * escaped space, `=`, `:`, `#` or backslash carries a separator or a comment
 * marker into a key or a value. A capital `\U` is not a unicode escape - it
 * yields `U` - and a lone backslash at the very end of the text is dropped.
 *
 * @throws {RangeError} On a `\uXXXX` escape that is not four hexadecimal digits.
 */
function unescapeProperties(raw) {
  let out = "";
  let index = 0;
  while (index < raw.length) {
    const char = raw.charAt(index);
    index += 1;
    if (char !== "\\") {
      out += char;
      continue;
    }
    if (index >= raw.length) {
      break;
    }
    const escape = raw.charAt(index);
    index += 1;
    if (escape === "u") {
      const digits = raw.slice(index, index + PROPERTIES_ESCAPE_WIDTH);
      if (!PROPERTIES_HEX_GRAMMAR.test(digits)) {
        throw new RangeError(PROPERTIES_MALFORMED_ESCAPE);
      }
      out += String.fromCharCode(Number.parseInt(digits, 16));
      index += PROPERTIES_ESCAPE_WIDTH;
    } else if (escape === "t") {
      out += "\t";
    } else if (escape === "n") {
      out += "\n";
    } else if (escape === "r") {
      out += "\r";
    } else if (escape === "f") {
      out += "\f";
    } else {
      out += escape;
    }
  }
  return out;
}

/**
 * Parses properties text exactly as `java.util.Properties.load` parses it.
 *
 * This is the shared configuration grammar, shared by implementation rather than
 * by convention: app.config.properties is the single source of truth for three
 * languages and User.java reads it with `Properties.load`, so the other two must
 * read it the same way or one file means two things. Four cases decide that - a
 * value's trailing whitespace, a `:` or whitespace separator, an escape sequence,
 * and a continuation line - and each changes what the endpoint publishes.
 *
 * The grammar, in order:
 *
 *   - Natural lines break on CRLF, LF or CR. Leading whitespace is skipped; an
 *     exhausted line is blank and skipped; a first non-whitespace character of
 *     `#` or `!` makes the line a comment, and that test applies to the FIRST
 *     natural line of a logical line only.
 *   - A line ending in an ODD number of backslashes continues: the final
 *     backslash is dropped and the next natural line is appended with its own
 *     leading whitespace stripped.
 *   - The key ends at the first UNESCAPED `=`, `:`, SPACE, TAB or FORM FEED.
 *     Whitespace after it is skipped, one optional `=` or `:` is consumed, and
 *     whitespace after that is skipped. The rest is the value, whose TRAILING
 *     whitespace is preserved.
 *   - Both halves are then unescaped, and the last occurrence of a repeated key
 *     wins.
 *
 * A byte-order mark is not stripped, because `Properties.load` does not strip
 * one: a file saved with a BOM has a first key of `\ufeffapp.name` in all three
 * implementations rather than a working key in one and a broken key in two.
 *
 * The result has a null prototype, so a file containing `__proto__=x` yields an
 * ordinary entry named `__proto__` exactly as it does in Python and Java.
 *
 * @throws {RangeError} On a malformed `\uXXXX` escape.
 */
function parseProperties(text) {
  const props = Object.create(null);
  const lines = splitNaturalLines(String(text));
  let index = 0;
  while (index < lines.length) {
    const line = lines[index];
    index += 1;
    const start = skipPropertiesWhitespace(line, 0);
    if (start >= line.length) {
      continue;
    }
    if (PROPERTIES_COMMENTS.includes(line.charAt(start))) {
      continue;
    }
    let logical = line.slice(start);
    while (trailingBackslashes(logical) % 2 === 1) {
      logical = logical.slice(0, -1);
      if (index >= lines.length) {
        break;
      }
      const follow = lines[index];
      index += 1;
      logical += follow.slice(skipPropertiesWhitespace(follow, 0));
    }
    let cursor = 0;
    while (cursor < logical.length) {
      const char = logical.charAt(cursor);
      if (char === "\\") {
        cursor += 2;
        continue;
      }
      if (
        PROPERTIES_SEPARATORS.includes(char) ||
        PROPERTIES_WHITESPACE.includes(char)
      ) {
        break;
      }
      cursor += 1;
    }
    const keyEnd = Math.min(cursor, logical.length);
    let after = skipPropertiesWhitespace(logical, keyEnd);
    if (after < logical.length && PROPERTIES_SEPARATORS.includes(logical.charAt(after))) {
      after = skipPropertiesWhitespace(logical, after + 1);
    }
    props[unescapeProperties(logical.slice(0, keyEnd))] = unescapeProperties(
      logical.slice(after),
    );
  }
  return props;
}

/**
 * Reads the shared properties file and parses it with the shared grammar.
 *
 * The file is read as BYTES and decoded by a FATAL UTF-8 decoder, matching
 * `Files.newBufferedReader(location, UTF_8)` in User.java and the strict decode
 * in app.py. A lossy decode substitutes U+FFFD for every malformed sequence, so
 * a file Java refuses to read at all would become a configuration full of
 * replacement characters here - and that configuration reaches the payload.
 *
 * Three outcomes, the same three in all three implementations. An ABSENT file is
 * silent, because the defaults are a complete configuration. A file that cannot
 * be READ, or is not UTF-8, emits one warning. A MALFORMED file - a `\uXXXX`
 * escape that is not four hex digits - emits one warning. Neither warning
 * carries the path or the underlying message, because that text embeds the path.
 */
function readProperties(file) {
  let text;
  try {
    text = STRICT_UTF8_DECODER.decode(fs.readFileSync(file));
  } catch (error) {
    if (error && error.code !== "ENOENT") {
      warn(CONFIG_UNREADABLE_WARNING);
    }
    return Object.create(null);
  }
  try {
    return parseProperties(text);
  } catch (malformed) {
    warn(CONFIG_MALFORMED_WARNING);
    return Object.create(null);
  }
}

/**
 * Ensures a request path starts with a slash, so a value configured as
 * "healthz" still matches the request target "/healthz".
 */
function withLeadingSlash(value) {
  return value.startsWith("/") ? value : `/${value}`;
}

/**
 * Resolves the listener port from candidates given in precedence order.
 *
 * The highest-precedence candidate that is present wins, and if that value is
 * not a legal port the function THROWS rather than falling through to a lower
 * one. Failing closed is the point: an operator who sets PORT=8O01 with a letter
 * O has asked for a specific port, and quietly serving the default instead would
 * leave a health probe pointed at nothing while the process reported itself up.
 * Silently binding NaN - which listen() turns into an arbitrary ephemeral port -
 * would be worse.
 *
 * Parsing is a digit test rather than Number(), because Number("0x50") is 80: a
 * hexadecimal typo would otherwise resolve to a real but unintended port. The
 * accepted grammar is the one Integer.parseInt and int() accept.
 *
 * Surrounding whitespace is removed before that test and nowhere else, so
 * PORT=" 8080 " resolves to 8080 here just as it does in the siblings, while
 * PORT="   " trims to the empty string, fails the digit test and is REJECTED
 * rather than skipped. Port 0 is legal: it is how a test binds an ephemeral port.
 *
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
 * Precedence, highest first: environment variable, then the properties file, then
 * the built-in default. The port has one extra rung above all of those - the
 * universal PORT variable - so PORT beats NODE_PORT beats `node.port` beats 8001.
 * app.py and User.java resolve the same chain the same way, so the three
 * implementations agree on every input, including the awkward ones: a supplied but
 * unusable port is refused by all three rather than quietly replaced.
 *
 * Both the file path and the environment map are injectable so that callers -
 * notably the unit tests - can assert the precedence chain without mutating
 * process.env for the whole process.
 *
 * @param {{file?: string, env?: Record<string, string|undefined>}} [options]
 */
function loadConfig(options = {}) {
  const file = options.file === undefined ? CONFIG_FILE : options.file;
  const env = options.env === undefined ? process.env : options.env || {};
  const props = readProperties(file);
  const pick = (key) => firstNonEmpty(env[ENV_KEYS[key]], props[key], DEFAULTS[key]);

  return Object.freeze({
    name: pick("app.name"),
    version: pick("app.version"),
    healthPath: configRoute(pick("health.path")),
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
 * Configuration is an input, and every value it carries reaches either the public
 * health document or the route that serves it. The case this exists for is a
 * non-empty but MALFORMED value: an `APP_VERSION` of `not-a-version` would
 * otherwise be served inside a 200 response whose `status` field read `UP`, so the
 * endpoint would attest to its own health while describing itself in a form no
 * consumer of the frozen contract can parse - which is worse than refusing to
 * start, because nothing downstream can tell.
 *
 * Four rules, identical in app.py and User.java:
 *
 *   - `name` is non-empty and carries no control character (it is a payload field,
 *     and a control character would also break the single-line banner);
 *   - `version` matches VERSION_GRAMMAR exactly;
 *   - `healthPath` is non-empty and the route it reduces to through configRoute is
 *     a valid request target that is not a NETWORK_PATH_PREFIX network-path
 *     reference - the ROUTE is graded rather than the raw value, so what is
 *     validated is exactly what will be served;
 *   - `host` is non-empty and carries no control character.
 *
 * Enforced at both points where a bad value would become observable: creating the
 * server, before the socket is bound; and running the probe, so a probe cannot
 * report healthy a configuration the server would refuse to serve.
 *
 * No message quotes the offending value - the key names the setting, and
 * withholding the value is what lets probe() print this message verbatim without a
 * configured string reaching a log line. The port is deliberately NOT checked
 * here: resolvePort grades it at the point of use.
 *
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
  if (typeof config.healthPath !== "string" || config.healthPath === "") {
    throw new RangeError("invalid health.path: it is not a valid request target");
  }
  const route = configRoute(config.healthPath);
  if (route.startsWith(NETWORK_PATH_PREFIX) || !isRequestTarget(route)) {
    throw new RangeError("invalid health.path: it is not a valid request target");
  }
  if (!isSingleLineText(config.host)) {
    throw new RangeError(
      "invalid app.host: it must be non-empty text with no control character",
    );
  }
}

// Health payload - the frozen response contract.

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

// Health payload construction and route normalisation.

/** Root path, and the value an empty or query-only target normalises to. */
const ROOT_PATH = "/";

/**
 * A target beginning with this is a network-path reference - RFC 3986 section 4.2
 * reads `//health` as an authority named `health`, not as a path. All three
 * implementations refuse such a value where it is CONFIGURED, because not all
 * three platform servers can route it: CPython's request parser folds an inbound
 * `//health` down to `/health` and the JDK's URI parser resolves it to an empty
 * path, so a route this runtime would serve happily is a route the other two
 * answer 404 - a configuration-dependent cross-language outage in which one
 * implementation reports itself up and the others cannot answer at all.
 */
const NETWORK_PATH_PREFIX = "//";

/**
 * Marks an absolute-form request target, as in `GET http://host/health`.
 *
 * RFC 9112 section 3.2.2 requires a server to accept this form even though almost
 * no client emits it, and all three implementations reduce it to its path so that
 * the same request reaches the same route in every one of them.
 */
const SCHEME_SEPARATOR = "://";

/**
 * Introduces a URI fragment.
 *
 * A real request target never carries one - RFC 9110 section 7.1 has the client
 * strip it before sending - so this is stripped defensively, and because the same
 * function normalises the CONFIGURED health path, where one could be written by
 * hand.
 */
const FRAGMENT_MARKER = "#";

/**
 * Current UTC instant, truncated to whole seconds, with a trailing "Z".
 *
 * toISOString() yields milliseconds; stripping the fractional part gives the
 * fixed-width form app.py and User.java also emit, which keeps the rendered body
 * the same length in all three. This is the only non-deterministic field in the
 * payload, which is why every automated assertion against it checks the FORMAT
 * and never the value.
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
 * No replacer and no space argument, so the output carries no whitespace and
 * matches the Python (compact separators) and Java (hand-built string) renderings
 * byte for byte.
 *
 * Accepts either an already-built payload - recognised by its `status` field - or
 * a configuration, so a caller with either in hand, or neither, can call it.
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
 * Four reductions, in this order and identical to normalisePath in User.java and
 * normalize_path in app.py: an absolute-form target is reduced to its path,
 * everything from the first "?" is dropped, everything from a "#" is dropped, and
 * one trailing slash is removed when something is left in front of it.
 *
 * What it deliberately does NOT do matters as much. No percent-decoding, so
 * "/health%2f" stays distinct from "/health/"; no dot-segment resolution, so
 * "/health/../health" does not reach the route; no collapsing of leading slashes,
 * so "//health" is distinct from "/health". Each of those would widen the route
 * to targets an operator did not configure.
 *
 * All three omissions hold in the other two implementations too, as pure functions.
 * ON THE WIRE the leading-slash one does not, and only in app.py: CPython's request
 * parser folds an inbound "//health" down to "/health" before any handler runs, so
 * that one target is served there and refused here. app.py records the fold at the
 * function it affects; the half all three can refuse is the CONFIGURED route, which
 * validateConfig refuses everywhere.
 *
 * Pure and total: an absent, empty or query-only target normalises to the root.
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
 * Reduces a CONFIGURED health path to the route the endpoint will answer on.
 *
 * Two steps, in this order and identical in app.py's `config_route` and
 * User.java's `configRoute`: a missing leading slash is supplied, then
 * normalizePath performs the same four reductions it performs on a request
 * target. `health`, `/health` and `/health/` therefore all describe one route.
 *
 * Both loadConfig and validateConfig go through this function, which is what
 * makes the route that is VALIDATED and the route that is SERVED the same string
 * by construction rather than by two code paths agreeing.
 */
function configRoute(value) {
  return normalizePath(withLeadingSlash(value));
}

/**
 * Reports whether a character is an unaccented ASCII letter.
 */
function isAsciiLetter(current) {
  return (current >= "a" && current <= "z") || (current >= "A" && current <= "Z");
}

/**
 * Reports whether a string is a URI scheme as RFC 3986 defines one.
 *
 * ALPHA followed by any number of ALPHA, DIGIT, `+`, `-` or `.`.
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
 * The authority is DISCARDED, not inspected, and that is deliberate rather than an
 * omission. A FOREIGN authority - `GET http://evil.example/health`, or one carrying
 * userinfo, or a port that is not the one bound - therefore reaches exactly the
 * route its path names: measured on the wire, all three implementations answer the
 * frozen 200 for such a target and the frozen 404 for `http://evil.example/nope`.
 * Nothing here depends on the authority, so honouring it would only create a way to
 * make one deployment answer differently from another; RFC 9112 section 3.2.2 does
 * require the target to be accepted, and section 7.2 puts host-based dispatch in
 * `Host`, which a single-route endpoint has no use for.
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

// HTTP server.

/**
 * Writes a JSON response carrying exactly the contract headers.
 *
 * Two suppressions are load-bearing rather than cosmetic. `sendDate` is switched
 * off because Node would otherwise add a `Date` header the Python implementation
 * does not emit. `Connection` is removed for the same reason: Node adds
 * `Connection: keep-alive` plus a `Keep-Alive` header on a persistent HTTP/1.1
 * response, and removing the header before writeHead() stops both from being
 * computed. Persistence itself is unaffected - it is governed by the parser's own
 * keep-alive decision - so connections are still reused. No `Server` header is
 * ever set, so the runtime version is not advertised.
 *
 * The result is the three-header contract set - Content-Type, Cache-Control and
 * Content-Length - plus Allow on a 405. That is byte-for-byte what app.py emits;
 * User.java emits those three AND a `Date` it cannot suppress, which is recorded
 * as a stated deviation on its own sendResponse rather than claimed here as
 * parity. `Content-Length` is the BYTE length rather than the character count, so
 * a multi-byte character in a configured value cannot desynchronise the advertised
 * length from the body.
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
 * The handler has three outcomes over `node:http`: a method other than GET is 405
 * with an `Allow` header, a target that does not normalise to the configured route
 * is 404, and everything else is the health document. There is no fourth outcome,
 * and nothing here reads the request body - the endpoint answers from
 * configuration and the clock alone.
 *
 * The route is derived with configRoute rather than normalizePath, so a health
 * path configured without a leading slash is SERVED on the same route
 * validateConfig graded and the same route app.py and User.java serve.
 *
 * Configuration is resolved ONCE here rather than per request, so every response a
 * given server produces describes the same application; app.py and User.java
 * snapshot at construction for the same reason. Reloading is what a restart is for.
 *
 * It is also VALIDATED here, before any socket exists. A server that bound a port
 * and then answered 200/UP with a malformed version would publish the very thing
 * the validation exists to refuse, and would look healthy while doing it - so the
 * refusal happens at construction, where the caller's own catch block learns about
 * the typo rather than a monitoring system three layers away.
 *
 * The four listener budgets are applied here too, for the reason given at their
 * declaration. The server is RETURNED rather than started, so the tests can drive
 * it on an ephemeral port without competing for the configured one.
 *
 * @throws {RangeError} When the configuration cannot be published.
 */
function createServer(config) {
  const resolved = config === undefined || config === null ? loadConfig() : config;
  validateConfig(resolved);
  const routePath = configRoute(resolved.healthPath);

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

// Process modes - serve and probe.

/** How long a graceful shutdown may take before the process exits anyway. */
const SHUTDOWN_GRACE_MS = 1000;

/**
 * Registers a graceful shutdown on the signals a container runtime sends.
 *
 * Open keep-alive sockets would keep server.close() pending, so they are closed
 * first; the unref'd fallback timer guarantees the process still exits promptly if
 * a socket refuses to go away, and being unref'd it never holds the event loop
 * open on its own.
 *
 * Because the shutdown completes normally, this process exits 0 for both SIGINT
 * and SIGTERM, while app.py exits 0 on SIGINT and is terminated by SIGTERM, and
 * the JVM in User.java reports 130 and 143. Exit STATUS is the one place these
 * three servers deliberately differ; everything an orchestrator depends on is
 * identical - the listener is closed, the port is released, stdout stays empty.
 *
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
    const route = configRoute(config.healthPath);
    warn(`health endpoint listening on http://${String(host)}:${boundPort}${route}`);
  });

  registerShutdown(server);
  return server;
}

// Self-check.
//
// A probe is a CLIENT, and a client is only as safe as its behaviour against a
// peer that does not cooperate. Three properties are built in rather than
// assumed: the destination is selected from a loopback allowlist and never
// derived from configuration, the exchange is bounded in time AND in bytes, and
// the verdict comes from parsing the document against the frozen contract rather
// than from looking for a fragment inside it. It is written in-process rather
// than as a shell-out because a slim runtime image ships neither curl nor wget.

/**
 * Returns true when every character is an ASCII digit and there is at least one.
 *
 * ASCII only, deliberately: a near-miss address spelled with an Arabic-Indic
 * digit must not be graded loopback.
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
 * general parser accepts spellings this module has no reason to accept - `127.1`,
 * `0x7f.0.0.1`, a bare decimal integer - and each is a different way to write a
 * destination the allowlist would then have to reason about. Four decimal octets
 * or nothing, and surrounding whitespace is not tolerated because no loader
 * trims it.
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
 * only the wildcard spellings and using everything else verbatim would make this
 * self-check a general-purpose outbound HTTP client aimed wherever that input
 * pointed - a probe that reports healthy because some OTHER machine is healthy,
 * and an egress request the deployment never asked for. So the destination is not
 * derived from the configured value at all; it is SELECTED from a fixed set of
 * loopback forms, and a value outside that set is replaced rather than honoured.
 *
 * A wildcard spelling (unset, empty, whitespace, 0.0.0.0, ::, [::], *) becomes
 * 127.0.0.1, because a wildcard names every interface and not a destination;
 * `localhost` is MAPPED to 127.0.0.1 rather than resolved; an address in
 * 127.0.0.0/8 is kept as configured; the IPv6 loopback in any spelling becomes
 * the bracketed [::1]; anything else becomes 127.0.0.1 with one warning that
 * never quotes the configured value. app.py and User.java apply the same
 * selection.
 *
 * Replacing rather than refusing is deliberate: a refusal would report the
 * application unhealthy because its BIND address is unusual, which is a
 * misdiagnosis - the listener may be serving perfectly on an interface this probe
 * is not allowed to dial. The warning is what tells an operator the configured
 * value was not used.
 *
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
 * Reports whether any object in an ALREADY-VALID JSON document names a member twice.
 *
 * `JSON.parse` resolves a repeated name by keeping the LAST occurrence and says
 * nothing about it, which silently turns a contradictory document into a plausible
 * one: `"status":"UP","status":"DOWN"` parses into an object reporting DOWN. RFC
 * 8259 calls such an object's behaviour unpredictable, so the probe refuses it
 * rather than picking a member on the endpoint's behalf.
 *
 * This runs as part of the PARSE step rather than after the field rules, and that
 * placement is the contract: app.py refuses a repeat through an
 * `object_pairs_hook` and User.java through its reader's own member map, and both
 * fire while the document is being parsed - so grading a duplicate later here
 * would report a FIELD reason for a body the other two call malformed.
 *
 * The scan assumes `text` has already been accepted by `JSON.parse`, so it
 * validates nothing: it only has to find the member names, which it does with a
 * container stack so that a string inside an array is never mistaken for one.
 * Names are compared DECODED, so `"a"` and `"\u0061"` collide exactly as they do
 * in the siblings, and EVERY object is examined at every depth, because the
 * sibling hooks apply at every depth too.
 */
function repeatsMember(text) {
  // One frame per open container: a Set of the member names seen so far for an
  // object, and null for an array. Arrays hold no names, but they must still be
  // tracked, or a string element would be read as a member name.
  const stack = [];
  let expectName = false;
  let cursor = skipJsonWhitespace(text, 0);
  while (cursor < text.length) {
    const character = text.charAt(cursor);
    if (character === '"') {
      const literal = readJsonString(text, cursor);
      if (literal === null) {
        // Unreachable for a document JSON.parse accepted, and reported as a
        // repeat rather than ignored: a probe that cannot read a body must never
        // be the reason that body passes.
        return true;
      }
      const names = stack[stack.length - 1];
      if (expectName && names) {
        if (names.has(literal.value)) {
          return true;
        }
        names.add(literal.value);
        expectName = false;
      }
      cursor = literal.cursor;
    } else if (character === "{") {
      stack.push(new Set());
      expectName = true;
      cursor += 1;
    } else if (character === "[") {
      stack.push(null);
      expectName = false;
      cursor += 1;
    } else if (character === "}" || character === "]") {
      stack.pop();
      expectName = false;
      cursor += 1;
    } else if (character === ",") {
      expectName = stack.length > 0 && stack[stack.length - 1] !== null;
      cursor += 1;
    } else {
      // A colon, or one character of a number, `true`, `false` or `null`. Stepping
      // over it one character at a time is enough: none of them can contain a
      // quotation mark, a bracket or a comma.
      cursor += 1;
    }
    cursor = skipJsonWhitespace(text, cursor);
  }
  return false;
}

/**
 * Grades the four contract fields of an already-shaped document.
 *
 * Split out so that the ordering of the field rules is stated in exactly one
 * place: `status` is examined before the three descriptive fields, so an endpoint
 * reporting itself down is reported as down rather than as whichever of its other
 * fields happened also to be wrong.
 *
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
 * The rules, in the order applied - and the order is part of the contract, because
 * it decides which of two simultaneous faults is reported:
 *
 *   1. the body fits inside MAX_PROBE_BODY_BYTES;
 *   2. the response code is exactly 200 - the IETF health-check draft couples a
 *      passing status to a 2xx code, and this contract narrows that to one code;
 *   3. the body decodes as UTF-8, is JSON with nothing trailing it, and names no
 *      member twice. ONE step, because all three are what the siblings settle
 *      while parsing, and splitting them would change which of two simultaneous
 *      faults gets reported;
 *   4. the body is a JSON OBJECT;
 *   5. it carries exactly PAYLOAD_KEYS, in that order;
 *   6. the four field rules of fieldRejection, which also refuse a nested or
 *      numeric member, since one that is not a string cannot satisfy its rule.
 *
 * Every rule is stated against a PARSED document, and that is what makes the
 * verdict fail closed: a truncated, unparseable body that happens to contain the
 * bytes `"status":"UP"` proves nothing and is graded on rule 3.
 *
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
  // Decode and parse under ONE catch, because a body that is not UTF-8 and a body
  // that is not JSON are the same fact to a probe, and because that is where the
  // siblings put the boundary: app.py catches UnicodeDecodeError and ValueError
  // together, and User.java's strict decoder throws its reader's exception. The
  // decoder is FATAL: a lossy decode substitutes U+FFFD, so a body carrying
  // `c3 28` inside its name field would decode to a schema-valid document and be
  // graded healthy here while both siblings refused the same bytes.
  let text;
  let parsed;
  try {
    text = STRICT_UTF8_DECODER.decode(buffer);
    parsed = JSON.parse(text);
  } catch {
    return "body is not the expected JSON document";
  }
  // Part of the parse step, before any field is graded: see repeatsMember for why
  // the placement rather than the rule is what keeps the three verdicts identical.
  if (repeatsMember(text)) {
    return "body is not the expected JSON document";
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    return "body is not a JSON object and carries no status field";
  }
  const keys = Object.keys(parsed);
  if (keys.length !== PAYLOAD_KEYS.length || keys.some((key, at) => key !== PAYLOAD_KEYS[at])) {
    return PROBE_KEY_SET_REASON;
  }
  return fieldRejection(parsed);
}

/**
 * Reduces an answer's Content-Type field values to the ONE media type they name,
 * or "" when they name none unambiguously.
 *
 * Two answers are indistinguishable to a probe and both reduce to "": no such
 * field at all, and more than one of them. Requiring EXACTLY one is what keeps
 * the three implementations in step, because their clients disagree about what a
 * repeated Content-Type means - measured on the wire, Python's http.client joins
 * the values with ", ", this runtime keeps the FIRST and discards the rest, and
 * the JDK client exposes every one - so grading whichever value a client happened
 * to surface would let one implementation accept a duplicated header the other
 * two refused. Node's `res.headers` cannot show the repetition, so the values are
 * read from `res.rawHeaders`, which preserves it.
 *
 * Parameters are stripped and the result folded and trimmed: RFC 9110 section
 * 8.3.1 makes `application/json; charset=utf-8` the same media type as
 * `application/json`, section 5.6.2 permits whitespace around a field value, and
 * section 8.3 defines the type and subtype as case-insensitive tokens.
 * `toLowerCase` rather than `toLocaleLowerCase`, so no ambient locale can fold a
 * token differently. The same reduction is what scripts/verify-health.sh applies.
 *
 * @param {Iterable<string>|undefined} contentTypes Every value the answer carried.
 * @returns {string} The sole media type, or "" when there is not exactly one.
 */
function soleMediaType(contentTypes) {
  const values = contentTypes === undefined || contentTypes === null ? [] : [...contentTypes];
  if (values.length !== 1 || typeof values[0] !== "string") {
    return "";
  }
  const semicolon = values[0].indexOf(";");
  const media = semicolon === -1 ? values[0] : values[0].slice(0, semicolon);
  return media.trim().toLowerCase();
}

/**
 * Collects every Content-Type field value an answer carried, in order.
 *
 * `res.headers` is the wrong source: for this field Node keeps the first value
 * and DISCARDS every later one, so a response carrying `application/json` and
 * then `text/html` is indistinguishable there from one carrying only the first.
 * `res.rawHeaders` is the flat name/value list exactly as received, which is the
 * only place the repetition survives.
 *
 * @param {import("node:http").IncomingMessage} res
 * @returns {string[]} Every value, in the order received.
 */
function answerContentTypes(res) {
  const raw = Array.isArray(res.rawHeaders) ? res.rawHeaders : [];
  const values = [];
  for (let at = 0; at + 1 < raw.length; at += 2) {
    if (String(raw[at]).toLowerCase() === "content-type") {
      values.push(String(raw[at + 1]));
    }
  }
  return values;
}

/**
 * Returns why an answer is not THIS application's, or null when it is.
 *
 * Runs after probeRejection, never instead of it, and answers the question that
 * grader cannot: probeRejection proves an answer satisfies the frozen contract,
 * which ANY application implementing the contract would satisfy. On its own it
 * therefore grades a different process that happens to hold this loopback port
 * healthy, and reports this application up while it is down. `--probe` is the
 * container health check, so that verdict keeps a dead container in service -
 * the one outcome a health check exists to prevent.
 *
 * Three rules, in this order:
 *
 *   1. the answer is served as CONTENT_TYPE, unambiguously - a well-formed health
 *      document delivered as `text/html` did not come from this contract;
 *   2. `name` is exactly the configured application name;
 *   3. `version` is exactly the configured application version.
 *
 * Media type first because it is settled by the FRAMING rather than by the
 * document, and the identity in a document is not worth grading when the framing
 * around it already says the answer is something else.
 *
 * No rule names an observed value. A response body is an input, and an input
 * reaching a log line verbatim is how a forged log entry gets written, so the
 * reasons state only the expectation the configuration already published.
 *
 * The body is parsed here as well as in probeRejection, deliberately: this
 * function must be total for a direct call, so it cannot depend on a caller
 * having parsed first. The parse is PLAIN - the strict rules (a repeated member, a
 * trailing byte, a sequence that is not UTF-8) belong to probeRejection alone,
 * because restating them here would change which of two simultaneous faults gets
 * reported.
 *
 * @returns {string|null} A fixed-category reason, or null when the answer is ours.
 */
function identityRejection(contentTypes, body, expectedName, expectedVersion) {
  if (soleMediaType(contentTypes) !== CONTENT_TYPE) {
    return `the answer is not served as ${CONTENT_TYPE}`;
  }
  const buffer = Buffer.isBuffer(body)
    ? body
    : Buffer.from(body === undefined || body === null ? "" : String(body));
  let parsed;
  try {
    parsed = JSON.parse(buffer.toString("utf8"));
  } catch {
    return "body is not the expected JSON document";
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    return "body is not a JSON object and carries no status field";
  }
  if (parsed.name !== expectedName) {
    return "the name field is not this application's name";
  }
  if (parsed.version !== expectedVersion) {
    return "the version field is not this application's version";
  }
  return null;
}

/** Self-probe deadline. Short, because a health check must answer quickly. */
const PROBE_TIMEOUT_MS = 2500;

/**
 * Requests this application's own health endpoint and reports the verdict as a
 * process exit code, which is the whole machine-readable result - a container
 * runtime can read it without an HTTP client of its own.
 *
 * Deliberately strict: 0 only when the endpoint answers 200, the body satisfies
 * the frozen contract, AND the answer identifies itself as this application -
 * probeRejection followed by identityRejection. Every other outcome - refused
 * connection, expired deadline, wrong status code, oversized body, unparseable
 * body, a document that merely looks right, a well-formed document served by
 * something else on this port, anything unforeseen - yields 1, because a probe
 * that cannot PROVE health must not report it.
 *
 * The identity step exists because the contract grader cannot supply it: a
 * document satisfying the contract is what any conforming implementation serves,
 * so without it a different process holding this loopback port would vouch for
 * this one. The expectation is taken from buildPayload, not restated, so the two
 * can never disagree about what this application publishes.
 *
 * The body ceiling has an operational edge worth knowing here: see
 * MAX_PROBE_BODY_BYTES for the `app.name` budget past which this application's own
 * healthy answer is refused for being too large.
 *
 * The exchange is bounded twice, because either bound alone can be defeated:
 *
 *   - an ABSOLUTE deadline, armed BEFORE the request object exists, so a name
 *     resolution or a connect that hangs is inside the budget. The request-level
 *     `timeout` option is an inactivity timer and cannot do this: a peer that
 *     sends one byte just inside every interval satisfies all of them;
 *   - a ceiling of MAX_PROBE_BODY_BYTES, enforced as chunks ARRIVE rather than
 *     after the body is complete, so an endpoint that streams without end is
 *     bounded in memory as well as in time.
 *
 * `http.request` is used rather than `fetch`, and that is a security choice: it
 * consults no proxy configuration, whereas an environment-aware client can be
 * redirected by an injected `HTTP_PROXY` - demonstrated in the Python
 * implementation to let a fabricated document answer a self-check on behalf of a
 * process that was not running at all. A loopback self-check must never be proxied.
 *
 * The verdict is returned rather than passed to process.exit so the unit tests can
 * call probe() without killing the test runner.
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
  const route = configRoute(config.healthPath);
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
          const answer = Buffer.concat(chunks);
          let rejection = probeRejection(res.statusCode, answer);
          if (rejection === null) {
            // The frozen contract holds. Now prove the answer came from THIS
            // application rather than from whatever else could be holding this
            // loopback port: the expectation is what buildPayload would publish,
            // so a server built from this same configuration always matches and
            // nothing else is assumed to.
            const published = buildPayload(config);
            rejection = identityRejection(
              answerContentTypes(res),
              answer,
              published.name,
              published.version,
            );
          }
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

// Entry point. The guard is what makes this file both runnable and importable:
// requiring it produces no output at all, while running it dispatches the three
// modes. An unrecognised flag falls through to the default branch, so the legacy
// invocation never fails and there is no usage error to print.

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
    // The five writes are the output contract: their number, order and exact
    // bytes are hashed by a committed baseline, so they are neither
    // de-duplicated nor collapsed into a loop.
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
 * `validateConfig`, `probeAuthority`, `probeRejection`, `identityRejection`,
 * `soleMediaType` and `MAX_PROBE_BODY_BYTES` are exported because each is a rule
 * the test suite has to be able to state directly. A rule reachable only through a
 * live socket can be asserted for one happy path and guessed at for the rest;
 * reachable as a function, every branch of it is a test - and the same names are
 * reachable in app.py and UserTest.java, so the three suites assert one contract
 * rather than three dialects of it.
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
  configRoute,
  stripAuthority,
  sanitizeForLog,
  createServer,
  buildServer: createServer,
  serve,
  probe,
  probeAuthority,
  probeRejection,
  identityRejection,
  soleMediaType,
  CONFIG_FILE,
  DEFAULTS,
  ENV_KEYS,
  HEALTH_STATUS,
  CONTENT_TYPE,
  CACHE_CONTROL,
  MAX_PROBE_BODY_BYTES,
};
