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
| Any other path | `404 Not Found` with the body `{"status":"NOT_FOUND"}` |
| A port that cannot be bound at startup | one readable diagnostic on standard error and a non-zero exit status, with no traceback and no stack trace |

### Notes on the wire format

- `application/json` is served rather than the `application/health+json` of the IETF health
  check draft, whose IANA registration was never completed. `application/json` is parsed by
  every client without special handling.
- `Cache-Control: no-store` is sent rather than a freshness lifetime, because `timestamp` is
  generated for each request and serving a cached copy would defeat it.
- `User.java` serves through the Java platform's own HTTP server, `com.sun.net.httpserver`.
  Three of its behaviours differ from the other two applications' and are properties of that
  platform rather than omissions here:
  - It renders response field names with a single leading capital - `Content-type`,
    `Content-length`, `Cache-control`. Field names are case insensitive, so these responses
    carry the same headers as the other two applications'.
  - It sends no `Content-length` on a `HEAD` reply, because the no-body form of its API
    suppresses that field. This is a recorded limitation, and an acceptable one for a
    liveness probe.
  - It routes on the request target parsed as a URI, and answers a target it cannot match to
    a route with its own `text/html` page before this application's handler is reached. Two
    families of target are affected: one it cannot parse, and one whose parsed path is empty
    or is not a path at all - a target opening with two slashes, such as `//health`, or the
    asterisk form `*`. `app.py` and `index.js` read the target off the request line and
    answer both families with the JSON above; `User.java` cannot, because that reply is
    written before any handler, filter or authenticator of its own can run. Every target that
    does reach this application is answered with the JSON above, and the platform's own reply
    still carries the status this contract asks for and carries no host name, no file system
    path, no environment value and no stack trace.

## Example

```console
$ curl -s http://127.0.0.1:3000/health
{"name":"calculator-app","version":"1.0.0","timestamp":"2026-08-01T03:06:47.996Z","status":"UP"}
```

## Running the applications

No installation step is required for any of the three applications. There is nothing to
`pip install`, nothing to `npm install` and nothing for Maven or Gradle to resolve: each
program uses only its own language's standard library.

Default mode is unchanged by the health endpoint. Each application prints what it has always
printed, opens no port and exits immediately.

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
reachable from the host it runs on and from nowhere else unless `HEALTH_HOST` says
otherwise. A variable that is set but unusable - a port written in anything but one to five
decimal digits or falling outside 1 to 65535, or a host containing blanks or control
characters - stops startup with one diagnostic on standard error and a non-zero exit status,
rather than silently binding the default.

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
