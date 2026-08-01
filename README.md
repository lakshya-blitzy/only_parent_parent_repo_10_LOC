# only_parent_parent_repo_10_LOC

## Applications

This repository holds three independent single-file applications. They share no code and no
process, and each one exposes the same read-only HTTP health endpoint at `/health`.

| File | Language | Application name | Default port |
| --- | --- | --- | --- |
| `app.py` | Python 3 | `greeter-app` | 8000 |
| `index.js` | JavaScript (CommonJS) | `calculator-app` | 3000 |
| `User.java` | Java | `user-app` | 8080 |

Each application name is derived from what that program does, and each name and default port is
a fixed property of the artifact. The three default ports do not collide, so all three
applications can serve at the same time on one host.

## Health endpoint

Every application answers the same request on its own port.

| Element | Value |
| --- | --- |
| Methods | `GET` and `HEAD` |
| Path | exactly `/health`. A query string is ignored, so `/health?probe=1` matches, while `/health/` and `/healthz` do not. There is no trailing-slash alias and no version prefix |
| Status when healthy | `200 OK` |
| `Content-Type` | `application/json` |
| `Cache-Control` | `no-store` |
| `Content-Length` | set on the `GET` response |
| Encoding | UTF-8 |

Every value in this table is a property of the responses these applications compose for
themselves, `Cache-Control: no-store` included. `User.java` also has request forms that the
Java platform answers before its handler runs, and those replies carry neither `Cache-Control`
nor `Date`; they are set out in the wire format notes below.

### Response body

The body is a JSON object carrying exactly four members, all of them strings, emitted in this
order.

| Field | Meaning |
| --- | --- |
| `name` | the application's name: `greeter-app`, `calculator-app` or `user-app` |
| `version` | the application version, `1.0.0` in all three applications |
| `timestamp` | the instant the response was generated. RFC 3339 / ISO 8601 UTC with millisecond precision and a trailing `Z`, shaped `YYYY-MM-DDTHH:MM:SS.mmmZ`. It is generated per request and never cached, so two responses a second apart carry different values |
| `status` | the literal `UP` |

Those four members are the whole document. The body deliberately carries no host name, no file
system path, no environment value and no diagnostic text, because a health response must not
disclose internal detail.

### Other requests

| Request | Response |
| --- | --- |
| `HEAD /health` | `200` with the same headers and an empty body |
| Any other method on `/health` | `405 Method Not Allowed` with `Allow: GET, HEAD` and the body `{"status":"METHOD_NOT_ALLOWED"}` |
| `GET` on any other path | `404 Not Found` with the body `{"status":"NOT_FOUND"}` |
| `HEAD` on any other path | `404 Not Found` with the same headers and an empty body |
| A request an application's own runtime refuses before routing | that runtime's own status, and a reply of that runtime's own shape rather than this table's: `app.py` and `index.js` answer with a JSON body, while `User.java`'s platform answers with its own `text/html` page. Every measured form is named below |
| A port that cannot be bound at startup | one readable diagnostic on standard error and a non-zero exit status, with no traceback and no stack trace |

The first four rows describe the replies each application composes for itself. The fifth is the
exception, and it is not a footnote: a request the runtime beneath an application refuses before
routing never reaches the code that composes those rows, so its status, headers and body are that
runtime's rather than this table's. For `User.java` the forms that go that way include `//health`,
which its platform answers with a `text/html` page carrying no `Cache-Control`, and not with the
JSON `404` the other two applications return for it.

A `HEAD` reply carries no body: it is the status line and the headers of its `GET` counterpart
with nothing after them, on `/health` and on any other path alike. Every other method does
receive the body named above.

A request target is compared exactly as the request line carried it, so nothing is an alias of
`/health`: `///health`, `//x/health` and an absolute-form `http://host/health` all answer as any
other unmatched path does, in all three applications. `app.py` routes every target it is given,
including the asterisk form `*` and one that is not addressed to a path at all. `index.js` routes
`*` as well, and its runtime refuses a target carrying no leading slash, such as `foo`, with
`400`. `User.java` is the one application whose platform answers `//health`, `*` and `foo` itself,
before its handler runs.

The shapes those runtime-refused replies take, application by application:

- `app.py` answers `{"status":"ERROR","code":N}`, naming the status it chose: `400` for a
  request line it cannot read, `414` for a request target longer than it accepts, `431` from a
  hundred header fields upwards, `505` for an HTTP version it does not implement.
- `index.js` answers `{"status":"BAD_REQUEST"}` with `400`, which is what a target not
  addressed to a path, such as `foo`, receives there; `{"status":"REQUEST_TIMEOUT"}` with `408`
  when a client stops sending part-way through; and
  `{"status":"REQUEST_HEADER_FIELDS_TOO_LARGE"}` with `431` once the header block passes the
  size its runtime accepts.
- `User.java` answers such a request through the Java platform's server instead, which is the
  subject of the wire format notes below.

Both of the JSON shapes above carry `application/json` and `Cache-Control: no-store` like every
other reply, and neither ever puts request text into a body. `app.py` and `User.java` answer a
client that stops sending nothing at all: each closes the connection after about ten seconds.
The `User.java` forms are worth reading in full, because several of them look ordinary and
several of them are served normally by the other two applications.

### Notes on the wire format

- `application/json` is served rather than the `application/health+json` of the IETF health
  check draft, whose IANA registration was never completed. `application/json` is read by the
  JSON-capable clients this endpoint is for - `curl`, common monitors and browsers - without a
  media type any of them has to be taught.
- `Cache-Control: no-store` is sent rather than a freshness lifetime, because `timestamp` is
  generated for each request and serving a cached copy would defeat it.
- `User.java` serves through the Java platform's own HTTP server, `com.sun.net.httpserver`,
  bound directly to the configured address with one handler registered at the root. Some of its
  behaviour is a property of that platform rather than an omission here:
  - It renders the response field names it is given with a single leading capital -
    `Content-type`, `Content-length`, `Cache-control`. Field names are case insensitive, so a
    reply its handler produced carries the same headers as the other two applications'.
  - It sends no `Content-length` on a `HEAD` reply, because the no-body form of its API
    suppresses that field. That is a recorded limitation of that reply.
  - Some requests never reach the handler at all. That server splits the request line into
    three tokens, parses the request target as a URI, and then matches a handler on the path it
    parses out of that target. A handler path must begin with a slash, so the handler at the
    root matches every parsed path that does. A request the server cannot carry through those
    steps, or whose parsed path does not begin with a slash, is answered by the platform itself
    before the handler is reached, with its own `text/html` page - a page, not an empty body,
    even for a `HEAD`. The forms measured to do so are:
    - a target that is not a legal URI, such as `/he^alth`, `/a{b}`, `/a|b` or `/a%zz`: `400`.
      The other two applications answer `404` with `{"status":"NOT_FOUND"}`.
    - a target it can parse but whose path matches no handler: `404`. `//health` is the form
      worth knowing, because it looks like the endpoint and is not - a target opening with two
      slashes parses as a network-path reference, so `health` is read as its authority and its
      path is left empty. The asterisk form `*` and a target carrying no leading slash such as
      `foo` reach the same refusal, their paths being `*` and `foo`, neither of which a handler
      path can match.
    - a request line that does not split into three tokens, such as `GET /health` with no HTTP
      version, or a bare `GET`: `400`. **The other two applications serve `GET /health` with no
      version as an ordinary request, answering `200` and the health document.**
    - a request line whose tokens are separated by more than one blank, such as
      `GET  /health  HTTP/1.1`: `404`. **The other two serve that as an ordinary request too.**
    - a `Content-Length` it cannot read as a number, two of them that disagree, or a header key
      holding an illegal character: `400`. This one does not depend on the request target at all.
    - a request carrying more than two hundred header fields: **no reply at all**, the
      connection is closed. `app.py` refuses such a request too, but with `431` and a JSON
      body, from a hundred fields upwards; `index.js` serves it, because its own limit is on
      the total size of the header block rather than on how many fields it holds.

    Those replies carry **no `Cache-Control` and no `Date`**, and their field names are in the
    conventional form - `Content-Length`, `Content-Type`, `Connection` - rather than the
    single-leading-capital form described above, so the endpoint table's header values do not
    describe them. Two of the pages name the Java exception type the platform caught,
    `URISyntaxException` or `NumberFormatException`, which is a platform fingerprint; none of
    them carries a stack trace, a host name, a file system path or any environment value.

    The platform's reply cannot be replaced from inside that server: no handler can be
    registered for an empty path, and every hook it offers - a filter, an authenticator, a
    handler predicate - runs only once a handler has already been matched. Answering these forms
    here instead would mean placing a second listener in front of the platform's server, which
    this repository does not do. `app.py` and `index.js` are not affected: each of them routes
    every target it is given, and where its own runtime refuses a request line outright it
    answers with that runtime's status and a JSON body, as the section above describes.

## Example

```console
$ curl -s http://127.0.0.1:3000/health
{"name":"calculator-app","version":"1.0.0","timestamp":"2026-08-01T03:06:47.996Z","status":"UP"}
```

## Running the applications

No installation step is required for any of the three applications. There is nothing to
`pip install`, nothing to `npm install` and nothing for Maven or Gradle to resolve: each
program uses only its own language's standard library.

The health endpoint leaves default mode alone: each application prints its own original output,
opens no port and exits immediately. `index.js` printed its five `12` lines before this change
and prints them still. `app.py` and `User.java` could not run at all until the duplicated
trailing block that stopped the one from parsing and the other from compiling was removed, which
this change also does; each now prints the line it was written to print, and nothing else about
default mode differs.

```console
$ python3 app.py                  # prints: Hello Lakshya
$ node index.js                   # prints: 12, five times
$ javac User.java && java User    # prints: Test
```

The endpoint is opt-in: pass `--serve`. Each application prints its usual output first, then
serves `/health` until it is stopped.

```console
$ python3 app.py --serve
$ node index.js --serve
$ javac User.java && java User --serve
```

`User.java` can also be run straight from source, which needs no separate compile step:

```console
$ java User.java --serve
```

`Ctrl-C` (`SIGINT`) or `SIGTERM` stops a serving application: it writes a shutdown notice and
releases the port. `app.py` and `index.js` exit `0`. `User.java` exits with the conventional
128 + signal number a JVM reports when it is signalled - `143` for `SIGTERM` and `130` for
`SIGINT` - which is expected behaviour and not an error.

For `User.java` that holds for a signal it can receive, and one more property of the Java
platform decides which those are: a signal already ignored when a JVM starts stays ignored,
because the JVM does not install a handler over an inherited disposition of ignore, and a signal
that is ignored is discarded before the process can see it. A shell running a script has no job
control, and such a shell ignores `SIGINT` on behalf of every background job it starts, so a
`java User --serve &` written inside a script cannot be stopped by `SIGINT` - send `SIGTERM`,
which that shell leaves alone for a background job. `app.py` and `index.js` are unaffected:
`signal.signal` and `process.on` each replace the inherited disposition, so both of them answer
either signal in any launch mode.

That state is decided by the launch, before this program runs, so the launch is where it is
undone. Either of these leaves `SIGINT` deliverable, after which `User.java` answers it exactly
as the other two do - shutdown notice on standard error, port released, exit `130`:

```console
$ java User --serve        # foreground: Ctrl-C, or a SIGINT sent from elsewhere, stops it
$ set -m                   # in a script: turn job control on before the launch, then
$ java User --serve &      # this job keeps SIGINT, so kill -INT on its pid stops it
```

Asking the platform for a signal handler instead does not work, and is therefore not done here:
its unsupported signal API reports the inherited ignore straight back and installs nothing, so
the signal stays undeliverable - and naming that API in this file would cost the warning-free
`javac -Xlint:all` build this repository keeps. `SIGTERM` needs none of this, because a shell
leaves it alone for a background job, so a script that stops the listener with `SIGTERM` works
in every launch mode.

`User.java` says this itself rather than leaving it to be discovered, writing one line to
standard error beside its startup banner that names both the signal that will stop it as
launched and the launch that would make the ignored one work. It writes nothing when both
signals can be delivered, and names `SIGKILL` when neither can.

```console
user-app: SIGINT is ignored by this process and cannot stop this listener; send SIGTERM instead, or start it where SIGINT is not ignored - in the foreground, or with job control enabled (set -m)
```

Startup banners, request records, failure diagnostics and shutdown notices all go to standard
error, so standard output carries only each application's own original output.

## Configuration

Two optional environment variables move the listener. Each application reads both of them
directly from the process environment.

| Variable | Purpose | Default |
| --- | --- | --- |
| `HEALTH_HOST` | bind address for the health listener | `127.0.0.1` |
| `HEALTH_PORT` | listen port for the health listener | `8000` for `app.py`, `3000` for `index.js`, `8080` for `User.java` |

Binding is loopback-only by default, which is the intended secure default: the listener is
reachable from the host it runs on and from nowhere else unless `HEALTH_HOST` says otherwise.

Surrounding ASCII blanks are trimmed from both variables, and a value that is empty or entirely
blank counts as unset: it falls back to the built-in default above rather than stopping startup.
What does stop startup - with one diagnostic on standard error and a non-zero exit status - is a
value with something left after trimming that still fails the grammar: a port that is not one to
five decimal digits, or that falls outside 1 to 65535, or a host with a blank or a control
character inside it. All three applications apply that grammar identically and word a rejection
the same way, and none of them quotes the rejected value back.

**The endpoint requires no configuration file.** This repository contains no `package.json`, no
`requirements.txt`, no `pyproject.toml`, no `pom.xml`, no `build.gradle`, no `.env` and no
other configuration or build manifest of any kind, and none is needed: the two variables above
are read straight from the process environment with safe built-in defaults, so no file has to
exist for either of them to take effect.

The application `name` and `version` are deliberately not overridable from the environment.
They describe the artifact rather than the host it happens to run on, so each is a named
constant in its own source file.

## Repository structure

The repository is flat: four files at the root - `README.md`, `app.py`, `index.js` and
`User.java` - and no subdirectories.

It also contains **no git submodules**, neither a child submodule nor a nested one, so the
parent repository is the complete applicable scope for any change made here. That was verified
six independent ways: `.gitmodules` is absent from the working tree; `git submodule status`
prints nothing; a scan of the `HEAD` tree for mode `160000` gitlink entries finds none;
`git log --all -- .gitmodules` shows the file has never existed on any ref; there is no
`.git/modules` directory; and there is no nested `.git` directory below the root.
