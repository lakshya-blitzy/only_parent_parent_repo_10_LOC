"""Unit tests for app.py - the preserved legacy behaviour and the /health endpoint.

A flat sibling of ``app.py`` rather than a member of a ``tests/`` package, matching
the repository's flat layout, and importing nothing outside the Python standard
library plus ``app`` itself.  That keeps the project's zero-dependency property
intact for its tests as well as for its application code.

Two rules govern the assertions themselves.

The timestamp is asserted by FORMAT and never by value.  It is the only
non-deterministic field in the payload, and comparing it to a computed instant
would make this suite fail for reasons unrelated to correctness.  No assertion in
this file compares a timestamp to anything.  The two places that do measure
elapsed time - the drain budget and the pre-parse deadline - assert only that a
bound was enforced, against a budget the test itself shortened first, and never
that a duration equalled a value.

Every header assertion is case-insensitive.  RFC 9110 makes field names
case-insensitive, and the sibling Java implementation normalises their casing, so
matching case-insensitively is what keeps the three language suites asserting the
same contract rather than three dialects of it.
"""

import contextlib
import datetime
import io
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest import mock

import app

# The frozen contract, written out in full.  These literals are deliberately NOT
# imported from app: a test that reads its expectation out of the module under test
# can never fail when that module changes.  Duplication here is the point.

#: Wire order of the health document.  Order is part of the contract, so this is
#: a list and is compared against ``list(payload.keys())``, not a set.
EXPECTED_KEY_ORDER = ["name", "version", "timestamp", "status"]

#: The literal status value the endpoint must report.
EXPECTED_STATUS = "UP"

#: The five built-in defaults, used when neither the environment nor the
#: properties file supplies a value.
EXPECTED_DEFAULTS = {
    "app.name": "only_parent_parent_repo_10_LOC",
    "app.version": "1.1.0",
    "health.path": "/health",
    "app.host": "0.0.0.0",
    "python.port": "8000",
}

#: Length in bytes of the health body for the default name and version.  This
#: number is a regression gate for the compact separators: rendering the same
#: four fields with json.dumps' default separators yields 115 bytes, which is
#: what the JavaScript and Java implementations would then fail to match.
REFERENCE_BODY_LENGTH = 108

EXPECTED_CONTENT_TYPE = "application/json"
EXPECTED_NOT_FOUND_BODY = b'{"error":"Not Found"}'
EXPECTED_METHOD_NOT_ALLOWED_BODY = b'{"error":"Method Not Allowed"}'
EXPECTED_ALLOW_HEADER = "GET"

#: Fixed-width UTC instant, whole seconds, ``Z`` zone designator.
TIMESTAMP_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
TIMESTAMP_STRPTIME_FORMAT = "%Y-%m-%dT%H:%M:%SZ"
TIMESTAMP_LENGTH = 20

#: Three-part dotted numeric version.
VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+$")

#: Test servers bind loopback, never the configured wildcard address: a test has
#: no business accepting connections from off the host.
LOOPBACK = "127.0.0.1"

#: Short by design.  A hung request must fail the test quickly instead of stalling
#: the run until an outer timeout fires.
REQUEST_TIMEOUT_SECONDS = 3.0

#: Ceiling for a subprocess that is expected to exit immediately.
SUBPROCESS_TIMEOUT_SECONDS = 30

#: Ceiling for joining the server thread during teardown.
THREAD_JOIN_TIMEOUT_SECONDS = 5

#: Every environment variable that can influence the endpoint's configuration.
#: Collected from app so that a variable added there is neutralised here too,
#: which is the one case where reading from the module is the safer choice.
HEALTH_ENV_NAMES = tuple(app.ENV_OVERRIDES.values()) + (app.UNIVERSAL_PORT_ENV,)

#: Directory holding app.py.  Subprocess tests run there so that ``import app``
#: resolves, wherever the runner itself was started from.
APP_DIRECTORY = os.path.dirname(os.path.abspath(app.__file__))


#: The loopback destinations a probe is permitted to dial, in the exact spelling
#: :func:`app.probe_authority` must produce for each of them.
EXPECTED_LOOPBACK_AUTHORITY = "127.0.0.1"
EXPECTED_LOOPBACK_AUTHORITY_V6 = "[::1]"



#: How long a deliberately broken endpoint keeps a connection open, and how often
#: its accept loop wakes to notice it has been asked to stop.  Both are bounded so
#: that a helper thread can never outlive the test run.
HOSTILE_ENDPOINT_LIFETIME_SECONDS = 10
ACCEPT_POLL_SECONDS = 0.25

#: One chunked-encoding chunk carrying a single byte, and the gap between two of
#: them.  A stream of these is the case a per-read timeout cannot bound: every
#: individual read succeeds, so only an absolute deadline ever ends it.
TRICKLE_CHUNK = b"1\r\nA\r\n"
TRICKLE_INTERVAL_SECONDS = 0.05

#: Ceilings for the raw-socket reads these tests perform.  A test client must be
#: bounded for the same reason the server is: an unbounded read against a
#: misbehaving peer hangs the suite instead of failing it.
RAW_READ_LIMIT = 65536
RAW_CHUNK_BYTES = 4096


#: Gap between two requests pushed down one connection.  Long enough that the
#: server has certainly answered the first before the second arrives, so the test
#: observes reuse of an idle connection rather than a pipelined burst.
PIPELINE_GAP_SECONDS = 0.3

#: Drain budget used by the test that must watch the budget expire.  Applied by
#: assigning the module attribute for the duration of one test and restored on
#: every path, so the production budget is never left mutated.
SHORT_DRAIN_BUDGET_SECONDS = 0.5

#: Pre-parse deadline used by the tests that must watch a silent client be
#: closed.  Applied to the handler class - which is where the deadline lives, and
#: where ``setup`` reads it for each accepted connection - for the duration of one
#: test, and restored on every path.  Waiting out the production deadline would
#: add ten seconds per case for no additional assurance: what is under test is
#: that a deadline exists and is enforced, not what number it carries, and the
#: number itself is asserted separately as a constant.
SHORT_HEADER_BUDGET_SECONDS = 0.5

#: Ceiling for waiting on something the SERVER must do unprompted - close an idle
#: connection, release a worker thread.  Generous next to the budget under test so
#: that a loaded runner cannot fail this suite merely for being slow, and bounded
#: so that a regression fails the test instead of hanging the run.
SETTLE_TIMEOUT_SECONDS = 15.0

#: Interval between polls while waiting for the server to settle.
SETTLE_POLL_SECONDS = 0.05

#: Silent connections opened at once by the thread-accumulation test.  Enough
#: that an unbounded handler would leave a visible pile of parked threads, few
#: enough to stay well inside any file-descriptor limit.
SILENT_CONNECTION_COUNT = 8

#: Request-line and header-block sizes that exceed what the transport will read.
#: The stdlib reads at most 65537 bytes of a request line and at most 100 header
#: lines, and refuses beyond either; both ceilings are exceeded generously so the
#: test does not sit on the boundary of an implementation detail it does not own.
OVERSIZED_REQUEST_LINE_BYTES = 70000
EXCESSIVE_HEADER_COUNT = 150

#: Chunk size for writing an oversized request to the socket.  Written in pieces
#: rather than in one call so that a send interrupted by the server's own close
#: is reported as a short write instead of stalling the test.
RAW_SEND_CHUNK_BYTES = 8192

#: A body far larger than any legitimate health document, used to prove the probe
#: refuses rather than accumulates.  The endpoint's own body is 108 bytes.
OVERSIZED_BODY_BYTES = 60000

#: Largest response body the probe may read, written out here rather than read
#: from app so that this is a gate on the ceiling and not a mirror of it.  A body
#: of exactly this length must still be accepted; one byte more must be refused.
PROBE_BODY_CEILING = 8192

#: A 200 response whose body is valid-looking but truncated JSON that still
#: CONTAINS the healthy status fragment.  A probe that matched on a substring
#: would grade this healthy; one that parses cannot.
FORGED_STATUS_BODY = b'{"name":"x","version":"1.1.0","status":"UP"'


# Helpers


def write_properties(case, text):
    """Write ``text`` to a temporary properties file and return its path.

    The file is removed by the test's own cleanup stack, so a failing assertion
    cannot leak it.  A temporary file is used rather than the committed
    ``app.config.properties`` because a test must never edit the file the
    application ships with.
    """
    handle = tempfile.NamedTemporaryFile(
        mode="w", suffix=".properties", delete=False, encoding="utf-8"
    )
    try:
        handle.write(text)
    finally:
        handle.close()
    case.addCleanup(os.unlink, handle.name)
    return handle.name


def absent_properties_path(case):
    """Return a path that is guaranteed not to exist, inside a temp directory.

    Used to exercise the "no properties file at all" branch without depending on
    a filename simply happening to be absent from the filesystem.
    """
    directory = tempfile.TemporaryDirectory()
    case.addCleanup(directory.cleanup)
    return os.path.join(directory.name, "absent.properties")


def defaults_only_config(case):
    """Resolve configuration with no environment and no properties file.

    Every value therefore comes from app's built-in defaults, which makes the
    resulting payload deterministic regardless of the environment the suite runs
    in or what the committed properties file happens to say.
    """
    return app.load_config(path=absent_properties_path(case), env={})


def header_names(headers):
    """Return response header names, lower-cased.

    RFC 9110 field names are case-insensitive, so every assertion about which
    headers are present - and especially about which are absent - compares
    lower-cased names.  A case-sensitive check would pass here and fail against
    the sibling Java implementation, which normalises the casing it emits.
    """
    return {name.lower() for name in headers.keys()}


def unused_port():
    """Return a TCP port with nothing listening on it.

    The kernel assigns an ephemeral port, which is then released.  This is for
    the fail-closed probe test, which needs an address that refuses connections;
    picking a number by hand would eventually collide with a real listener.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe_socket:
        probe_socket.bind((LOOPBACK, 0))
        return probe_socket.getsockname()[1]


def json_head(length, status=b"HTTP/1.1 200 OK"):
    """Build a complete, well-formed response head advertising ``length`` bytes."""
    return (
        status
        + b"\r\nContent-Type: application/json\r\nContent-Length: "
        + str(length).encode("ascii")
        + b"\r\n\r\n"
    )


#: The version and instant :func:`padded_document` writes, and the fixed bytes it
#: puts either side of its ``name`` field.  Named rather than inlined because the
#: probe grades IDENTITY as well as shape, so a test that serves a padded document
#: must also configure the matching name and version; deriving the document and the
#: configuration from these is what keeps the two equal by construction.
PADDED_DOCUMENT_VERSION = "1.1.0"
PADDED_DOCUMENT_PREFIX = b'{"name":"'
PADDED_DOCUMENT_SUFFIX = (
    '","version":"'
    + PADDED_DOCUMENT_VERSION
    + '","timestamp":"2026-01-01T00:00:00Z","status":"UP"}'
).encode("ascii")


def padded_name(length):
    """Return the ``name`` value :func:`padded_document` carries at ``length`` bytes.

    Exposed separately so a test can configure the identity it is about to serve.
    """
    padding = length - len(PADDED_DOCUMENT_PREFIX) - len(PADDED_DOCUMENT_SUFFIX)
    if padding < 0:
        raise ValueError(f"{length} is shorter than the smallest valid document")
    return "a" * padding


def padded_document(length):
    """Return a VALID healthy health document padded to exactly ``length`` bytes.

    Used to probe the body ceiling from both sides.  The padding goes in the
    ``name`` field, so the document stays a well-formed JSON object reporting the
    healthy status and the only thing under test is its length.
    """
    return (
        PADDED_DOCUMENT_PREFIX
        + padded_name(length).encode("ascii")
        + PADDED_DOCUMENT_SUFFIX
    )


class HostileEndpoint:
    """A raw TCP listener that answers the way a broken health endpoint would.

    The probe is a client, and a client is only as safe as its behaviour against a
    peer that does not cooperate.  These are the peers that matter: one that never
    answers, one that answers forever, one that answers with far too much, and one
    that answers with something that merely looks right.  Each is a few lines of
    socket code, which is why this is written out rather than mocked - a mock of
    :mod:`http.client` would prove the test's own assumptions, not the bound.

    The listener keeps accepting until it is closed, so one instance can serve
    several probes, and every wait is bounded so that no helper thread can outlive
    the run.
    """

    def __init__(self, head=b"", body=b"", trickle=False, mute=False):
        """Start the listener on an ephemeral loopback port.

        :param trickle: keep emitting one-byte chunks until stopped.
        :param mute: accept the connection and then say nothing at all.
        """
        self._head = head
        self._body = body
        self._trickle = trickle
        self._mute = mute
        self._stop = threading.Event()
        self._listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._listener.bind((LOOPBACK, 0))
        self._listener.listen(4)
        self._listener.settimeout(ACCEPT_POLL_SECONDS)
        self.port = self._listener.getsockname()[1]
        self._thread = threading.Thread(
            target=self._run, name="hostile-health-endpoint", daemon=True
        )
        self._thread.start()

    def _run(self):
        """Accept connections until stopped, serving each one in turn."""
        while not self._stop.is_set():
            try:
                connection, _ = self._listener.accept()
            except TimeoutError:
                continue
            except OSError:
                return
            with connection:
                self._serve(connection)

    def _serve(self, connection):
        """Answer one connection in whichever broken way was configured."""
        try:
            if self._mute:
                self._stop.wait(HOSTILE_ENDPOINT_LIFETIME_SECONDS)
                return
            connection.sendall(self._head)
            if self._body:
                connection.sendall(self._body)
            while self._trickle and not self._stop.is_set():
                connection.sendall(TRICKLE_CHUNK)
                self._stop.wait(TRICKLE_INTERVAL_SECONDS)
            self._stop.wait(HOSTILE_ENDPOINT_LIFETIME_SECONDS)
        except OSError:
            # The probe hung up, which for most of these cases is the point.
            return

    def close(self):
        """Stop the loop, join the thread, release the listening socket."""
        self._stop.set()
        self._thread.join(timeout=THREAD_JOIN_TIMEOUT_SECONDS)
        self._listener.close()


def hostile_endpoint(case, **kwargs):
    """Return a started :class:`HostileEndpoint` that the test will clean up."""
    endpoint = HostileEndpoint(**kwargs)
    case.addCleanup(endpoint.close)
    return endpoint


def read_response(client, limit=RAW_READ_LIMIT):
    """Read from ``client`` until it closes or ``limit`` bytes have arrived.

    A timeout is treated as end of input rather than as an error: several of these
    tests are asserting what a server does NOT send, and for those a timeout is
    the expected outcome.  ``client`` must already have a timeout set.
    """
    chunks = []
    total = 0
    try:
        while total < limit:
            chunk = client.recv(min(RAW_CHUNK_BYTES, limit - total))
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
    except (TimeoutError, OSError):
        pass
    return b"".join(chunks)


def read_one_response(client):
    """Read exactly ONE response - head plus its advertised body - from ``client``.

    Needed by the keep-alive tests, which must consume one response and leave the
    connection open for the next request.  ``read_response`` cannot do that: it
    reads to end of stream, which on a persistent connection never comes.
    """
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = client.recv(RAW_CHUNK_BYTES)
        if not chunk:
            return data
        data += chunk
    head, _, body = data.partition(b"\r\n\r\n")
    length = 0
    for line in head.split(b"\r\n")[1:]:
        name, _, value = line.partition(b":")
        if name.strip().lower() == b"content-length":
            length = int(value.strip())
    while len(body) < length:
        chunk = client.recv(RAW_CHUNK_BYTES)
        if not chunk:
            break
        body += chunk
    return head + b"\r\n\r\n" + body


def parse_raw_response(received):
    """Split raw response bytes into ``(status_line, headers, body)``.

    The raw-socket tests cannot go through ``urllib``: several of them send a
    request line no HTTP client would agree to construct, and one of them asserts
    that nothing comes back at all.  Parsing is deliberately minimal - split once
    on the header terminator, then once on the first colon of each field line -
    because what these tests assert is what the server WROTE, and a forgiving
    parser would paper over exactly the departures they exist to catch.

    Field names are lower-cased on the way in, so the result can be handed to
    :func:`header_names` and compared case-insensitively like every other header
    assertion in this file.

    :returns: the status line without its terminator, a mapping of lower-cased
        field names to their stripped values, and the body bytes.  A response
        with no header terminator yields an empty mapping and an empty body
        rather than raising, so a test can assert on a truncated reply.
    """
    head, separator, body = received.partition(b"\r\n\r\n")
    if not separator:
        return received, {}, b""
    lines = head.split(b"\r\n")
    headers = {}
    for line in lines[1:]:
        name, colon, value = line.partition(b":")
        if colon:
            headers[name.strip().lower().decode("latin-1")] = (
                value.strip().decode("latin-1")
            )
    return lines[0], headers, body


def send_in_chunks(client, payload):
    """Write ``payload`` to ``client``, tolerating a close by the peer.

    The transport-rejection tests deliberately send more than the server will
    read: it answers and retires the connection while the rest is still in
    flight, which surfaces here as a broken pipe.  That is the expected outcome
    of the case, not a failure of it, so the write stops and the test goes on to
    read the refusal the server already wrote.

    :returns: True when the whole payload was accepted, False when the peer
        closed the connection first.
    """
    for index in range(0, len(payload), RAW_SEND_CHUNK_BYTES):
        try:
            client.sendall(payload[index:index + RAW_SEND_CHUNK_BYTES])
        except OSError:
            return False
    return True


def raw_exchange(port, request, timeout=REQUEST_TIMEOUT_SECONDS):
    """Send ``request`` verbatim on a fresh connection and read the whole reply.

    Bypassing ``urllib`` is the point: these tests send request lines and header
    blocks that no HTTP client would agree to construct, and they assert on the
    exact bytes that come back rather than on a parsed object.  The read ends when
    the server closes the connection, which every refusal path does.

    :param timeout: read ceiling, so a regression fails the test rather than
        hanging it.
    :returns: everything the server wrote, which may legitimately be empty.
    """
    client = socket.create_connection((LOOPBACK, port), timeout=REQUEST_TIMEOUT_SECONDS)
    try:
        send_in_chunks(client, request)
        client.settimeout(timeout)
        return read_response(client)
    finally:
        client.close()


def raw_exchange_with_eof(port, request, timeout=REQUEST_TIMEOUT_SECONDS):
    """Send ``request``, then END the stream, and read the whole reply.

    The difference from :func:`raw_exchange` is the half-close, and it is the whole
    point of the helper: a request that stops mid-block is indistinguishable from a
    slow client until the writing half closes, so without the shutdown the server
    correctly waits for the rest and the test would measure the pre-parse deadline
    instead of the framing decision.  With it, the server sees end of stream where a
    header block should have continued, which is the case being asserted.
    """
    client = socket.create_connection((LOOPBACK, port), timeout=REQUEST_TIMEOUT_SECONDS)
    try:
        send_in_chunks(client, request)
        try:
            client.shutdown(socket.SHUT_WR)
        except OSError:
            # Already retired by the peer, which is one of the outcomes under test.
            pass
        client.settimeout(timeout)
        return read_response(client)
    finally:
        client.close()


def await_settled(predicate):
    """Poll ``predicate`` until it is true or :data:`SETTLE_TIMEOUT_SECONDS` passes.

    Used where the assertion is about something the SERVER does on its own
    schedule - closing an idle connection, releasing a worker thread - which
    cannot be observed synchronously.  A fixed sleep long enough to be reliable
    would make the suite slow, and one short enough to be quick would make it
    flaky; polling with a bounded ceiling is neither.

    :returns: the final value of the predicate, so the caller asserts on it.
    """
    deadline = time.monotonic() + SETTLE_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(SETTLE_POLL_SECONDS)
    return predicate()


def neutralize_health_environment():
    """Remove every health-related variable from ``os.environ``, restorably.

    A live server resolves its configuration ONCE, when it is constructed, from
    the real process environment and the committed properties file.  A test that
    drives one therefore cannot hand it an injected mapping the way the loader
    tests do: the ambient variables have to be taken out of the way BEFORE the
    server is built, or a developer with ``HEALTH_PATH`` exported would see this
    suite fail against a perfectly correct implementation.

    ``patch.dict`` snapshots the entire mapping and restores it when stopped, so
    variables this suite never looked at are put back untouched, and so is a
    health variable the caller's own environment legitimately set.  Only the six
    names the endpoint consults are removed, and nothing is added.

    :returns: the started patcher.  The caller MUST stop it - through
        ``addCleanup`` or a ``finally`` - so the environment is always restored.
    """
    patcher = mock.patch.dict(os.environ)
    patcher.start()
    for name in HEALTH_ENV_NAMES:
        os.environ.pop(name, None)
    return patcher


# Preserved legacy behaviour - the backward-compatibility contract.  The original
# greeting function and the default invocation of the script are pinned byte-exactly.


class TestGreet(unittest.TestCase):
    """The greeting function, unchanged since before the endpoint existed."""

    def test_greet_returns_the_original_greeting(self):
        """The exact string the program has always produced for its own user."""
        self.assertEqual(app.greet("Lakshya"), "Hello Lakshya")

    def test_greet_interpolates_any_name(self):
        """One space, no punctuation, the name verbatim - for any input."""
        cases = {
            "X": "Hello X",
            "Ada Lovelace": "Hello Ada Lovelace",
            "O'Brien": "Hello O'Brien",
            "\u30e9\u30af\u30b7\u30e3": "Hello \u30e9\u30af\u30b7\u30e3",
            "": "Hello ",
        }
        for name, expected in cases.items():
            with self.subTest(name=name):
                self.assertEqual(app.greet(name), expected)

    def test_greet_stringifies_a_non_string_argument(self):
        """Existing behaviour: the f-string coerces rather than rejecting.

        This is documented here as the current contract, not endorsed as a
        design.  Changing it would change observable behaviour, which the
        backward-compatibility requirement forbids, so it is pinned instead.
        """
        self.assertEqual(app.greet(7), "Hello 7")
        self.assertEqual(app.greet(None), "Hello None")

    def test_greet_returns_a_string(self):
        """The return type is part of what callers may rely on."""
        self.assertIsInstance(app.greet("Lakshya"), str)


class TestLegacyInvocation(unittest.TestCase):
    """The script's default mode, exercised as a real process.

    A subprocess is used rather than an in-process import because the property
    under test is precisely what a fresh interpreter does: whether the module
    prints at import time, and what the script writes when run with no flags.
    Neither question can be answered from inside an interpreter that has already
    imported the module.
    """

    def _run(self, *arguments):
        """Run the current interpreter with ``arguments`` in app.py's directory.

        Output is captured as RAW BYTES - ``text`` is deliberately left off.
        With ``text=True`` the child's streams are decoded through
        universal-newline translation, which rewrites a CRLF the child emitted
        into a bare LF before any assertion sees it; a length assertion made on
        the decoded string would then pass on a platform whose ``print``
        terminates lines with CRLF while the real stream carried fifteen bytes
        rather than fourteen.  The backward-compatibility contract is a byte
        sequence, so it is asserted on bytes.  Decoding happens only in
        :meth:`_diagnostic`, for a message a human reads after a failure.
        """
        completed = subprocess.run(
            [sys.executable, *arguments],
            cwd=APP_DIRECTORY,
            capture_output=True,
            timeout=SUBPROCESS_TIMEOUT_SECONDS,
            check=False,
        )
        return completed

    @staticmethod
    def _diagnostic(stream):
        """Decode a captured stream for a failure message only, never for an assertion."""
        return stream.decode("utf-8", "backslashreplace")

    def test_importing_app_writes_nothing_to_stdout(self):
        """Importing the module must have no observable side effect.

        The greeting lives behind the ``__main__`` guard.  If it ever escaped
        that guard, importing ``app`` - as this very test file does, and as any
        other consumer would - would print to standard output.
        """
        completed = self._run("-c", "import app")
        self.assertEqual(
            completed.returncode, 0, msg=self._diagnostic(completed.stderr)
        )
        self.assertEqual(completed.stdout, b"")
        self.assertNotIn(b"Hello", completed.stderr)

    def test_importing_app_exposes_the_public_surface(self):
        """A consumer that imports the module can reach every documented name."""
        expected = (
            "greet",
            "log_warning",
            "parse_properties",
            "read_properties",
            "config_value",
            "load_config",
            "normalize_path",
            "strip_authority",
            "config_route",
            "health_route",
            "health_timestamp",
            "build_payload",
            "render_payload",
            "is_single_line_text",
            "is_request_target",
            "validate_config",
            "sanitize_for_log",
            "probe_authority",
            "probe_rejection",
            "sole_media_type",
            "identity_rejection",
            "HealthRequestHandler",
            "HealthServer",
            "create_server",
            "serve",
            "probe",
        )
        for name in expected:
            with self.subTest(name=name):
                self.assertTrue(
                    hasattr(app, name), msg=f"app is missing the public name {name!r}"
                )

    def test_default_invocation_prints_the_preserved_greeting(self):
        """``python app.py`` with no flags: byte-exact stdout, exit status 0.

        This is the whole backward-compatibility guarantee in one assertion.  The
        trailing newline is included on purpose - it is one of the fourteen bytes
        the output has always consisted of.
        """
        completed = self._run("app.py")
        self.assertEqual(
            completed.returncode, 0, msg=self._diagnostic(completed.stderr)
        )
        self.assertEqual(completed.stdout, b"Hello Lakshya\n")
        self.assertEqual(len(completed.stdout), 14)

    def test_default_invocation_writes_nothing_to_stderr(self):
        """The default mode is silent apart from its one line of output."""
        completed = self._run("app.py")
        self.assertEqual(completed.stderr, b"")

    def test_unrecognised_flag_falls_back_to_the_default_mode(self):
        """An unknown argument must not change the legacy behaviour.

        The dispatcher recognises ``--serve`` and ``--probe`` and treats
        everything else as the default invocation, so a stray argument prints the
        greeting instead of starting a listener or failing.
        """
        completed = self._run("app.py", "--not-a-real-flag")
        self.assertEqual(
            completed.returncode, 0, msg=self._diagnostic(completed.stderr)
        )
        self.assertEqual(completed.stdout, b"Hello Lakshya\n")


class TestModeDispatch(unittest.TestCase):
    """``--serve`` and ``--probe``, driven through the real entry point.

    What is under test is the WIRING at the foot of ``app.py``: that the flag
    reaches the listener, that ``--probe`` reaches the self-check and that its
    verdict becomes the process exit status an orchestrator reads.  None of that
    can be established by calling :func:`app.serve` or :func:`app.probe` directly -
    those tests exist elsewhere in this file and would pass even if the dispatcher
    ignored both flags.  It also cannot be established in-process: the exit status
    IS the contract, and only a real child has one.

    Every child runs with a controlled environment - the health variables removed,
    then this test's own values put in - so a developer with ``HEALTH_PATH``
    exported cannot fail the suite, and on a port the kernel has just confirmed is
    free, so a server already running on the configured port cannot either.

    Standard output is asserted empty for both modes.  It carries this program's
    legacy output and is hashed by the backward-compatibility gate, so a mode that
    printed one line to it would break that gate while looking perfectly healthy.
    """

    def setUp(self):
        self._streams = {}

    def _environment(self, **overrides):
        """Return the ambient environment with health variables replaced."""
        environment = dict(os.environ)
        for name in HEALTH_ENV_NAMES:
            environment.pop(name, None)
        environment.update(overrides)
        return environment

    def _spawn(self, *arguments, environment=None):
        """Start ``python <arguments>`` in app.py's directory, streams captured."""
        child = subprocess.Popen(
            [sys.executable, *arguments],
            cwd=APP_DIRECTORY,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=environment,
        )
        self.addCleanup(self._stop, child)
        return child

    def _stop(self, child):
        """Terminate the child if it is still running; return ``(stdout, stderr)``.

        Idempotent, because it is both called by the test and registered as
        cleanup: a listener has no natural end, so the test that starts one is also
        what must end it, on the failure path as well as the passing one.
        """
        if child.pid not in self._streams:
            if child.poll() is None:
                child.terminate()
            try:
                self._streams[child.pid] = child.communicate(
                    timeout=SUBPROCESS_TIMEOUT_SECONDS
                )
            except subprocess.TimeoutExpired:
                child.kill()
                self._streams[child.pid] = child.communicate()
        return self._streams[child.pid]

    def _await_listener(self, child, port):
        """Wait until ``port`` accepts a connection, or the child has died."""

        def ready():
            if child.poll() is not None:
                return True
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
                probe.settimeout(REQUEST_TIMEOUT_SECONDS)
                return probe.connect_ex((LOOPBACK, port)) == 0

        await_settled(ready)
        if child.poll() is not None:
            _, stderr = self._stop(child)
            self.fail(
                "the server exited instead of serving: "
                + stderr.decode("utf-8", "backslashreplace")
            )

    def _run(self, *arguments, environment=None):
        """Run a child that is expected to exit on its own, and return it."""
        return subprocess.run(
            [sys.executable, *arguments],
            cwd=APP_DIRECTORY,
            capture_output=True,
            timeout=SUBPROCESS_TIMEOUT_SECONDS,
            check=False,
            env=environment,
        )

    def test_the_serve_flag_serves_the_endpoint_and_leaves_stdout_empty(self):
        port = unused_port()
        environment = self._environment(APP_HOST=LOOPBACK, PORT=str(port))
        child = self._spawn("app.py", "--serve", environment=environment)
        self._await_listener(child, port)
        with urllib.request.urlopen(
            f"http://{LOOPBACK}:{port}/health", timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            status = response.status
            document = json.loads(response.read().decode("utf-8"))
        stdout, stderr = self._stop(child)
        self.assertEqual(status, 200)
        self.assertEqual(list(document.keys()), EXPECTED_KEY_ORDER)
        self.assertEqual(document["status"], EXPECTED_STATUS)
        self.assertEqual(stdout, b"", msg="--serve must not write to standard output")
        self.assertNotIn(b"Hello", stdout)
        self.assertIn(b"Serving", stderr)
        self.assertIn(str(port).encode("ascii"), stderr)

    def test_the_probe_flag_exits_zero_against_the_running_listener(self):
        port = unused_port()
        environment = self._environment(APP_HOST=LOOPBACK, PORT=str(port))
        child = self._spawn("app.py", "--serve", environment=environment)
        self._await_listener(child, port)
        completed = self._run("app.py", "--probe", environment=environment)
        self.assertEqual(
            completed.returncode,
            0,
            msg=completed.stderr.decode("utf-8", "backslashreplace"),
        )
        self.assertEqual(completed.stdout, b"")
        self.assertEqual(completed.stderr, b"", msg="a healthy probe is silent")

    def test_the_probe_flag_exits_one_when_nothing_is_listening(self):
        """Fail closed: a probe's caller acts on this status and only this."""
        environment = self._environment(APP_HOST=LOOPBACK, PORT=str(unused_port()))
        completed = self._run("app.py", "--probe", environment=environment)
        self.assertEqual(completed.returncode, 1)
        self.assertEqual(completed.stdout, b"")
        self.assertEqual(completed.stderr.count(b"\n"), 1, msg=repr(completed.stderr))

    def test_the_probe_flag_follows_the_configured_health_path(self):
        """Both modes read the same configuration, or the probe grades a stranger."""
        port = unused_port()
        served = self._environment(
            APP_HOST=LOOPBACK, PORT=str(port), HEALTH_PATH="/healthz"
        )
        child = self._spawn("app.py", "--serve", environment=served)
        self._await_listener(child, port)
        agreeing = self._run("app.py", "--probe", environment=served)
        disagreeing = self._run(
            "app.py",
            "--probe",
            environment=self._environment(
                APP_HOST=LOOPBACK, PORT=str(port), HEALTH_PATH="/health"
            ),
        )
        self.assertEqual(
            agreeing.returncode,
            0,
            msg=agreeing.stderr.decode("utf-8", "backslashreplace"),
        )
        self.assertEqual(disagreeing.returncode, 1)

    def test_the_serve_flag_fails_closed_on_an_unusable_port(self):
        """An orchestrator must never see a success status from a dead listener."""
        environment = self._environment(APP_HOST=LOOPBACK, PORT="not-a-port")
        completed = self._run("app.py", "--serve", environment=environment)
        self.assertEqual(completed.returncode, 1)
        self.assertEqual(completed.stdout, b"")
        self.assertIn(b"cannot start the health server", completed.stderr)
        self.assertEqual(completed.stderr.count(b"\n"), 1, msg=repr(completed.stderr))

    def test_the_serve_flag_refuses_an_unpublishable_configuration(self):
        """Validation happens before the bind, so nothing is ever served from it."""
        environment = self._environment(APP_HOST=LOOPBACK, APP_VERSION="one.two")
        completed = self._run("app.py", "--serve", environment=environment)
        self.assertEqual(completed.returncode, 1)
        self.assertEqual(completed.stdout, b"")
        self.assertIn(b"app.version", completed.stderr)


# Configuration.  Every test here injects its own environment mapping and its own
# properties path.  os.environ is never written to: these tests must not depend on
# the environment they run in, nor leave it altered for those that follow.


#: The SHARED properties grammar fixtures.  Every entry is ``(label, file text,
#: expected mapping)``, and the identical table - same labels, same text, same
#: expectations - appears in ``index.test.js`` and ``UserTest.java``.  Each
#: expectation was produced by running ``java.util.Properties.load`` on the same
#: bytes, so this table is a transcription of the reference implementation rather
#: than a description of this one: a suite that asserted what its own parser
#: happens to do could not detect the divergence it exists to prevent.
SHARED_PROPERTIES_FIXTURES = (
    ("a plain key and value", "a=1\n", {"a": "1"}),
    ("a colon separator", "a:1\n", {"a": "1"}),
    ("a space separator", "a 1\n", {"a": "1"}),
    ("a tab separator", "a\t1\n", {"a": "1"}),
    ("a form-feed separator", "a\f1\n", {"a": "1"}),
    ("whitespace around the separator", "a = 1\n", {"a": "1"}),
    ("trailing value whitespace is preserved", "a=1   \n", {"a": "1   "}),
    ("a whitespace-only value is empty", "a=   \n", {"a": ""}),
    ("a key with no separator has an empty value", "abc\n", {"abc": ""}),
    ("an empty key is still a key", "=v\n", {"": "v"}),
    ("only the first separator separates", "a = b=c \n", {"a": "b=c "}),
    ("an escaped space belongs to the key", "a\\ b=x\n", {"a b": "x"}),
    ("an escaped equals belongs to the key", "a\\=b=x\n", {"a=b": "x"}),
    ("an escaped colon belongs to the key", "a\\:b=x\n", {"a:b": "x"}),
    ("a tab escape in a value", "a=x\\ty\n", {"a": "x\ty"}),
    ("a newline escape in a value", "a=x\\nz\n", {"a": "x\nz"}),
    ("a unicode escape in a value", "a=\\u0041\n", {"a": "A"}),
    ("a capital U is not a unicode escape", "a=\\U0041\n", {"a": "U0041"}),
    ("an unknown escape is the character itself", "a=\\z\n", {"a": "z"}),
    ("an escaped backslash is one backslash", "a=x\\\\y\n", {"a": "x\\y"}),
    ("an odd trailing backslash continues the line", "a=one\\\n   two\n", {"a": "onetwo"}),
    ("an even trailing backslash ends the line", "a=v\\\\\nb=2\n", {"a": "v\\", "b": "2"}),
    ("a hash comment is skipped", "#c\na=1\n", {"a": "1"}),
    ("a bang comment is skipped", "!c\na=1\n", {"a": "1"}),
    ("an indented comment is skipped", "   # c\na=1\n", {"a": "1"}),
    ("a continuation line is data, not a comment", "a=x\\\n#y\n", {"a": "x#y"}),
    ("CR, LF and CRLF all end a line", "a=1\r\nb=2\rc=3\n", {"a": "1", "b": "2", "c": "3"}),
    ("the last of a repeated key wins", "a=1\na=2\n", {"a": "2"}),
    ("quote characters are literal", 'a="q"\n', {"a": '"q"'}),
    ("a trailing backslash at end of input is dropped", "a=v\\", {"a": "v"}),
    ("a byte-order mark is not stripped", "\ufeffa=1\n", {"\ufeffa": "1"}),
)

#: The shared MALFORMED fixtures: the one condition under which
#: ``java.util.Properties.load`` refuses a document outright rather than reading
#: part of it as a literal.
SHARED_MALFORMED_PROPERTIES = (
    ("a short unicode escape in a value", "a=\\u12\n"),
    ("a non-hexadecimal unicode escape", "a=\\uZZZZ\n"),
    ("a malformed unicode escape in a key", "\\u12=v\n"),
)


class TestReadProperties(unittest.TestCase):
    """Parsing of the shared ``key=value`` configuration file."""

    def test_reads_keys_and_values(self):
        path = write_properties(self, "app.name=configured\napp.version=4.5.6\n")
        self.assertEqual(
            app.read_properties(path),
            {"app.name": "configured", "app.version": "4.5.6"},
        )

    def test_the_shared_grammar_fixtures_parse_as_java_parses_them(self):
        """The cross-language contract: one file must mean one thing in three languages.

        Every expectation in :data:`SHARED_PROPERTIES_FIXTURES` came out of
        ``java.util.Properties.load``, which is how ``User.java`` reads the same
        file.  The equivalent table in ``index.test.js`` and ``UserTest.java``
        carries the same labels and the same expectations, so a change that made
        one parser drift would fail in whichever suite owns it.
        """
        for label, text, expected in SHARED_PROPERTIES_FIXTURES:
            with self.subTest(label=label):
                self.assertEqual(app.parse_properties(text), expected)

    def test_a_malformed_unicode_escape_makes_the_document_malformed(self):
        """A bad ``\\uXXXX`` is a refusal, not a literal - as in Java."""
        for label, text in SHARED_MALFORMED_PROPERTIES:
            with self.subTest(label=label):
                with self.assertRaises(app.PropertiesFormatError):
                    app.parse_properties(text)

    def test_a_malformed_file_warns_once_and_uses_the_defaults(self):
        """The malformed-file half of the shared failure policy."""
        path = write_properties(self, "app.name=x\\u12\n")
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            properties = app.read_properties(path)
        self.assertEqual(properties, {})
        self.assertEqual(
            stderr.getvalue(),
            f"[app.py] {app.CONFIG_MALFORMED_WARNING}\n",
        )

    def test_a_file_that_cannot_be_read_warns_once_and_uses_the_defaults(self):
        """The unreadable-file half of the shared failure policy.

        A directory standing where the file should be is the portable way to make
        a read fail: a permission bit does not stop the root user that a container
        build commonly runs as, so it would make the test pass for the wrong
        reason on one host and fail on another.
        """
        directory = tempfile.mkdtemp(suffix=".properties")
        self.addCleanup(os.rmdir, directory)
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            properties = app.read_properties(directory)
        self.assertEqual(properties, {})
        self.assertEqual(
            stderr.getvalue(),
            f"[app.py] {app.CONFIG_UNREADABLE_WARNING}\n",
        )

    def test_bytes_that_are_not_utf8_are_a_read_failure(self):
        """Strict decoding, matching Java's UTF-8 reader and Node's fatal decoder.

        A replacement character here would put a U+FFFD into the published
        ``name`` field of exactly one of the three implementations.
        """
        handle = tempfile.NamedTemporaryFile(suffix=".properties", delete=False)
        try:
            handle.write(b"app.name=caf\xe9\n")
        finally:
            handle.close()
        self.addCleanup(os.unlink, handle.name)
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            properties = app.read_properties(handle.name)
        self.assertEqual(properties, {})
        self.assertEqual(
            stderr.getvalue(),
            f"[app.py] {app.CONFIG_UNREADABLE_WARNING}\n",
        )

    def test_ignores_comments_and_blank_lines(self):
        """Comment markers are ``#`` and ``!``, matching java.util.Properties.

        A line carrying no separator is NOT ignored: ``Properties.load`` reads it
        as a key with an empty value, so all three implementations do.  Discarding
        such a line instead would make a typo like a missing ``=`` produce no key
        here and an empty-valued key in Java.
        """
        text = (
            "# a hash comment\n"
            "! a bang comment\n"
            "\n"
            "   \n"
            "app.name=kept\n"
        )
        path = write_properties(self, text)
        self.assertEqual(app.read_properties(path), {"app.name": "kept"})
        with_separatorless_line = write_properties(
            self, "a line with no equals sign\napp.name=kept\n"
        )
        self.assertEqual(
            app.read_properties(with_separatorless_line),
            {"a": "line with no equals sign", "app.name": "kept"},
        )

    def test_splits_on_the_first_separator_only(self):
        """A value may itself contain ``=``; its trailing whitespace is preserved."""
        path = write_properties(self, "app.name = a=b=c \n")
        self.assertEqual(app.read_properties(path), {"app.name": "a=b=c "})

    def test_keeps_quote_characters_literally(self):
        """Values are never unquoted - java.util.Properties does not either.

        If this stripped quotes, the three implementations would disagree about
        what the file says, and a quoted name would reach the payload in one
        language but not the others.
        """
        path = write_properties(self, 'app.name="quoted"\n')
        self.assertEqual(app.read_properties(path), {"app.name": '"quoted"'})

    def test_a_missing_file_yields_an_empty_mapping_silently(self):
        """Absence is expected, not exceptional: defaults cover every key.

        Silence matters as much as the empty result.  A container image that
        copied only the application source must still serve the endpoint without
        writing a diagnostic on every single request.
        """
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            properties = app.read_properties(absent_properties_path(self))
        self.assertEqual(properties, {})
        self.assertEqual(stderr.getvalue(), "")

    def test_reads_the_committed_configuration_file(self):
        """app.config.properties, as committed, carries the frozen identity.

        This is the cross-file half of the contract: the shared properties file
        is the single source of truth for all three language implementations, so
        a change to it here would silently change the payload everywhere.  It is
        also what justifies the 108-byte reference length asserted below.
        """
        properties = app.read_properties(app.CONFIG_PATH)
        for key, expected in EXPECTED_DEFAULTS.items():
            with self.subTest(key=key):
                self.assertEqual(properties.get(key), expected)


class TestConfigPrecedence(unittest.TestCase):
    """Environment variable, then properties file, then built-in default."""

    def test_environment_beats_the_properties_file(self):
        path = write_properties(self, "app.name=fromfile\napp.version=9.9.9\n")
        config = app.load_config(path=path, env={"APP_NAME": "zzz"})
        self.assertEqual(config["app.name"], "zzz")
        self.assertEqual(
            config["app.version"],
            "9.9.9",
            msg="a key with no override must still come from the file",
        )

    def test_the_properties_file_beats_the_builtin_default(self):
        path = write_properties(self, "app.version=2.3.4\n")
        config = app.load_config(path=path, env={})
        self.assertEqual(config["app.version"], "2.3.4")
        self.assertEqual(config["app.name"], EXPECTED_DEFAULTS["app.name"])

    def test_builtin_defaults_apply_when_nothing_else_supplies_a_value(self):
        """No environment and no file: every key falls back, none is missing."""
        self.assertEqual(defaults_only_config(self), EXPECTED_DEFAULTS)

    def test_the_universal_port_beats_the_per_language_port(self):
        """PORT outranks PYTHON_PORT - the twelve-factor single-app convention.

        A platform that injects PORT knows nothing about this repository's
        per-language keys, so it has to win.
        """
        config = app.load_config(
            path=absent_properties_path(self),
            env={"PORT": "8123", "PYTHON_PORT": "8100"},
        )
        self.assertEqual(config["python.port"], "8123")

    def test_the_per_language_port_applies_without_the_universal_port(self):
        config = app.load_config(
            path=absent_properties_path(self), env={"PYTHON_PORT": "8100"}
        )
        self.assertEqual(config["python.port"], "8100")

    def test_the_universal_port_beats_the_properties_file_too(self):
        path = write_properties(self, "python.port=8500\n")
        config = app.load_config(path=path, env={"PORT": "8123"})
        self.assertEqual(config["python.port"], "8123")

    def test_an_empty_environment_value_is_treated_as_absent(self):
        """``APP_NAME=""`` must not produce an empty ``name`` field.

        The response contract requires a non-empty name, so an exported-but-empty
        variable falls through to the next source rather than winning.
        """
        path = write_properties(self, "app.name=fromfile\n")
        config = app.load_config(path=path, env={"APP_NAME": ""})
        self.assertEqual(config["app.name"], "fromfile")

    def test_an_empty_properties_value_is_treated_as_absent(self):
        path = write_properties(self, "app.version=\n")
        config = app.load_config(path=path, env={})
        self.assertEqual(config["app.version"], EXPECTED_DEFAULTS["app.version"])

    def test_a_missing_properties_file_is_tolerated_without_raising(self):
        """The endpoint still resolves a full configuration with no file at all."""
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            config = app.load_config(path=absent_properties_path(self), env={})
        self.assertEqual(config, EXPECTED_DEFAULTS)
        self.assertEqual(stderr.getvalue(), "")

    def test_load_config_resolves_every_key(self):
        """No key may be absent from the resolved mapping, whatever the inputs."""
        config = app.load_config(path=absent_properties_path(self), env={})
        self.assertEqual(sorted(config), sorted(EXPECTED_DEFAULTS))

    def test_config_value_resolves_one_key_with_injected_sources(self):
        """The single-key resolver honours the same precedence as load_config."""
        properties = {"app.name": "fromfile"}
        self.assertEqual(
            app.config_value(
                "app.name",
                "APP_NAME",
                "fallback",
                props=properties,
                env={"APP_NAME": "fromenv"},
            ),
            "fromenv",
        )
        self.assertEqual(
            app.config_value(
                "app.name", "APP_NAME", "fallback", props=properties, env={}
            ),
            "fromfile",
        )
        self.assertEqual(
            app.config_value("app.name", "APP_NAME", "fallback", props={}, env={}),
            "fallback",
        )

    def test_neutralizing_the_environment_removes_and_restores_it(self):
        """The harness invariant that lets the live-server tests be trusted.

        The endpoint's variables must disappear for the duration of a live-server
        test, every unrelated variable must survive it, and every removed
        variable must come back afterwards.  This is asserted rather than assumed
        because losing it silently makes the routing tests depend on whatever
        happens to be exported in the shell that runs them.
        """
        ambient = {"APP_NAME": "ambient", "HEALTH_PATH": "/ambient", "KEEP_ME": "kept"}
        with mock.patch.dict(os.environ, ambient):
            environment = neutralize_health_environment()
            try:
                for name in HEALTH_ENV_NAMES:
                    with self.subTest(name=name):
                        self.assertNotIn(name, os.environ)
                self.assertEqual(os.environ.get("KEEP_ME"), "kept")
                self.assertEqual(app.health_route(), EXPECTED_DEFAULTS["health.path"])
            finally:
                environment.stop()
            self.assertEqual(os.environ.get("APP_NAME"), "ambient")
            self.assertEqual(os.environ.get("HEALTH_PATH"), "/ambient")
            self.assertEqual(os.environ.get("KEEP_ME"), "kept")

    def test_the_real_environment_is_never_mutated_by_these_tests(self):
        """Guard rail: the suite injects mappings instead of exporting variables.

        Written as an assertion rather than a comment so that a future test which
        starts setting variables globally is caught by the suite itself.
        """
        before = dict(os.environ)
        app.load_config(
            path=absent_properties_path(self), env={"APP_NAME": "ignored-by-os-environ"}
        )
        self.assertEqual(dict(os.environ), before)


class TestRouteResolution(unittest.TestCase):
    """Normalising a request target, and resolving the configured route."""

    def test_normalize_path_strips_the_query_and_one_trailing_slash(self):
        cases = {
            "/health": "/health",
            "/health/": "/health",
            "/health?probe=1": "/health",
            "/health/?probe=1": "/health",
            "/health?": "/health",
            "/nope": "/nope",
            "/": "/",
        }
        for target, expected in cases.items():
            with self.subTest(target=target):
                self.assertEqual(app.normalize_path(target), expected)

    def test_normalize_path_removes_only_one_trailing_slash(self):
        """One forgiving slash is a convenience; two describe a different path."""
        self.assertEqual(app.normalize_path("/health//"), "/health/")

    def test_normalize_path_strips_an_absolute_form_authority(self):
        """A request line may name the whole URL, and RFC 9112 permits it.

        ``GET http://host:8002/health HTTP/1.1`` is the absolute form, and a
        proxy-aware client emits it.  The Java implementation reduced it from the
        beginning; these two did not, so before this reduction existed the same
        request reached the route on one implementation and returned 404 on the other
        two.  It is uniform now: measured on the wire, all three answer 200 for the
        configured path and 404 for any other, in either scheme and whatever
        authority the line names.
        """
        cases = {
            "http://host:8002/health": "/health",
            "http://host:8002/health/": "/health",
            "http://host/nope": "/nope",
            "https://host:443/health?probe=1": "/health",
            "http://host": "/",
        }
        for target, expected in cases.items():
            with self.subTest(target=target):
                self.assertEqual(app.normalize_path(target), expected)

    def test_normalize_path_strips_a_fragment(self):
        """A fragment is a client-side construct and never selects a route."""
        self.assertEqual(app.normalize_path("/health#section"), "/health")
        self.assertEqual(app.normalize_path("/health?probe=1#section"), "/health")

    def test_strip_authority_validates_the_scheme_before_removing_anything(self):
        """``://`` inside a QUERY is data, not an authority.

        The scheme is checked first, so a target whose query happens to carry a
        URL keeps every byte of it.  Without that check ``/health?next=http://x/``
        would be truncated to ``/`` and the route would be lost.
        """
        for target in (
            "/health?next=http://elsewhere/",
            "/health",
            "/",
            "//health",
            "/health%2f",
            "/9nothing://x",
            "/-bad://x",
        ):
            with self.subTest(target=target):
                self.assertEqual(app.strip_authority(target), target)

    def test_strip_authority_leaves_a_target_it_does_not_recognise(self):
        """Only a scheme followed by ``://`` is an authority worth removing."""
        self.assertEqual(app.strip_authority("http:/health"), "http:/health")
        self.assertEqual(app.strip_authority(""), "")

    def test_health_route_supplies_a_missing_leading_slash(self):
        cases = {
            "/health": "/health",
            "health": "/health",
            "/health/": "/health",
            "/status": "/status",
            "readyz": "/readyz",
        }
        for configured, expected in cases.items():
            with self.subTest(configured=configured):
                self.assertEqual(
                    app.health_route({"health.path": configured}), expected
                )

    def test_health_route_falls_back_to_the_default_path(self):
        """An empty or absent ``health.path`` still yields a usable route."""
        self.assertEqual(app.health_route({}), EXPECTED_DEFAULTS["health.path"])
        self.assertEqual(
            app.health_route({"health.path": ""}), EXPECTED_DEFAULTS["health.path"]
        )

    def test_config_route_reduces_a_configured_path_the_shared_way(self):
        """The same table appears in ``index.test.js`` and ``UserTest.java``.

        ``config_route`` is the single function both the validator and the router
        go through, so this table is simultaneously the routing contract and the
        validation contract - they cannot drift apart because they are the same
        call.
        """
        cases = {
            "/health": "/health",
            "health": "/health",
            "healthz": "/healthz",
            "/health/": "/health",
            "/health?probe=1": "/health",
            "/health#part": "/health",
            # The leading slash is supplied BEFORE normalisation, so a configured
            # value that looks like an absolute URL is no longer in absolute form
            # by the time the authority would be stripped.  All three
            # implementations do this in the same order, which is what matters:
            # the value is nonsense either way, and it stays nonsense identically.
            "http://host:8000/health": "/http://host:8000/health",
            "/": "/",
            "//": "/",
            "//health": "//health",
            "/health//": "/health/",
        }
        for configured, expected in cases.items():
            with self.subTest(configured=configured):
                self.assertEqual(app.config_route(configured), expected)


# The health document.  The payload is built from a defaults-only configuration so
# that these assertions are identical wherever they run, whatever the environment
# or the properties file happens to hold.


class TestPayloadContract(unittest.TestCase):
    """Field names, field order, field formats and the compact serialisation."""

    def setUp(self):
        self.config = defaults_only_config(self)
        self.payload = app.build_payload(self.config)
        self.rendered = app.render_payload(self.payload)

    def test_the_key_order_is_frozen(self):
        """Insertion order is the wire order, so it is asserted as a sequence.

        A set comparison would accept a reordered document; monitoring tools and
        the byte-length gate would not.
        """
        self.assertEqual(list(self.payload.keys()), EXPECTED_KEY_ORDER)

    def test_the_document_carries_no_additional_fields(self):
        """Exactly four fields.  Every extra field is disclosure."""
        self.assertEqual(len(self.payload), len(EXPECTED_KEY_ORDER))

    def test_the_status_is_the_literal_up(self):
        """The one mandatory field, and the value the requirement names."""
        self.assertEqual(self.payload["status"], EXPECTED_STATUS)

    def test_the_name_is_a_non_empty_string_taken_from_configuration(self):
        self.assertIsInstance(self.payload["name"], str)
        self.assertNotEqual(self.payload["name"], "")
        self.assertEqual(self.payload["name"], self.config["app.name"])
        self.assertEqual(self.payload["name"], EXPECTED_DEFAULTS["app.name"])

    def test_the_version_is_three_part_dotted_numeric(self):
        version = self.payload["version"]
        self.assertRegex(version, VERSION_PATTERN)
        self.assertEqual(version, self.config["app.version"])
        self.assertEqual(version, EXPECTED_DEFAULTS["app.version"])

    def test_the_timestamp_matches_the_frozen_format(self):
        """Format only.  Never a value comparison - see the module docstring."""
        timestamp = self.payload["timestamp"]
        self.assertIsInstance(timestamp, str)
        self.assertRegex(timestamp, TIMESTAMP_PATTERN)
        self.assertEqual(len(timestamp), TIMESTAMP_LENGTH)

    def test_the_timestamp_parses_as_a_whole_second_instant(self):
        """The shape is a real instant, not merely digits in the right places.

        ``strptime`` rejects an impossible date such as month 13, which the
        regular expression alone would accept.  Only the parsed field's own
        sub-second component is inspected afterwards, so nothing here depends on
        what the clock actually reads.
        """
        parsed = datetime.datetime.strptime(
            self.payload["timestamp"], TIMESTAMP_STRPTIME_FORMAT
        )
        self.assertEqual(parsed.microsecond, 0)

    def test_every_health_timestamp_matches_the_frozen_format(self):
        """The generator itself, exercised directly and repeatedly."""
        for attempt in range(5):
            with self.subTest(attempt=attempt):
                self.assertRegex(app.health_timestamp(), TIMESTAMP_PATTERN)

    def test_the_rendered_body_contains_no_whitespace(self):
        """Compact separators are part of the cross-language byte parity."""
        for character in (" ", "\t", "\n", "\r"):
            with self.subTest(character=character):
                self.assertNotIn(character, self.rendered)

    def test_the_rendered_body_round_trips_to_the_same_document(self):
        """Serialisation loses nothing, including the field order."""
        decoded = json.loads(self.rendered)
        self.assertEqual(decoded, self.payload)
        self.assertEqual(list(decoded.keys()), EXPECTED_KEY_ORDER)

    def test_the_reference_body_is_exactly_108_bytes(self):
        """The byte-parity gate shared with the JavaScript and Java bodies.

        Dropping the explicit compact separators from the renderer produces 115
        bytes for these same four fields, so this single number catches that
        divergence at unit level rather than at the cross-language seam.
        """
        self.assertEqual(
            len(self.rendered.encode("utf-8")),
            REFERENCE_BODY_LENGTH,
            msg=(
                "the compact JSON body must be "
                f"{REFERENCE_BODY_LENGTH} bytes; 115 means json.dumps was called "
                "without separators=(',', ':')"
            ),
        )

    def test_the_payload_reflects_overridden_configuration(self):
        """Name and version are reported from configuration, not hard-coded."""
        payload = app.build_payload(
            {"app.name": "other-application", "app.version": "9.8.7"}
        )
        self.assertEqual(payload["name"], "other-application")
        self.assertEqual(payload["version"], "9.8.7")
        self.assertEqual(payload["status"], EXPECTED_STATUS)
        self.assertEqual(list(payload.keys()), EXPECTED_KEY_ORDER)

    def test_the_payload_falls_back_to_defaults_for_missing_keys(self):
        """An incomplete configuration still yields a contract-valid document."""
        payload = app.build_payload({})
        self.assertEqual(payload["name"], EXPECTED_DEFAULTS["app.name"])
        self.assertEqual(payload["version"], EXPECTED_DEFAULTS["app.version"])
        self.assertRegex(payload["timestamp"], TIMESTAMP_PATTERN)
        self.assertEqual(payload["status"], EXPECTED_STATUS)

    def test_the_renderer_never_sorts_keys(self):
        """Sorting would reorder the wire document to name, status, timestamp...

        Asserted against a deliberately unsorted mapping so that the check fails
        if ``sort_keys=True`` is ever introduced.
        """
        rendered = app.render_payload({"status": "UP", "name": "a"})
        self.assertEqual(rendered, '{"status":"UP","name":"a"}')


# The live endpoint.  These tests drive a real HealthServer over a real socket,
# which is the only way to assert the parts of the contract that are properties of
# the response rather than of the payload: the status codes, the header set, and
# the two headers that must NOT be there.


class HealthServerTestCase(unittest.TestCase):
    """Base class: a HealthServer bound to an ephemeral loopback port.

    Port 0 lets the kernel choose the port, which is then read back from the
    bound socket.  Nothing here hard-codes 8000, so the suite passes while a
    developer has ``python app.py --serve`` running on the configured port.

    The server resolves its configuration once, in ``setUpClass`` below, from the
    process environment and the committed properties file, so it cannot be given
    an injected mapping the way the loader tests do.  The health-related variables
    are therefore removed from ``os.environ`` before it is built, for the lifetime
    of the class - through ``patch.dict``, which snapshots the whole mapping and
    restores it even if a test fails - leaving the committed file as the effective
    configuration.  No other variable is touched, and nothing is added.
    """

    #: Set once the serve_forever loop is running.  shutdown() waits on an event
    #: that only serve_forever sets, so calling it before the loop starts would
    #: block forever; this flag makes teardown safe on every path.
    _serving = False
    _environment = None
    server = None
    thread = None

    @classmethod
    def setUpClass(cls):
        cls._environment = neutralize_health_environment()
        try:
            cls.config = app.load_config()
            cls.route = app.health_route(cls.config)
            cls.server = app.create_server(host=LOOPBACK, port=0, config=cls.config)
            cls.port = cls.server.server_address[1]
            cls.base_url = f"http://{LOOPBACK}:{cls.port}"
            cls.thread = threading.Thread(
                target=cls.server.serve_forever,
                name="health-endpoint-test-server",
                daemon=True,
            )
            cls.thread.start()
            cls._serving = True
        except BaseException:
            # A failure part-way through setUpClass must not leave a bound
            # socket or a mutated environment behind for the next class.
            cls._shut_down()
            cls._environment.stop()
            raise

    @classmethod
    def tearDownClass(cls):
        try:
            cls._shut_down()
        finally:
            cls._environment.stop()

    @classmethod
    def _shut_down(cls):
        """Stop the loop, close the listening socket, join the thread."""
        if cls._serving:
            cls.server.shutdown()
            cls._serving = False
        if cls.server is not None:
            cls.server.server_close()
            cls.server = None
        if cls.thread is not None:
            cls.thread.join(timeout=THREAD_JOIN_TIMEOUT_SECONDS)
            cls.thread = None

    def request(self, path, method="GET", data=None):
        """Issue one request; return ``(status, headers, body)``.

        An error status is returned rather than raised.  urllib signals 4xx by
        raising ``HTTPError``, which is itself a response object holding an open
        socket, so it is unwrapped and closed here.  That keeps the 200, 404 and
        405 assertions symmetrical and leaks no descriptors.
        """
        request = urllib.request.Request(self.base_url + path, data=data, method=method)
        try:
            with urllib.request.urlopen(
                request, timeout=REQUEST_TIMEOUT_SECONDS
            ) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            try:
                return error.code, error.headers, error.read()
            finally:
                error.close()


class TestRouting(HealthServerTestCase):
    """Status codes, bodies and headers of every response the endpoint gives."""

    def test_get_on_the_health_route_returns_the_document(self):
        status, _, body = self.request(self.route)
        self.assertEqual(status, 200)
        self.assertEqual(len(body), REFERENCE_BODY_LENGTH)
        self.assertNotIn(b" ", body)
        document = json.loads(body.decode("utf-8"))
        self.assertEqual(list(document.keys()), EXPECTED_KEY_ORDER)
        self.assertEqual(document["status"], EXPECTED_STATUS)
        self.assertEqual(document["name"], EXPECTED_DEFAULTS["app.name"])
        self.assertRegex(document["version"], VERSION_PATTERN)
        self.assertRegex(document["timestamp"], TIMESTAMP_PATTERN)

    def test_a_single_trailing_slash_still_reaches_the_health_route(self):
        status, _, body = self.request(self.route + "/")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body.decode("utf-8"))["status"], EXPECTED_STATUS)

    def test_a_query_string_is_stripped_before_matching(self):
        status, _, body = self.request(self.route + "?probe=1&verbose=true")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body.decode("utf-8"))["status"], EXPECTED_STATUS)

    def test_the_success_headers_are_exactly_the_frozen_set(self):
        """Three headers, and only three.  Anything more is disclosure."""
        status, headers, body = self.request(self.route)
        self.assertEqual(status, 200)
        self.assertEqual(
            header_names(headers),
            {"content-type", "cache-control", "content-length"},
            msg="the response header set is frozen; nothing may be added to it",
        )
        self.assertEqual(headers.get("Content-Type"), EXPECTED_CONTENT_TYPE)
        self.assertEqual(int(headers.get("Content-Length")), len(body))

    def test_the_success_response_refuses_caching(self):
        """A cached "healthy" is worse than no answer at all."""
        _, headers, _ = self.request(self.route)
        cache_control = headers.get("cache-control", "")
        for directive in ("no-cache", "no-store", "must-revalidate"):
            with self.subTest(directive=directive):
                self.assertIn(directive, cache_control)

    def test_header_lookups_are_case_insensitive(self):
        """RFC 9110: field names carry no case.  All three suites match this way."""
        _, headers, _ = self.request(self.route)
        spellings = ("Content-Type", "content-type", "CONTENT-TYPE", "cOnTeNt-TyPe")
        for spelling in spellings:
            with self.subTest(spelling=spelling):
                self.assertEqual(headers.get(spelling), EXPECTED_CONTENT_TYPE)

    def test_no_server_or_date_header_is_disclosed(self):
        """Least disclosure, asserted on the success path and an error path.

        ``send_response`` - the obvious call to reach for - appends
        ``Server: BaseHTTP/x Python/y`` and a ``Date`` header.  The
        implementation uses ``send_response_only`` precisely to avoid that, and a
        refactor back to the convenient call is exactly what this test exists to
        catch.
        """
        for path in (self.route, "/nope"):
            with self.subTest(path=path):
                _, headers, _ = self.request(path)
                names = header_names(headers)
                self.assertNotIn("server", names)
                self.assertNotIn("date", names)
                self.assertNotIn("Python", str(headers))

    def test_an_unknown_path_returns_not_found(self):
        for path in ("/nope", "/", "/healthz", self.route + "/extra"):
            with self.subTest(path=path):
                status, headers, body = self.request(path)
                self.assertEqual(status, 404)
                self.assertEqual(body, EXPECTED_NOT_FOUND_BODY)
                self.assertEqual(headers.get("content-type"), EXPECTED_CONTENT_TYPE)

    def test_a_double_trailing_slash_is_not_the_health_route(self):
        """One forgiving slash is deliberate; two is a different path."""
        status, _, body = self.request(self.route + "//")
        self.assertEqual(status, 404)
        self.assertEqual(body, EXPECTED_NOT_FOUND_BODY)

    def test_the_error_body_discloses_nothing_about_the_request(self):
        """The 404 body is constant: no echoed path, no traceback."""
        _, _, body = self.request("/deployment-internal-path")
        self.assertEqual(body, EXPECTED_NOT_FOUND_BODY)
        self.assertNotIn(b"deployment-internal-path", body)

    def test_post_is_rejected_with_method_not_allowed(self):
        status, headers, body = self.request(self.route, method="POST", data=b"")
        self.assertEqual(status, 405)
        self.assertEqual(body, EXPECTED_METHOD_NOT_ALLOWED_BODY)
        self.assertEqual(headers.get("allow"), EXPECTED_ALLOW_HEADER)

    def test_every_other_method_is_rejected_the_same_way(self):
        """The method policy is stated once, so it must hold for every verb."""
        cases = (("PUT", b""), ("DELETE", None), ("PATCH", b""), ("OPTIONS", None))
        for method, data in cases:
            with self.subTest(method=method):
                status, headers, body = self.request(
                    self.route, method=method, data=data
                )
                self.assertEqual(status, 405)
                self.assertEqual(body, EXPECTED_METHOD_NOT_ALLOWED_BODY)
                self.assertEqual(headers.get("Allow"), EXPECTED_ALLOW_HEADER)

    def test_head_is_refused_but_still_advertises_the_length(self):
        """HEAD is the one refusal that carries a length yet no body.

        The sibling method test above cannot cover HEAD, because every other
        verb receives the 30 error bytes and HEAD must receive none.  RFC 9110
        requires a HEAD response to carry the header fields the corresponding
        message-body response would have, so suppressing the length would break
        parity with the Node and Java suites, while writing the 30 bytes anyway
        would corrupt the next request on a persistent connection.  Both
        mistakes are caught here.
        """
        status, headers, body = self.request(self.route, method="HEAD")
        self.assertEqual(status, 405)
        self.assertEqual(headers.get("Allow"), EXPECTED_ALLOW_HEADER)
        self.assertEqual(
            header_names(headers),
            {"content-type", "cache-control", "content-length", "allow"},
            msg="the 405 header set is frozen; HEAD must not add to or drop from it",
        )
        self.assertEqual(headers.get("Content-Type"), EXPECTED_CONTENT_TYPE)
        self.assertEqual(
            int(headers.get("Content-Length")),
            len(EXPECTED_METHOD_NOT_ALLOWED_BODY),
            msg="the advertised length is the one a GET-shaped 405 would carry",
        )
        self.assertEqual(body, b"", msg="a HEAD response transmits zero body bytes")
        for directive in ("no-cache", "no-store", "must-revalidate"):
            with self.subTest(directive=directive):
                self.assertIn(directive, headers.get("Cache-Control", ""))
        names = header_names(headers)
        self.assertNotIn("server", names)
        self.assertNotIn("date", names)

    def test_head_on_an_unknown_path_is_refused_before_the_route_is_consulted(self):
        """Method classification precedes route matching, so HEAD /nope is 405.

        The order matters: were the route consulted first, an unknown path would
        answer 404 for HEAD and 405 for the health path, making the method
        policy depend on the target.  It does not, and this pins that.
        """
        status, headers, body = self.request("/nope", method="HEAD")
        self.assertEqual(status, 405)
        self.assertEqual(headers.get("allow"), EXPECTED_ALLOW_HEADER)
        self.assertEqual(body, b"")

    def test_error_responses_also_refuse_caching(self):
        cases = (("/nope", "GET", None, 404), (self.route, "POST", b"", 405))
        for path, method, data, expected_status in cases:
            with self.subTest(path=path, method=method):
                status, headers, _ = self.request(path, method=method, data=data)
                self.assertEqual(status, expected_status)
                self.assertIn("no-store", headers.get("Cache-Control", ""))

    def test_the_not_found_headers_are_exactly_the_frozen_set(self):
        """The error paths are held to the same three fields as the success path.

        Asserted by EQUALITY rather than by containment, on every status this
        endpoint serves: a containment check passes while a refactor quietly adds a
        fourth field, which is precisely the regression the frozen set exists to
        prevent.  Every cache directive is checked here too, not just ``no-store``.
        """
        status, headers, body = self.request("/nope")
        self.assertEqual(status, 404)
        self.assertEqual(
            header_names(headers),
            {"content-type", "cache-control", "content-length"},
        )
        self.assertEqual(headers.get("Content-Type"), EXPECTED_CONTENT_TYPE)
        self.assertEqual(int(headers.get("Content-Length")), len(body))
        for directive in ("no-cache", "no-store", "must-revalidate"):
            self.assertIn(directive, headers.get("cache-control", ""))

    def test_the_refusal_headers_are_exactly_the_frozen_set_plus_allow(self):
        """``Allow`` is the ONE field the 405 adds, and it is added on every verb."""
        for method, data in (("POST", b""), ("PUT", b""), ("DELETE", None),
                             ("PATCH", b""), ("OPTIONS", None), ("HEAD", None)):
            with self.subTest(method=method):
                status, headers, _ = self.request(
                    self.route, method=method, data=data
                )
                self.assertEqual(status, 405)
                self.assertEqual(
                    header_names(headers),
                    {"content-type", "cache-control", "content-length", "allow"},
                )
                self.assertEqual(headers.get("Allow"), EXPECTED_ALLOW_HEADER)
                for directive in ("no-cache", "no-store", "must-revalidate"):
                    self.assertIn(directive, headers.get("cache-control", ""))

    def test_the_content_length_is_accurate_on_every_response(self):
        """An inaccurate length corrupts a persistent HTTP/1.1 connection."""
        cases = (
            (self.route, "GET", None),
            ("/nope", "GET", None),
            (self.route, "POST", b""),
        )
        for path, method, data in cases:
            with self.subTest(path=path, method=method):
                _, headers, body = self.request(path, method=method, data=data)
                self.assertEqual(int(headers.get("Content-Length")), len(body))

    def test_the_endpoint_serves_concurrent_requests(self):
        """The listener is threaded so a probe cannot queue behind a CI poll.

        Any failure here is a real defect rather than flakiness: the endpoint has
        no shared mutable state, so concurrent requests cannot interfere.
        """
        results = []
        lock = threading.Lock()

        def poll():
            status, _, body = self.request(self.route)
            with lock:
                results.append((status, json.loads(body.decode("utf-8"))["status"]))

        threads = [
            threading.Thread(target=poll, name=f"health-poll-{index}", daemon=True)
            for index in range(4)
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=THREAD_JOIN_TIMEOUT_SECONDS)
            self.assertFalse(thread.is_alive(), msg="a concurrent poll did not finish")
        self.assertEqual(results, [(200, EXPECTED_STATUS)] * 4)

    def test_repeated_polling_keeps_answering(self):
        """A health endpoint exists to be polled, so polling must be repeatable."""
        for attempt in range(3):
            with self.subTest(attempt=attempt):
                status, _, body = self.request(self.route)
                self.assertEqual(status, 200)
                self.assertEqual(len(body), REFERENCE_BODY_LENGTH)


class TestProbe(HealthServerTestCase):
    """The in-process self-check that answers ``--probe``."""

    def _config_for(self, host, port):
        """Copy the class configuration, pointing it at ``host`` and ``port``."""
        config = dict(self.config)
        config["app.host"] = host
        config["python.port"] = str(port)
        return config

    def test_the_probe_reports_healthy_against_the_live_endpoint(self):
        self.assertEqual(app.probe(self._config_for(LOOPBACK, self.port)), 0)

    def test_the_probe_substitutes_loopback_for_a_wildcard_bind_address(self):
        """``0.0.0.0`` names every interface and is not a routable destination.

        The configured host is the wildcard by default, so without this
        substitution a wildcard-bound process could never reach its own endpoint.
        """
        self.assertEqual(app.probe(self._config_for("0.0.0.0", self.port)), 0)

    def test_the_probe_fails_closed_when_nothing_is_listening(self):
        """A probe that cannot prove health must report unhealthy.

        The diagnostic is asserted too, in two parts.  It must name the target so
        that an operator knows which endpoint was checked, and it must report the
        transport fault as an exception TYPE rather than an exception message:
        message text can carry response-derived or resolver-derived content, and
        this line goes into a log.
        """
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(self._config_for(LOOPBACK, unused_port()))
        self.assertEqual(verdict, 1)
        diagnostic = stderr.getvalue()
        self.assertIn("could not reach", diagnostic)
        self.assertIn(f"http://{LOOPBACK}:", diagnostic)
        self.assertIn("ConnectionRefusedError", diagnostic)

    def test_the_probe_fails_closed_on_a_non_health_route(self):
        """Pointed at a path that answers 404, the verdict is unhealthy."""
        stderr = io.StringIO()
        config = self._config_for(LOOPBACK, self.port)
        config["health.path"] = "/not-the-health-route"
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(config)
        self.assertEqual(verdict, 1)
        self.assertIn("404", stderr.getvalue())

    def test_a_non_loopback_configured_host_is_still_probed_on_loopback(self):
        """The destination is selected, not derived; see TestProbeAuthority.

        ``app.host`` is an input.  A probe that honoured it would turn a configured
        value pointing off the machine into an outbound HTTP client - reporting
        this application healthy because some other host answered.  The live
        endpoint here is on loopback and nothing is listening on the named host, so
        a verdict of healthy is only possible if loopback was dialled.
        """
        stderr = io.StringIO()
        config = self._config_for("monitoring.example.com", self.port)
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(config)
        written = stderr.getvalue()
        self.assertEqual(verdict, 0)
        self.assertIn("not loopback", written)
        self.assertNotIn("example.com", written)


# Diagnostic safety.  Everything this module says about itself goes to stderr, and
# every line of it is a fixed category: a configured value or an exception string
# reaching a log line would both disclose the deployment and - for any value
# carrying a CR or an LF - let a caller forge log entries.


class TestDiagnosticSafety(unittest.TestCase):
    """Fixed categories only, stderr only, and one line per diagnostic."""

    def test_log_warning_writes_one_prefixed_line_to_stderr_only(self):
        """Nothing may reach stdout: it is the hashed backward-compatibility
        contract of this program."""
        stderr = io.StringIO()
        stdout = io.StringIO()
        with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(stdout):
            app.log_warning("a fixed category")
        self.assertEqual(stderr.getvalue(), "[app.py] a fixed category\n")
        self.assertEqual(stdout.getvalue(), "")

    def test_log_warning_strips_control_characters_so_no_line_can_be_forged(self):
        """A CR and an LF in the text must not become a second log entry."""
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            app.log_warning("real\r\n[app.py] forged entry\x1b[2J\x7fand an escape")
        written = stderr.getvalue()
        self.assertEqual(written.count("\n"), 1)
        self.assertTrue(written.endswith("\n"))
        self.assertNotIn("\r", written)
        self.assertNotIn("\x1b", written)
        self.assertNotIn("\x7f", written)
        self.assertIn("forged entry", written)

    def test_an_unreadable_configuration_file_reports_no_path_and_no_error_text(self):
        """A directory is readable as a name but not as a file.

        The failure is neither absence - which is silent and expected - nor a
        parse problem, so it is exactly the branch that reports.  What it reports
        must not include the path, which is a deployment detail, nor the exception
        text, which embeds that path.
        """
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            properties = app.read_properties(directory.name)
        written = stderr.getvalue()
        self.assertEqual(properties, {})
        self.assertEqual(written.count("\n"), 1)
        self.assertIn("cannot read the configuration file", written)
        self.assertNotIn(directory.name, written)
        self.assertNotIn("Errno", written)

    def test_an_unusable_port_is_reported_without_naming_the_value(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(
                {
                    "app.host": LOOPBACK,
                    "python.port": "not-a-port",
                    "health.path": "/health",
                }
            )
        written = stderr.getvalue()
        self.assertEqual(verdict, 1)
        self.assertEqual(written.count("\n"), 1)
        self.assertIn("port is unusable", written)
        self.assertNotIn("not-a-port", written)

    def test_a_health_path_carrying_crlf_cannot_forge_a_probe_log_line(self):
        """A health path carrying CRLF must not be able to forge a log line.

        The path is refused before a request is constructed, and the single line
        the refusal emits carries none of it.
        """
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(
                {
                    "app.host": LOOPBACK,
                    "python.port": str(unused_port()),
                    "health.path": "/health\r\n[app.py] forged entry",
                }
            )
        written = stderr.getvalue()
        self.assertEqual(verdict, 1)
        self.assertEqual(written.count("\n"), 1)
        self.assertIn("not a valid request target", written)
        self.assertNotIn("forged entry", written)


# Probe destination allowlist


class TestProbeAuthority(unittest.TestCase):
    """The probe destination is SELECTED from a loopback set, never derived.

    Two properties are asserted separately because they fail separately: that
    every legitimate loopback spelling is honoured exactly and silently, and that
    everything else is replaced - with one fixed line and without echoing the
    value that was refused.
    """

    #: Configured value mapped to the authority the probe must dial.
    ACCEPTED = {
        None: EXPECTED_LOOPBACK_AUTHORITY,
        "": EXPECTED_LOOPBACK_AUTHORITY,
        "   ": EXPECTED_LOOPBACK_AUTHORITY,
        "0.0.0.0": EXPECTED_LOOPBACK_AUTHORITY,
        "::": EXPECTED_LOOPBACK_AUTHORITY,
        "[::]": EXPECTED_LOOPBACK_AUTHORITY,
        "*": EXPECTED_LOOPBACK_AUTHORITY,
        "localhost": EXPECTED_LOOPBACK_AUTHORITY,
        "LocalHost": EXPECTED_LOOPBACK_AUTHORITY,
        "127.0.0.1": "127.0.0.1",
        " 127.0.0.1 ": "127.0.0.1",
        "127.0.0.2": "127.0.0.2",
        "127.255.255.254": "127.255.255.254",
        "::1": EXPECTED_LOOPBACK_AUTHORITY_V6,
        "[::1]": EXPECTED_LOOPBACK_AUTHORITY_V6,
        "0:0:0:0:0:0:0:1": EXPECTED_LOOPBACK_AUTHORITY_V6,
        "[0:0:0:0:0:0:0:1]": EXPECTED_LOOPBACK_AUTHORITY_V6,
    }

    #: Values that must NOT be dialled.  The link-local metadata address and the
    #: private ranges are the ones that make this a security property rather than
    #: a tidiness one; the rest are the near-miss spellings a permissive address
    #: parser would accept as loopback and this one must not.
    REFUSED = (
        "monitoring.example.com",
        "10.0.0.5",
        "192.168.1.1",
        "169.254.169.254",
        "8.8.8.8",
        "127.0.0.256",
        "127.1",
        "0x7f.0.0.1",
        "2130706433",
        "127.0.0.1.example.com",
        "127.0.0.\u0661",
        "evil\r\nX-Injected: 1",
        "::2",
        "localhost.example.com",
    )

    def test_every_loopback_spelling_is_honoured_exactly_and_silently(self):
        for host, expected in self.ACCEPTED.items():
            with self.subTest(host=host):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    resolved = app.probe_authority(host)
                self.assertEqual(resolved, expected)
                self.assertEqual(stderr.getvalue(), "")

    def test_every_other_value_is_replaced_by_loopback(self):
        for host in self.REFUSED:
            with self.subTest(host=host):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    resolved = app.probe_authority(host)
                self.assertEqual(resolved, EXPECTED_LOOPBACK_AUTHORITY)
                self.assertIn("not loopback", stderr.getvalue())

    def test_a_refused_value_is_never_echoed_and_emits_exactly_one_line(self):
        for host in self.REFUSED:
            with self.subTest(host=host):
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    app.probe_authority(host)
                written = stderr.getvalue()
                self.assertEqual(written.count("\n"), 1)
                self.assertNotIn(host, written)


# Probe bounds.  The probe is a client, and a client is only as safe as its
# behaviour against a peer that does not cooperate.  Each test here points it at a
# deliberately broken endpoint: one that answers forever, one that never answers,
# one that answers with far too much, and one that answers with something that
# merely looks right.  Every one must end in a bounded, unhealthy verdict.


class TestProbeBounds(unittest.TestCase):
    """Bounded in time, bounded in bytes, and strict about the document."""

    def _probe(self, port, path="/health", host=LOOPBACK, name=None, version=None):
        """Probe ``port`` and return ``(verdict, stderr)``.

        ``name`` and ``version`` state the identity the probe is to expect.  They
        matter because the probe grades identity as well as shape: a document is
        only proof of THIS application's health if it names this application, so a
        test serving a padded document has to configure the padded name it serves.
        Left unset, the committed defaults apply, which is what every hostile case
        below wants - the answer is meant to be refused.
        """
        config = {"app.host": host, "python.port": str(port), "health.path": path}
        if name is not None:
            config["app.name"] = name
        if version is not None:
            config["app.version"] = version
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(config)
        return verdict, stderr.getvalue()

    def test_an_endpoint_that_streams_without_end_cannot_outlive_the_deadline(self):
        """The case a per-read timeout cannot bound.

        The chunks arrive faster than any inactivity timeout, so every individual
        read succeeds and an implementation without an ABSOLUTE deadline stays in
        this loop for as long as the peer keeps trickling.  Ending at all is the
        assertion; no duration is compared to anything.
        """
        endpoint = hostile_endpoint(
            self,
            head=b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n",
            trickle=True,
        )
        verdict, written = self._probe(endpoint.port)
        self.assertEqual(verdict, 1)
        self.assertIn("deadline", written)

    def test_an_endpoint_that_never_answers_cannot_hold_the_probe(self):
        """A peer that accepts the connection and then says nothing at all."""
        endpoint = hostile_endpoint(self, mute=True)
        verdict, written = self._probe(endpoint.port)
        self.assertEqual(verdict, 1)
        self.assertEqual(written.count("\n"), 1)

    def test_a_body_larger_than_the_ceiling_is_refused_rather_than_accumulated(self):
        body = b"0" * OVERSIZED_BODY_BYTES
        endpoint = hostile_endpoint(self, head=json_head(len(body)), body=body)
        verdict, written = self._probe(endpoint.port)
        self.assertEqual(verdict, 1)
        self.assertIn("exceeds the probe limit", written)

    def test_the_ceiling_is_inclusive_and_one_byte_past_it_is_refused(self):
        """Both sides of the limit, so an off-by-one cannot hide in either.

        The accepted side configures the padded name it serves, because the probe
        grades identity too.  That is not a workaround: it is the F-15 hazard stated
        as a test.  A document at exactly the ceiling is one an ``app.name`` of
        ``PROBE_BODY_CEILING - 78`` bytes produces, so this pair also pins the
        largest name whose own healthy answer the probe can still read.
        """
        at_limit = padded_document(PROBE_BODY_CEILING)
        accepted = hostile_endpoint(
            self, head=json_head(len(at_limit)), body=at_limit
        )
        verdict, written = self._probe(
            accepted.port,
            name=padded_name(PROBE_BODY_CEILING),
            version=PADDED_DOCUMENT_VERSION,
        )
        self.assertEqual(verdict, 0, msg=written)

        past_limit = padded_document(PROBE_BODY_CEILING + 1)
        refused = hostile_endpoint(
            self, head=json_head(len(past_limit)), body=past_limit
        )
        verdict, written = self._probe(refused.port)
        self.assertEqual(verdict, 1)
        self.assertIn("exceeds the probe limit", written)

    def test_malformed_json_containing_the_healthy_fragment_is_not_healthy(self):
        """A substring match would grade this healthy; a parse cannot.

        This is the fail-closed property in its sharpest form: the body carries
        the exact bytes of a healthy status field and is still not a JSON
        document, so the only correct verdict is unhealthy.
        """
        endpoint = hostile_endpoint(
            self,
            head=json_head(len(FORGED_STATUS_BODY)),
            body=FORGED_STATUS_BODY,
        )
        verdict, written = self._probe(endpoint.port)
        self.assertEqual(verdict, 1)
        self.assertIn("not the expected JSON", written)

    def test_a_json_body_that_is_not_an_object_is_not_healthy(self):
        body = b'"UP"'
        endpoint = hostile_endpoint(self, head=json_head(len(body)), body=body)
        verdict, written = self._probe(endpoint.port)
        self.assertEqual(verdict, 1)
        self.assertIn("status field", written)

    def test_a_document_without_the_expected_status_is_not_healthy(self):
        body = b'{"name":"x","version":"1.1.0","timestamp":"z","status":"DOWN"}'
        endpoint = hostile_endpoint(self, head=json_head(len(body)), body=body)
        verdict, written = self._probe(endpoint.port)
        self.assertEqual(verdict, 1)
        self.assertIn("status field", written)

    def test_a_non_200_answer_is_reported_by_code_and_nothing_else(self):
        body = b'{"error":"Internal Server Error"}'
        endpoint = hostile_endpoint(
            self,
            head=json_head(len(body), status=b"HTTP/1.1 500 Internal Server Error"),
            body=body,
        )
        verdict, written = self._probe(endpoint.port)
        self.assertEqual(verdict, 1)
        self.assertIn("500", written)
        self.assertNotIn("/health", written)

    def test_a_well_behaved_endpoint_is_healthy_and_silent(self):
        """The positive control: these bounds must not reject a correct answer."""
        body = padded_document(120)
        endpoint = hostile_endpoint(self, head=json_head(len(body)), body=body)
        verdict, written = self._probe(
            endpoint.port,
            name=padded_name(120),
            version=PADDED_DOCUMENT_VERSION,
        )
        self.assertEqual(verdict, 0, msg=written)
        self.assertEqual(written, "")


class TestRequestBodyDrain(HealthServerTestCase):
    """A refused request arrives WITH a body, and the connection is reused.

    ``BaseHTTPRequestHandler`` never reads a request body.  Left queued on a
    kept-alive connection those bytes are consumed as the start of the NEXT
    request line: a three-byte body in front of a following ``GET`` parses as the
    method ``xyzGET``, which the inherited error path answers with a 501 carrying
    an HTML body and both a ``Server`` and a ``Date`` header - three departures
    from the frozen contract at once - and the legitimate request behind it is
    never answered at all.

    The other two implementations do not need this repaired: Node dumps an
    unconsumed request itself once the response finishes, and Java drains
    explicitly.  These tests pin the behaviour all three now share.
    """

    def _exchange(self, first, second, timeout=REQUEST_TIMEOUT_SECONDS):
        """Send two requests down ONE connection; return everything received."""
        client = socket.create_connection(
            (LOOPBACK, self.port), timeout=REQUEST_TIMEOUT_SECONDS
        )
        try:
            client.sendall(first)
            time.sleep(PIPELINE_GAP_SECONDS)
            client.sendall(second)
            client.settimeout(timeout)
            return read_response(client)
        finally:
            client.close()

    def test_a_refused_request_with_a_body_does_not_corrupt_the_next_one(self):
        body = b"xyz"
        received = self._exchange(
            b"POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: %d\r\n\r\n%s"
            % (len(body), body),
            b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        self.assertEqual(received.count(b"HTTP/1.1 "), 2, msg=repr(received))
        self.assertIn(b"405 Method Not Allowed", received)
        self.assertIn(b"200 OK", received)
        self.assertIn(b'"status":"UP"', received)

    def test_no_leftover_byte_is_ever_parsed_as_a_request_line(self):
        """The 501, the HTML body and the Server header are all absent."""
        received = self._exchange(
            b"POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\nxyz",
            b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        self.assertNotIn(b"501", received)
        self.assertNotIn(b"<html", received.lower())
        self.assertNotIn(b"Server:", received)
        self.assertNotIn(b"Date:", received)

    def test_a_body_on_the_health_route_itself_is_drained_too(self):
        """The drain sits on the shared response path, not on the 405 branch."""
        received = self._exchange(
            b"GET /health HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\nxyz",
            b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        self.assertEqual(received.count(b"HTTP/1.1 200 OK"), 2, msg=repr(received))

    def test_a_chunked_body_retires_the_connection_instead_of_guessing(self):
        """Decoding a chunked body needs a reader this server does not carry.

        The response is still written; only the connection is retired.  A second
        request on it must therefore go unanswered rather than be misparsed.
        """
        received = self._exchange(
            b"POST /health HTTP/1.1\r\nHost: h\r\n"
            b"Transfer-Encoding: chunked\r\n\r\n3\r\nxyz\r\n0\r\n\r\n",
            b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        self.assertEqual(received.count(b"HTTP/1.1 "), 1, msg=repr(received))
        self.assertIn(b"405 Method Not Allowed", received)

    def test_a_length_above_the_ceiling_is_refused_rather_than_read(self):
        """Reading it to be polite is the unbounded read the limit prevents."""
        received = self._exchange(
            b"POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: %d\r\n\r\n"
            % (app.MAX_REQUEST_DRAIN_BYTES + 1),
            b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        self.assertEqual(received.count(b"HTTP/1.1 "), 1, msg=repr(received))
        self.assertIn(b"405 Method Not Allowed", received)

    def test_a_length_that_is_not_ascii_decimal_retires_the_connection(self):
        """The same grammar the port uses: a plain non-negative decimal, or no.

        A sign, a radix prefix and a non-ASCII digit are each refused.  The last
        one matters most: ``int()`` accepts Unicode decimal digits, so the Arabic-
        Indic three would otherwise be read as a length of three and the two
        implementations would disagree about where the body ends.
        """
        for stated in (b"+3", b"-3", b"0x3", "\u0663".encode("utf-8"), b"3.0", b""):
            with self.subTest(stated=stated):
                received = self._exchange(
                    b"POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: "
                    + stated + b"\r\n\r\nxyz",
                    b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
                )
                self.assertNotIn(b"200 OK", received, msg=repr(received))

    def test_whitespace_around_the_length_is_not_part_of_the_value(self):
        """RFC 9110 excludes optional surrounding whitespace from a field value.

        ``Content-Length: 3 `` therefore states three, and the exchange proceeds
        normally rather than retiring the connection.
        """
        received = self._exchange(
            b"POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: 3 \r\n\r\nxyz",
            b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        self.assertEqual(received.count(b"HTTP/1.1 "), 2, msg=repr(received))
        self.assertIn(b"200 OK", received)

    def test_an_under_delivered_body_gives_up_on_its_own_budget(self):
        """A client that promises a hundred bytes, sends three and goes quiet.

        The drain is a BLOCKING read, so without a bound of its own it parks a
        handler thread for the lifetime of the process.  The budget is lowered for
        the duration of this test so the assertion costs a fraction of a second
        rather than the production budget, and it is restored on every path.
        """
        original = app.REQUEST_DRAIN_TIMEOUT_SECONDS
        app.REQUEST_DRAIN_TIMEOUT_SECONDS = SHORT_DRAIN_BUDGET_SECONDS
        try:
            client = socket.create_connection(
                (LOOPBACK, self.port), timeout=REQUEST_TIMEOUT_SECONDS
            )
            try:
                client.sendall(
                    b"POST /health HTTP/1.1\r\nHost: h\r\n"
                    b"Content-Length: 100\r\n\r\nxyz"
                )
                began = time.monotonic()
                client.settimeout(REQUEST_TIMEOUT_SECONDS)
                received = read_response(client)
                elapsed = time.monotonic() - began
            finally:
                client.close()
        finally:
            app.REQUEST_DRAIN_TIMEOUT_SECONDS = original
        self.assertIn(b"405 Method Not Allowed", received)
        self.assertGreaterEqual(elapsed, SHORT_DRAIN_BUDGET_SECONDS * 0.5)
        self.assertLess(elapsed, REQUEST_TIMEOUT_SECONDS)

    def test_the_drain_budget_is_the_javascript_request_budget(self):
        """One number governs the same behaviour in both implementations."""
        self.assertEqual(app.REQUEST_DRAIN_TIMEOUT_SECONDS, 15.0)
        self.assertEqual(app.MAX_REQUEST_DRAIN_BYTES, 8 * 1024 * 1024)

    def test_serving_a_drained_exchange_writes_no_diagnostic(self):
        """A slow or sloppy client is not this endpoint's news to report."""
        with contextlib.redirect_stderr(io.StringIO()) as sink:
            self._exchange(
                b"POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\nxyz",
                b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
            )
        self.assertEqual(sink.getvalue(), "")


class TestAmbiguousFraming(HealthServerTestCase):
    """A request whose body length has no single answer must not be followed by one.

    CWE-444.  ``self.headers`` is a multi-dict, so a lookup that takes the FIRST
    ``Content-Length`` silently picks one of two contradictory framings: with ``0``
    and ``5`` present it drains nothing, and the five bytes it left behind are read
    as the next request line on a connection it kept alive.  A front end resolving
    the same message to ``5`` would call those bytes a body.  Two participants, two
    readings, one connection - which is the definition of the hazard.

    Retiring the connection is the whole fix, and it is sufficient rather than
    merely mitigating: bytes on a connection nobody reads again cannot be
    reinterpreted.  No new status code is introduced, because the frozen contract
    defines three responses and this needs none of them changed.

    A control sits alongside: an honest single length must still drain and still keep
    the connection.  Without it this class would also pass if every POST simply
    closed, which would be a different defect wearing this fix's clothes.
    """

    def _pipelined(self, first, second, timeout=REQUEST_TIMEOUT_SECONDS):
        """Send two requests down ONE connection; return everything received."""
        client = socket.create_connection(
            (LOOPBACK, self.port), timeout=REQUEST_TIMEOUT_SECONDS
        )
        try:
            client.sendall(first)
            time.sleep(PIPELINE_GAP_SECONDS)
            client.sendall(second)
            client.settimeout(timeout)
            return read_response(client)
        finally:
            client.close()

    #: Framings with no single reading, each followed by bytes a front end would
    #: call a body and a desynchronised server would call a request.
    AMBIGUOUS = {
        "two disagreeing lengths, low first": (
            b"POST /health HTTP/1.1\r\nHost: h\r\n"
            b"Content-Length: 0\r\nContent-Length: 5\r\n\r\n"
        ),
        "two disagreeing lengths, high first": (
            b"POST /health HTTP/1.1\r\nHost: h\r\n"
            b"Content-Length: 5\r\nContent-Length: 0\r\n\r\n"
        ),
        "two AGREEING lengths": (
            b"POST /health HTTP/1.1\r\nHost: h\r\n"
            b"Content-Length: 5\r\nContent-Length: 5\r\n\r\n"
        ),
        "one line carrying a comma list": (
            b"POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: 0, 5\r\n\r\n"
        ),
        "a length continued by an obs-fold": (
            b"POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: 0\r\n 5\r\n\r\n"
        ),
        "chunked and a length together": (
            b"POST /health HTTP/1.1\r\nHost: h\r\n"
            b"Transfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n"
        ),
    }

    #: The bytes queued behind each ambiguous head: a complete, valid request.
    SMUGGLED = b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"

    def test_an_ambiguous_length_is_answered_once_and_never_twice(self):
        for label, head in self.AMBIGUOUS.items():
            with self.subTest(framing=label):
                received = self._pipelined(head, self.SMUGGLED)
                self.assertEqual(
                    received.count(b"HTTP/1.1 "), 1,
                    msg="%s: %r" % (label, received),
                )

    def test_no_smuggled_request_is_ever_served(self):
        """The queued GET must not produce a payload behind the refusal."""
        for label, head in self.AMBIGUOUS.items():
            with self.subTest(framing=label):
                received = self._pipelined(head, self.SMUGGLED)
                self.assertNotIn(b'"status":"UP"', received, msg=label)
                self.assertNotIn(b"200 OK", received, msg=label)

    def test_the_one_answer_is_still_the_frozen_contract(self):
        """Retiring the connection changes the framing, never the response."""
        for label, head in self.AMBIGUOUS.items():
            with self.subTest(framing=label):
                received = self._pipelined(head, self.SMUGGLED)
                self.assertIn(b"405 Method Not Allowed", received, msg=label)
                self.assertIn(b'{"error":"Method Not Allowed"}', received, msg=label)
                self.assertIn(b"Allow: GET", received, msg=label)
                self.assertNotIn(b"Server:", received, msg=label)
                self.assertNotIn(b"Date:", received, msg=label)
                self.assertNotIn(b"<html", received.lower(), msg=label)

    def test_an_ambiguous_exchange_writes_no_diagnostic(self):
        """A hostile framing is not this endpoint's news to report either."""
        with contextlib.redirect_stderr(io.StringIO()) as sink:
            self._pipelined(
                self.AMBIGUOUS["two disagreeing lengths, low first"], self.SMUGGLED
            )
        self.assertEqual(sink.getvalue(), "")

    def test_repetition_alone_is_refused_even_when_the_values_agree(self):
        """RFC 9112 section 6.3 allows folding or refusing; the other two refuse.

        Refusing is therefore what makes the three answers uniform, and uniformity
        is the property the shared contract is worth having for.
        """
        received = self._pipelined(
            b"POST /health HTTP/1.1\r\nHost: h\r\n"
            b"Content-Length: 5\r\nContent-Length: 5\r\n\r\nhello",
            self.SMUGGLED,
        )
        self.assertEqual(received.count(b"HTTP/1.1 "), 1, msg=repr(received))
        self.assertNotIn(b'"status":"UP"', received)

    def test_a_single_honest_length_still_keeps_the_connection(self):
        """The control that fails if the guard ever widens to every POST."""
        received = self._pipelined(
            b"POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhello",
            self.SMUGGLED,
        )
        self.assertEqual(received.count(b"HTTP/1.1 "), 2, msg=repr(received))
        self.assertIn(b'"status":"UP"', received)


class TestMethodDispatchSurface(unittest.TestCase):
    """The handler answers for every ``do_*`` name, and claims nothing else.

    The inherited request loop dispatches on ``hasattr(self, "do_" + command)``,
    so a method policy written as a list of explicit ``do_*`` methods covers only
    the verbs someone thought of.  The class therefore resolves the whole ``do_``
    namespace dynamically.  That is a broad hook, and these checks pin both
    halves of the deal: every ``do_`` name resolves to the refusal, and no other
    name is intercepted, so :func:`hasattr` keeps telling the truth about this
    object and the introspection, copy and pickle protocols keep working.

    An uninitialised instance is enough, and is used deliberately: the attribute
    contract must hold before any socket exists, and constructing a real handler
    would require a connection and would run a request.
    """

    def setUp(self):
        self.handler = object.__new__(app.HealthRequestHandler)

    def test_an_unimplemented_method_resolves_to_the_refusal(self):
        for name in ("do_TRACE", "do_CONNECT", "do_FROBNICATE", "do_PROPFIND"):
            with self.subTest(name=name):
                resolved = getattr(self.handler, name)
                self.assertIs(
                    resolved.__func__, app.HealthRequestHandler._method_not_allowed
                )

    def test_an_explicit_method_is_not_shadowed_by_the_fallback(self):
        """GET must still reach the health route, not the refusal."""
        self.assertIs(
            self.handler.do_GET.__func__, app.HealthRequestHandler.do_GET
        )

    def test_the_prefix_alone_is_not_a_method_name(self):
        with self.assertRaises(AttributeError):
            getattr(self.handler, "do_")

    def test_no_other_missing_attribute_is_intercepted(self):
        """A hook that answered for everything would break far more than it fixed."""
        for name in ("missing", "_private", "DO_GET", "handle_one_request_", "todo_x"):
            with self.subTest(name=name):
                with self.assertRaises(AttributeError):
                    getattr(self.handler, name)

    def test_the_attribute_error_names_the_attribute(self):
        """The message must stay the one the interpreter would have produced."""
        with self.assertRaises(AttributeError) as raised:
            getattr(self.handler, "definitely_absent")
        self.assertIn("definitely_absent", str(raised.exception))


class TestMethodPolicyOverRawSockets(HealthServerTestCase):
    """Every method token a request line can carry, not just the six with methods.

    ``urllib`` cannot send these: it refuses to construct a CONNECT the way this
    test needs it, and an invented extension token has no client-side support at
    all.  Yet these are exactly the requests that escaped the contract before the
    dispatch was made total - each one reached the inherited 501 path, which
    answers with a 483-to-488 byte HTML document, a ``Server`` banner naming the
    interpreter, a ``Date`` header, and the caller's own method token reflected
    into the status line.  Four departures from the frozen contract, reachable by
    anyone who could open a socket.

    The sibling Node and Java implementations reject on "not GET" rather than on a
    list of known verbs, so these assertions are also what keeps the three
    agreeing about what a non-GET request receives.
    """

    #: Method tokens with no ``do_*`` method of their own.  The first two are
    #: registered HTTP methods, the third is a legal extension token, and the
    #: fourth is a real method from another specification - the kind of request a
    #: scanner sends first and an ordinary consumer never sends at all.
    UNIMPLEMENTED_METHODS = (b"TRACE", b"CONNECT", b"FROBNICATE", b"PROPFIND")

    #: Exactly the fields a refusal carries: the frozen three, plus ``Allow``.
    REFUSAL_HEADER_NAMES = {"content-type", "cache-control", "content-length", "allow"}

    def _refuse(self, method, target=None):
        """Send one raw request with ``method`` and return the parsed reply."""
        if target is None:
            target = self.route.encode("ascii")
        request = (
            method
            + b" "
            + target
            + b" HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"
        )
        received = raw_exchange(self.port, request)
        return received, parse_raw_response(received)

    def test_every_unimplemented_method_receives_the_frozen_refusal(self):
        for method in self.UNIMPLEMENTED_METHODS:
            with self.subTest(method=method.decode("ascii")):
                _, (status_line, headers, body) = self._refuse(method)
                self.assertEqual(status_line, b"HTTP/1.1 405 Method Not Allowed")
                self.assertEqual(body, EXPECTED_METHOD_NOT_ALLOWED_BODY)
                self.assertEqual(
                    header_names(headers),
                    self.REFUSAL_HEADER_NAMES,
                    msg="the refusal header set is frozen at four fields",
                )
                self.assertEqual(headers["allow"], EXPECTED_ALLOW_HEADER)
                self.assertEqual(headers["content-type"], EXPECTED_CONTENT_TYPE)
                self.assertEqual(int(headers["content-length"]), len(body))

    def test_no_unimplemented_method_reaches_the_not_implemented_page(self):
        """The 501, the HTML, the banner and the Date header are all absent."""
        for method in self.UNIMPLEMENTED_METHODS:
            with self.subTest(method=method.decode("ascii")):
                received, (_, headers, _) = self._refuse(method)
                lowered = received.lower()
                self.assertNotIn(b"501", received)
                self.assertNotIn(b"not implemented", lowered)
                self.assertNotIn(b"unsupported method", lowered)
                self.assertNotIn(b"<html", lowered)
                self.assertNotIn(b"basehttp", lowered)
                self.assertNotIn(b"python", lowered)
                self.assertNotIn("server", header_names(headers))
                self.assertNotIn("date", header_names(headers))

    def test_a_refusal_never_reflects_the_method_token(self):
        """The inherited page quotes the verb back; this one discloses nothing."""
        for method in self.UNIMPLEMENTED_METHODS:
            with self.subTest(method=method.decode("ascii")):
                received, _ = self._refuse(method)
                self.assertNotIn(method, received)

    def test_connect_in_its_authority_form_is_refused_without_echo(self):
        """CONNECT carries an authority, not a path, and it must not come back."""
        authority = b"internal-host.example:8443"
        received, (status_line, headers, body) = self._refuse(b"CONNECT", authority)
        self.assertEqual(status_line, b"HTTP/1.1 405 Method Not Allowed")
        self.assertEqual(body, EXPECTED_METHOD_NOT_ALLOWED_BODY)
        self.assertEqual(headers["allow"], EXPECTED_ALLOW_HEADER)
        self.assertNotIn(b"internal-host", received)

    def test_a_refusal_also_refuses_caching(self):
        for method in self.UNIMPLEMENTED_METHODS:
            with self.subTest(method=method.decode("ascii")):
                _, (_, headers, _) = self._refuse(method)
                for directive in ("no-cache", "no-store", "must-revalidate"):
                    self.assertIn(directive, headers["cache-control"])

    def test_the_health_route_still_answers_after_an_unimplemented_method(self):
        """The dynamic dispatch must not disturb the request it exists beside."""
        self._refuse(b"TRACE")
        status, _, body = self.request(self.route)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body.decode("utf-8"))["status"], EXPECTED_STATUS)


class TestTransportRejection(HealthServerTestCase):
    """A request too malformed to have a method, refused in one status line.

    These requests never reach a ``do_*`` method: the transport rejects them while
    parsing, which is the one response this handler cannot compose from the frozen
    contract.  The inherited error path is unusable here - it emits an HTML
    document, a ``Server`` banner, a ``Date`` header and the offending request line
    echoed into both the status line and the body, and a request line that fails to
    parse at all is classified as HTTP/0.9, for which the inherited writer emits no
    status line and no headers, making the whole reply a bare HTML document that is
    not a valid HTTP response.

    What must come back instead is a status line, ``Content-Length: 0``,
    ``Connection: close``, and nothing else: the same shape the JavaScript
    listener sends for the same requests.
    """

    #: ``(label, request bytes, expected status line, text that must not appear)``.
    #: Each case exercises a different branch of the transport's own parser, and
    #: each names the request-derived text a regression towards the inherited page
    #: would put back on the wire.
    REJECTION_CASES = (
        (
            "a one-word request line",
            b"GARBAGE\r\n\r\n",
            b"HTTP/1.1 400 Bad Request",
            b"GARBAGE",
        ),
        (
            "a version that does not parse",
            b"GARBAGE REQUEST line\r\n\r\n",
            b"HTTP/1.1 400 Bad Request",
            b"GARBAGE",
        ),
        (
            "an HTTP/0.9 request that is not a GET",
            b"POST /health\r\n\r\n",
            b"HTTP/1.1 400 Bad Request",
            b"POST",
        ),
        (
            "an unsupported major version",
            b"GET /health HTTP/9.9\r\nHost: h\r\n\r\n",
            b"HTTP/1.1 505 HTTP Version Not Supported",
            b"9.9",
        ),
        (
            "a request line past the transport ceiling",
            b"GET /" + b"a" * OVERSIZED_REQUEST_LINE_BYTES + b" HTTP/1.1\r\n\r\n",
            b"HTTP/1.1 414 URI Too Long",
            b"aaaaaaaa",
        ),
        (
            "more header lines than the transport will parse",
            b"GET /health HTTP/1.1\r\nHost: h\r\n"
            + b"".join(
                b"X-Filler-%d: v\r\n" % index for index in range(EXCESSIVE_HEADER_COUNT)
            )
            + b"\r\n",
            b"HTTP/1.1 431 Request Header Fields Too Large",
            b"X-Filler",
        ),
        (
            "a single header line past the transport ceiling",
            b"GET /health HTTP/1.1\r\nHost: h\r\nX-Filler: "
            + b"b" * OVERSIZED_REQUEST_LINE_BYTES
            + b"\r\n\r\n",
            b"HTTP/1.1 431 Request Header Fields Too Large",
            b"bbbbbbbb",
        ),
    )

    def _reject(self, request):
        """Send ``request``, capture the one diagnostic, return the reply bytes.

        Each of these requests produces exactly one operator diagnostic.  Capturing
        it keeps this suite's own output clean and turns "exactly one line" into an
        assertion rather than an expectation: the sanitising emitter is what makes
        that true, and a refactor past it would show up here.  The diagnostic is
        written before the refusal is, so it has certainly arrived by the time the
        reply has been read.
        """
        with contextlib.redirect_stderr(io.StringIO()) as sink:
            received = raw_exchange(self.port, request)
        written = sink.getvalue()
        self.assertEqual(written.count("\n"), 1, msg=repr(written))
        return received

    def test_every_malformed_request_is_refused_in_one_status_line(self):
        for label, request, expected_status, _ in self.REJECTION_CASES:
            with self.subTest(case=label):
                received = self._reject(request)
                status_line, headers, body = parse_raw_response(received)
                self.assertEqual(status_line, expected_status)
                self.assertEqual(
                    header_names(headers),
                    {"content-length", "connection"},
                    msg="a transport refusal carries a length and a close, nothing more",
                )
                self.assertEqual(headers["content-length"], "0")
                self.assertEqual(headers["connection"].lower(), "close")
                self.assertEqual(body, b"")

    def test_no_malformed_request_receives_a_document_of_any_kind(self):
        for label, request, _, _ in self.REJECTION_CASES:
            with self.subTest(case=label):
                lowered = self._reject(request).lower()
                self.assertNotIn(b"<html", lowered)
                self.assertNotIn(b"<head", lowered)
                self.assertNotIn(b"error code", lowered)
                self.assertNotIn(b"basehttp", lowered)
                self.assertNotIn(b"python", lowered)
                self.assertNotIn(b"server:", lowered)
                self.assertNotIn(b"date:", lowered)

    def test_no_refusal_echoes_the_request_that_caused_it(self):
        """Every one of these values is reflected by the inherited page."""
        for label, request, _, forbidden in self.REJECTION_CASES:
            with self.subTest(case=label):
                received = self._reject(request)
                self.assertNotIn(forbidden, received)

    def test_a_reply_that_is_not_a_valid_response_would_be_caught(self):
        """A reply must always be a valid response: a status line is always written."""
        received = self._reject(b"GARBAGE\r\n\r\n")
        self.assertTrue(
            received.startswith(b"HTTP/1.1 "), msg=repr(received[:120])
        )
        self.assertIn(b"\r\n\r\n", received)

    def test_a_malformed_request_retires_the_connection(self):
        """Nothing after an unparseable request could be trusted to start cleanly."""
        with contextlib.redirect_stderr(io.StringIO()):
            client = socket.create_connection(
                (LOOPBACK, self.port), timeout=REQUEST_TIMEOUT_SECONDS
            )
            try:
                client.sendall(b"GARBAGE\r\n\r\n")
                first = read_one_response(client)
                client.settimeout(REQUEST_TIMEOUT_SECONDS)
                remainder = read_response(client)
            finally:
                client.close()
        self.assertIn(b"400 Bad Request", first)
        self.assertEqual(remainder, b"", msg="the connection must be closed, not idle")

    def test_a_transport_refusal_is_logged_as_exactly_one_line(self):
        """One line per refusal, and nothing in it the caller chose.

        The inherited parser passes its own wording to ``send_error``, and every one of
        those wordings quotes the request line - ``Bad request syntax ('GARBAGE')``.  The
        override discards it and logs the status code alone, so the request line reaches
        neither the network nor the operator's log.  The sanitising emitter is the second
        line of defence: even if a value did arrive, a control character in it could not
        open a second entry in whatever collects this process's stderr.
        """
        with contextlib.redirect_stderr(io.StringIO()) as sink:
            raw_exchange(self.port, b"GAR\x07BAGE\x1b[31m\r\n\r\n")
        written = sink.getvalue()
        self.assertEqual(written.count("\n"), 1, msg=repr(written))
        self.assertIn("refusing a malformed request with 400", written)
        self.assertNotIn("\x07", written)
        self.assertNotIn("\x1b", written)
        self.assertNotIn("GAR", written)
        self.assertNotIn("BAGE", written)
        self.assertNotIn("[31m", written)

    def test_no_refusal_diagnostic_carries_the_request_that_caused_it(self):
        """Every case above, asserted on the LOG rather than on the wire.

        ``test_no_refusal_echoes_the_request_that_caused_it`` covers the response; this
        covers the diagnostic, which is the other place the inherited wordings would put
        request-derived text.  Both matter, and they are separate paths: one is written to
        the socket and one to stderr.
        """
        for label, request, expected_status, forbidden in self.REJECTION_CASES:
            with self.subTest(case=label):
                with contextlib.redirect_stderr(io.StringIO()) as sink:
                    raw_exchange(self.port, request)
                written = sink.getvalue()
                self.assertEqual(written.count("\n"), 1, msg=repr(written))
                self.assertNotIn(
                    forbidden.decode("latin-1"),
                    written,
                    msg="the inherited wording would quote this",
                )
                code = expected_status.split()[1].decode("latin-1")
                self.assertIn(f"refusing a malformed request with {code}", written)


class TestRequestFraming(HealthServerTestCase):
    """The request grammar, held to on the bytes that arrived.

    ``BaseHTTPRequestHandler`` is lenient in ways the JavaScript and Java listeners are
    not, and every one of them was reachable from a socket: a TAB read as a delimiter, a
    bare LF read as a terminator, a leading ``//`` folded to one slash so ``//health``
    answered the health document, a two-field HTTP/0.9 line answered with a bare body and
    no status line, an empty line before the request answered with nothing at all, and a
    field line whose name is not a token ending the header block so that ``Content-Length``
    vanished and the body was read as the next request.

    Every case here was measured against all three implementations before it was fixed, so
    each one states what the other two do as well.  The pre-parse validation is what brings
    this implementation into line on every shape where the three CAN agree, and agreement
    is the contract: an operator polling one language must not get a different answer from
    another.

    Agreement is not total, and the exceptions are named per case rather than smoothed
    over, because a claim of uniformity that does not hold is worse than none.  Measured on
    the wire, four shapes still divide the three: ``GET  /health`` with two spaces and the
    two-field HTTP/0.9 form are answered 200 and 404 respectively by index.js and User.java
    where this implementation answers 400; a bare-LF terminator and a fourth token in the
    request line are answered 200 by User.java.  Each is conformant - RFC 9112 section 3
    permits a recipient to refuse a malformed line without requiring it - and refusing is
    the stricter reading, which is the reading taken here.
    """

    #: ``(label, request bytes)`` - each is a name for the SAME route, made out of the
    #: leniency being removed.  RFC 3986 section 4.2 reads a leading ``//`` as an
    #: authority rather than a path, and CPython gh-87389 folds it to one slash before any
    #: handler sees it, so each of these reached ``do_GET`` as ``/health``.
    ROUTE_ALIASES = (
        ("two leading slashes", b"//health"),
        ("three leading slashes", b"///health"),
        ("a hundred leading slashes", b"/" * 100 + b"health"),
        ("two leading slashes and a trailing one", b"//health/"),
        ("two leading slashes and two trailing", b"//health//"),
    )

    #: ``(label, request line)`` - a line that is not
    #: ``method SP request-target SP HTTP-version CRLF``.  ``str.split()`` treats every
    #: one of the whitespace forms as a delimiter and ``rstrip('\r\n')`` accepts either
    #: terminator, so all of these parsed before the pre-parse validation was added.
    #:
    #: The other two implementations refuse MOST but not all of them, and the
    #: exceptions were measured rather than assumed, because a comment claiming
    #: uniformity that does not hold is worse than no comment: index.js answers the
    #: frozen 200 to ``GET  /health`` (two spaces) and to the two-field HTTP/0.9 form,
    #: and User.java answers 200 to a bare-LF terminator and to a fourth field and 404
    #: to the two-space form.  Refusing all of them here is the stricter reading of
    #: RFC 9112 section 3, and it is the reading this implementation takes; where the
    #: three differ, the divergence is recorded in the implementation that diverges
    #: rather than smoothed over by loosening an assertion.
    MALFORMED_REQUEST_LINES = (
        ("a TAB after the target", b"GET /health\tHTTP/1.1\r\n"),
        ("a TAB before the target", b"GET\t/health HTTP/1.1\r\n"),
        ("a VERTICAL TAB delimiter", b"GET\x0b/health HTTP/1.1\r\n"),
        ("a FORM FEED delimiter", b"GET\x0c/health HTTP/1.1\r\n"),
        ("two spaces between the fields", b"GET  /health HTTP/1.1\r\n"),
        ("a bare LF terminator", b"GET /health HTTP/1.1\n"),
        ("the two-field HTTP/0.9 form", b"GET /health\r\n"),
        ("a fourth field", b"GET /health and more HTTP/1.1\r\n"),
        ("an embedded LF in the target", b"GET /hea\nlth HTTP/1.1\r\n"),
        ("an embedded CR in the target", b"GET /hea\rlth HTTP/1.1\r\n"),
        ("a version that is not HTTP/n.n", b"GET /health HTTP/1\r\n"),
        ("a version with no digits", b"GET /health HTTP/x.y\r\n"),
        ("a non-ASCII byte in the target", b"GET /health\xc3\xa9 HTTP/1.1\r\n"),
    )

    #: ``(label, header block)`` - a block the inherited parser would either truncate at
    #: the offending line or read as complete.  ``X-A : 1`` and ``X A: 1`` are not field
    #: lines, and a continuation cannot be the first line of a block.
    MALFORMED_HEADER_BLOCKS = (
        ("a space before the colon", b"Host: h\r\nX-A : 1\r\n\r\n"),
        ("a TAB before the colon", b"Host: h\r\nX-A\t: 1\r\n\r\n"),
        ("a space inside the field name", b"Host: h\r\nX A: 1\r\n\r\n"),
        ("a field line with no colon", b"Host: h\r\nX-A 1\r\n\r\n"),
        ("an empty field name", b"Host: h\r\n: 1\r\n\r\n"),
        ("a leading continuation line", b" continued\r\nHost: h\r\n\r\n"),
        ("a non-ASCII byte in the field name", b"Host: h\r\nX-\xc3\xa9: 1\r\n\r\n"),
    )

    REFUSAL = b"HTTP/1.1 400 Bad Request"

    def _quietly(self, exchange, *arguments):
        """Run one raw exchange with stderr captured, and return the reply.

        Every refusal writes exactly one operator diagnostic.  Capturing it keeps this
        suite's own output clean and turns "exactly one line" into an assertion.
        """
        with contextlib.redirect_stderr(io.StringIO()) as sink:
            received = exchange(self.port, *arguments)
        self.written = sink.getvalue()
        return received

    def _assert_frozen_refusal(self, received):
        """Assert the reply is the refusal shape, byte for byte.

        A status line, a length of zero, a close, and nothing else - the same shape
        ``TestTransportRejection`` asserts, because a malformed framing and a malformed
        request line are the same class of answer.
        """
        status_line, headers, body = parse_raw_response(received)
        self.assertEqual(status_line, self.REFUSAL)
        self.assertEqual(header_names(headers), {"content-length", "connection"})
        self.assertEqual(headers["content-length"], "0")
        self.assertEqual(headers["connection"].lower(), "close")
        self.assertEqual(body, b"")

    def test_a_leading_slash_alias_is_not_the_health_route(self):
        """The finding this class exists for: ``//health`` answered ``200``."""
        for label, target in self.ROUTE_ALIASES:
            with self.subTest(case=label):
                received = raw_exchange(
                    self.port,
                    b"GET " + target + b" HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
                )
                status_line, _, body = parse_raw_response(received)
                self.assertEqual(
                    status_line,
                    b"HTTP/1.1 404 Not Found",
                    msg="a folded target must not reach the health route",
                )
                self.assertEqual(body, app.render_payload(app.NOT_FOUND_BODY).encode())

    def test_an_alias_refusal_still_carries_the_frozen_error_contract(self):
        """A 404 here is the SAME 404 as any other unknown path, headers included."""
        received = raw_exchange(
            self.port, b"GET //health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n"
        )
        _, headers, _ = parse_raw_response(received)
        self.assertEqual(
            header_names(headers), {"content-type", "cache-control", "content-length"}
        )
        self.assertEqual(headers["content-type"], app.CONTENT_TYPE)
        self.assertEqual(headers["cache-control"], app.CACHE_CONTROL)

    def test_the_configured_route_itself_is_untouched_by_the_validation(self):
        """The whole point is that a well-formed request still gets its document."""
        received = raw_exchange(
            self.port,
            b"GET "
            + self.route.encode()
            + b" HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        status_line, _, body = parse_raw_response(received)
        self.assertEqual(status_line, b"HTTP/1.1 200 OK")
        self.assertEqual(json.loads(body)["status"], app.HEALTH_STATUS)

    def test_a_malformed_request_line_is_refused_in_the_frozen_shape(self):
        for label, line in self.MALFORMED_REQUEST_LINES:
            with self.subTest(case=label):
                received = self._quietly(
                    raw_exchange, line + b"Host: h\r\nConnection: close\r\n\r\n"
                )
                self._assert_frozen_refusal(received)
                self.assertEqual(self.written.count("\n"), 1, msg=repr(self.written))

    def test_a_malformed_request_line_never_receives_a_document(self):
        """Not the health document, and not a document of any other kind either.

        The two-field form is the one that mattered most: the inherited writer emits no
        status line for it, so the reply used to be the 108-byte payload on its own -
        a health document that no HTTP client would recognise as a response.
        """
        for label, line in self.MALFORMED_REQUEST_LINES:
            with self.subTest(case=label):
                received = self._quietly(
                    raw_exchange, line + b"Host: h\r\nConnection: close\r\n\r\n"
                )
                self.assertTrue(received.startswith(b"HTTP/1.1 "), msg=repr(received[:80]))
                self.assertNotIn(b'"status"', received)
                self.assertNotIn(app.HEALTH_STATUS.encode(), received)
                self.assertNotIn(b"<html", received.lower())

    def test_a_shaped_but_unsupported_version_is_still_a_505(self):
        """Shape is validated here; MEANING stays with the transport.

        ``HTTP/9.9`` is a well-formed version this server does not support, and the
        distinction is worth an assertion: a validator that rejected it as malformed would
        turn a 505 into a 400 and lose the reason the caller needs.
        """
        received = self._quietly(
            raw_exchange, b"GET /health HTTP/9.9\r\nHost: h\r\nConnection: close\r\n\r\n"
        )
        status_line, _, _ = parse_raw_response(received)
        self.assertEqual(status_line, b"HTTP/1.1 505 HTTP Version Not Supported")

    def test_an_unimplemented_method_is_still_a_405_and_not_a_400(self):
        """Validating the request LINE must not narrow the method policy.

        Dispatch is total by design: any token a request line can carry reaches the 405
        responder.  A validator that only admitted known verbs would answer 400 here and
        break that.
        """
        received = raw_exchange(
            self.port,
            b"FROBNICATE /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        status_line, headers, _ = parse_raw_response(received)
        self.assertEqual(status_line, b"HTTP/1.1 405 Method Not Allowed")
        self.assertEqual(headers["allow"], app.ALLOWED_METHODS)

    def test_an_empty_line_before_the_request_is_ignored(self):
        """RFC 9112 section 2.2, and the finding: the reply used to be nothing at all."""
        for count in (1, 2, app.MAX_LEADING_EMPTY_LINES):
            with self.subTest(empty_lines=count):
                received = raw_exchange(
                    self.port,
                    b"\r\n" * count
                    + b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
                )
                status_line, _, body = parse_raw_response(received)
                self.assertEqual(
                    status_line,
                    b"HTTP/1.1 200 OK",
                    msg="an ignorable empty line must not suppress the answer",
                )
                self.assertEqual(json.loads(body)["status"], app.HEALTH_STATUS)

    def test_a_bare_LF_empty_line_before_the_request_is_ignored_too(self):
        """Both spellings of an empty line, since the transport accepts both."""
        received = raw_exchange(
            self.port,
            b"\n\r\n\n"
            + b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        status_line, _, _ = parse_raw_response(received)
        self.assertEqual(status_line, b"HTTP/1.1 200 OK")

    def test_an_endless_run_of_empty_lines_is_refused_rather_than_absorbed(self):
        """The bound is what stops an empty-line run being a way to hold a thread."""
        received = self._quietly(
            raw_exchange,
            b"\r\n" * (app.MAX_LEADING_EMPTY_LINES + 1)
            + b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        self._assert_frozen_refusal(received)

    def test_empty_lines_and_then_a_hang_up_are_answered_with_silence(self):
        """Nothing was requested, so there is nothing to refuse and nothing to log."""
        received = self._quietly(raw_exchange_with_eof, b"\r\n\r\n")
        self.assertEqual(received, b"", msg=repr(received[:80]))
        self.assertEqual(self.written, "", msg=repr(self.written))

    def test_a_malformed_header_block_is_refused_in_the_frozen_shape(self):
        for label, block in self.MALFORMED_HEADER_BLOCKS:
            with self.subTest(case=label):
                received = self._quietly(
                    raw_exchange, b"GET /health HTTP/1.1\r\n" + block
                )
                self._assert_frozen_refusal(received)
                self.assertEqual(self.written.count("\n"), 1, msg=repr(self.written))

    def test_a_malformed_field_line_is_answered_once_and_never_twice(self):
        """The framing finding, stated as the count that proves it.

        ``X-A : 1`` used to end the header block, so ``Content-Length`` disappeared, the
        drain read nothing, and the body was parsed as a second request line - two
        responses on one connection, which is the desynchronisation a front end and this
        server would resolve differently (CWE-444).  Refusing the block makes the count
        one, whichever order the fields arrive in.
        """
        orderings = (
            b"GET /health HTTP/1.1\r\nHost: h\r\nX-A : 1\r\nContent-Length: 13\r\n\r\n"
            b"GET /nope HTTP/1.1\r\n\r\n",
            b"GET /health HTTP/1.1\r\nHost: h\r\nContent-Length: 13\r\nX-A : 1\r\n\r\n"
            b"GET /nope HTTP/1.1\r\n\r\n",
        )
        for index, request in enumerate(orderings):
            with self.subTest(ordering=index):
                received = self._quietly(raw_exchange, request)
                self.assertEqual(
                    received.count(b"HTTP/1.1 "),
                    1,
                    msg=f"exactly one response, got {received[:200]!r}",
                )
                self._assert_frozen_refusal(received)

    def test_a_continuation_line_after_a_field_line_is_still_accepted(self):
        """Deprecated, and left working, matching User.java rather than index.js.

        Refusing an obs-fold outright is permitted by RFC 9112 section 5.2, but it is a
        behaviour change no finding asked for.  Measured across the three: User.java
        accepts a continuation line and answers the frozen 200, and index.js refuses it
        with the minimal 400 its parser writes for any framing fault.  Accepting is
        therefore the majority behaviour and the conservative one, and index.js records
        its own divergence.  Only the illegal position - first line of the block,
        continuing nothing - is refused here, and all three refuse that.
        """
        received = raw_exchange(
            self.port,
            b"GET /health HTTP/1.1\r\nHost: h\r\nX-A: 1\r\n\tstill-one"
            b"\r\nConnection: close\r\n\r\n",
        )
        status_line, _, _ = parse_raw_response(received)
        self.assertEqual(status_line, b"HTTP/1.1 200 OK")

    def test_a_header_block_that_never_ends_is_refused(self):
        """End of stream is not the end of a header block.

        The inherited parser reads it as one and serves the request, so a request that was
        never finished used to be answered as though it had been.  The JavaScript listener
        refuses it; so does this one now.
        """
        for label, request in (
            ("a request line and one field", b"GET /health HTTP/1.1\r\nHost: h\r\n"),
            ("a request line alone", b"GET /health HTTP/1.1\r\n"),
        ):
            with self.subTest(case=label):
                received = self._quietly(raw_exchange_with_eof, request)
                self._assert_frozen_refusal(received)

    def test_the_transport_header_ceilings_are_unchanged(self):
        """The pre-parse validation must apply the transport's own limits, not new ones.

        A field line one byte past ``_MAXLINE`` and a block one line past ``_MAXHEADERS``
        are both 431, at the same counts as before, because those statuses are part of the
        refusal contract and moving either would move a documented answer.
        """
        oversize = (
            b"GET /health HTTP/1.1\r\nHost: h\r\nX-Filler: "
            + b"b" * app.MAX_HEADER_LINE_BYTES
            + b"\r\n\r\n"
        )
        received = self._quietly(raw_exchange, oversize)
        status_line, _, _ = parse_raw_response(received)
        self.assertEqual(status_line, b"HTTP/1.1 431 Request Header Fields Too Large")

        too_many = (
            b"GET /health HTTP/1.1\r\n"
            + b"".join(
                b"X-Filler-%d: v\r\n" % index for index in range(app.MAX_HEADER_LINES)
            )
            + b"\r\n"
        )
        received = self._quietly(raw_exchange, too_many)
        status_line, _, _ = parse_raw_response(received)
        self.assertEqual(status_line, b"HTTP/1.1 431 Request Header Fields Too Large")

    def test_a_field_block_at_the_ceiling_is_still_served(self):
        """The line one short of the limit must still be a normal request."""
        block = b"".join(
            b"X-Filler-%d: v\r\n" % index for index in range(app.MAX_HEADER_LINES - 2)
        )
        received = raw_exchange(
            self.port,
            b"GET /health HTTP/1.1\r\nConnection: close\r\n" + block + b"\r\n",
        )
        status_line, _, _ = parse_raw_response(received)
        self.assertEqual(status_line, b"HTTP/1.1 200 OK")

    def test_the_raw_target_is_recorded_before_any_rewriting(self):
        """The attribute the route decision now reads, asserted directly.

        A handler that stopped populating it would fall back to ``self.path`` and the
        alias would come back, so the mechanism is worth pinning and not only its effect.
        """
        self.assertEqual(app.HealthRequestHandler.raw_target, "")
        handler = object.__new__(app.HealthRequestHandler)
        handler.raw_requestline = b"GET //health HTTP/1.1\r\n"
        self.assertTrue(handler._accept_request_line(handler.raw_requestline))
        self.assertEqual(handler.raw_target, "//health")
        self.assertNotEqual(app.normalize_path(handler.raw_target), self.route)

    def test_a_validated_request_still_drains_its_body(self):
        """The delegation must leave the socket at the first BODY byte, not past it.

        This is the regression the buffer swap could introduce: if the validated block
        were handed over and the socket left where it was, the drain would re-read the
        header block as a body; if the socket were consumed past the block, the body would
        be lost and read as the next request line.  Both requests go out in ONE write, so
        the body and the following request line are in the same segment - the arrangement
        that makes a mis-positioned stream fail rather than merely be able to.
        """
        received = raw_exchange(
            self.port,
            b"POST /health HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nhello"
            b"GET /health HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n",
        )
        self.assertEqual(received.count(b"HTTP/1.1 "), 2, msg=repr(received[:240]))
        refusal, _, document = received.partition(b"HTTP/1.1 200 OK")
        self.assertIn(b"405 Method Not Allowed", refusal)
        self.assertIn(b'"status":"UP"', document)
        self.assertNotIn(b"400 Bad Request", received)
        self.assertNotIn(b"hello", received)



class TestPreParseDeadline(HealthServerTestCase):
    """A connection that never finishes its request must not hold a thread.

    Every socket read before a request is parsed - the wait for the request line
    and the wait for each header line - was unbounded, so one connection that
    opened and said nothing parked a handler thread for the lifetime of the
    process.  Measured before the deadline existed: a client that sent no bytes at
    all was still connected after thirty seconds, and so was one that sent half a
    header block.  Repeat that and the thread pool is the resource that runs out.

    The deadline is a class attribute, applied by ``setup`` to each accepted
    connection.  These tests shorten it for their own duration rather than waiting
    out the production budget, and restore it on every path; the production number
    itself is asserted separately as a constant.
    """

    #: Prefixes that leave a request incomplete in each of the ways a real
    #: slowloris client does.  The first sends nothing at all.
    PARTIAL_REQUESTS = (
        ("no bytes at all", b""),
        ("a request line and then silence", b"GET /health HTTP/1.1\r\n"),
        ("half a header block", b"GET /health HTTP/1.1\r\nHost: h\r\n"),
        ("a request line without its terminator", b"GET /health HTTP/1.1"),
    )

    @contextlib.contextmanager
    def _short_deadline(self):
        """Shorten the pre-parse deadline for one test, restoring it always."""
        with mock.patch.object(
            app.HealthRequestHandler, "timeout", SHORT_HEADER_BUDGET_SECONDS
        ):
            yield

    def _hold(self, prefix):
        """Open a connection, send ``prefix``, and wait for the server to close it.

        :returns: ``(received bytes, elapsed seconds)``.  A correct server returns
            no bytes: there is no response to a request it never received.
        """
        client = socket.create_connection(
            (LOOPBACK, self.port), timeout=SETTLE_TIMEOUT_SECONDS
        )
        try:
            if prefix:
                client.sendall(prefix)
            began = time.monotonic()
            client.settimeout(SETTLE_TIMEOUT_SECONDS)
            received = read_response(client)
            return received, time.monotonic() - began
        finally:
            client.close()

    def test_an_unfinished_request_is_closed_by_the_deadline(self):
        for label, prefix in self.PARTIAL_REQUESTS:
            with self.subTest(case=label):
                with self._short_deadline():
                    with contextlib.redirect_stderr(io.StringIO()):
                        received, elapsed = self._hold(prefix)
                self.assertEqual(
                    received, b"", msg="a request never received has no response"
                )
                self.assertGreaterEqual(elapsed, SHORT_HEADER_BUDGET_SECONDS * 0.5)
                self.assertLess(
                    elapsed,
                    SETTLE_TIMEOUT_SECONDS,
                    msg="the connection was not closed by the deadline",
                )

    def test_the_deadline_diagnostic_carries_nothing_the_client_sent(self):
        """One line per abandoned connection, and none of it caller-supplied."""
        with self._short_deadline():
            with contextlib.redirect_stderr(io.StringIO()) as sink:
                self._hold(b"GET /health HTTP/1.1\r\nHost: internal.example\r\n")
        written = sink.getvalue()
        self.assertEqual(written.count("\n"), 1, msg=repr(written))
        self.assertIn("timed out", written)
        self.assertNotIn("internal.example", written)
        self.assertNotIn("health", written)

    def test_silent_connections_do_not_accumulate_handler_threads(self):
        """The resource the deadline actually protects, measured directly."""
        baseline = threading.active_count()
        clients = []
        try:
            with self._short_deadline():
                with contextlib.redirect_stderr(io.StringIO()):
                    for _ in range(SILENT_CONNECTION_COUNT):
                        clients.append(
                            socket.create_connection(
                                (LOOPBACK, self.port), timeout=SETTLE_TIMEOUT_SECONDS
                            )
                        )
                    for client in clients:
                        client.settimeout(SETTLE_TIMEOUT_SECONDS)
                        self.assertEqual(
                            read_response(client),
                            b"",
                            msg="every silent connection must be closed by the server",
                        )
                    settled = await_settled(
                        lambda: threading.active_count() <= baseline
                    )
        finally:
            for client in clients:
                client.close()
        self.assertTrue(
            settled,
            msg=f"threads did not settle: {threading.active_count()} > {baseline}",
        )

    def test_the_pre_parse_deadline_is_the_javascript_headers_budget(self):
        """One number, the same behaviour in both implementations.

        Node bounds the same hazard with ``headersTimeout``; Java bounds header
        and body together with ``sun.net.httpserver.maxReqTime``.  The deadline
        must also be shorter than the drain budget, because a request that has not
        arrived is worth less patience than one being delivered slowly.
        """
        self.assertEqual(app.REQUEST_HEADER_TIMEOUT_SECONDS, 10.0)
        self.assertEqual(
            app.HealthRequestHandler.timeout,
            app.REQUEST_HEADER_TIMEOUT_SECONDS,
            msg="the handler must carry the deadline; setup() reads it from here",
        )
        self.assertLess(
            app.REQUEST_HEADER_TIMEOUT_SECONDS, app.REQUEST_DRAIN_TIMEOUT_SECONDS
        )

    def test_the_deadline_does_not_disturb_a_reused_connection(self):
        """A legitimate idle gap between two requests must still be served.

        Run against the PRODUCTION deadline on purpose: a bound set so tightly
        that ordinary connection reuse broke would be a regression dressed as a
        hardening measure.
        """
        client = socket.create_connection(
            (LOOPBACK, self.port), timeout=REQUEST_TIMEOUT_SECONDS
        )
        try:
            request = (
                f"GET {self.route} HTTP/1.1\r\nHost: h\r\n\r\n".encode("ascii")
            )
            client.sendall(request)
            first = read_one_response(client)
            time.sleep(PIPELINE_GAP_SECONDS)
            client.sendall(request)
            second = read_one_response(client)
        finally:
            client.close()
        for reply in (first, second):
            self.assertIn(b"200 OK", reply)
            self.assertIn(b'"status":"UP"', reply)


class TestServerLifecycle(unittest.TestCase):
    """Binding, port validation, and leaving nothing behind on shutdown."""

    def setUp(self):
        # This class drives a live server too, and a server resolves its route from
        # the real environment when it is constructed.  Without this the suite
        # would fail for anyone who happens to have HEALTH_PATH exported - a
        # failure that says nothing about the implementation.
        environment = neutralize_health_environment()
        self.addCleanup(environment.stop)

    def test_create_server_binds_an_ephemeral_port_when_asked_for_zero(self):
        server = app.create_server(
            host=LOOPBACK, port=0, config=defaults_only_config(self)
        )
        self.addCleanup(server.server_close)
        host, port = server.server_address[0], server.server_address[1]
        self.assertEqual(host, LOOPBACK)
        self.assertGreater(port, 0)

    def test_create_server_rejects_an_unusable_port(self):
        """A configuration typo must fail with a message naming the value.

        Fail closed: an orchestrator must never see a success code from a server
        that did not bind.
        """
        config = defaults_only_config(self)
        # ``expected`` is the text the message must carry.  It differs by branch on
        # purpose: a value that fails the grammar is quoted as given, while one that
        # parses but lies outside the range is reported as the number it parsed to.
        cases = (
            ("not-a-port", repr("not-a-port")),
            ("70000", "70000"),
            ("-1", "-1"),
            ("", repr("")),
        )
        for value, expected in cases:
            with self.subTest(value=value):
                with self.assertRaises(ValueError) as raised:
                    app.create_server(host=LOOPBACK, port=value, config=config)
                self.assertIn(expected, str(raised.exception))

    def test_shutdown_releases_the_listening_socket(self):
        """The suite must leave no listener behind for the next test run."""
        config = defaults_only_config(self)
        server = app.create_server(host=LOOPBACK, port=0, config=config)
        port = server.server_address[1]
        thread = threading.Thread(
            target=server.serve_forever, name="health-lifecycle-server", daemon=True
        )
        thread.start()
        try:
            url = f"http://{LOOPBACK}:{port}{app.health_route(config)}"
            with urllib.request.urlopen(
                url, timeout=REQUEST_TIMEOUT_SECONDS
            ) as response:
                self.assertEqual(response.status, 200)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=THREAD_JOIN_TIMEOUT_SECONDS)

        self.assertFalse(thread.is_alive())
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reconnect:
            reconnect.settimeout(REQUEST_TIMEOUT_SECONDS)
            self.assertNotEqual(
                reconnect.connect_ex((LOOPBACK, port)),
                0,
                msg="the listening socket outlived the server",
            )


# Security regressions.  Each test below names a specific way this endpoint could
# be subverted and fails if the defence against it is removed.  They are named
# after what an attacker would achieve rather than after the function under test,
# so a reader can tell at a glance that they are not stylistic assertions.


class TestConfigurationValidation(unittest.TestCase):
    """An unpublishable configuration is refused, not served.

    Without this, ``APP_VERSION=not-a-version`` would be served verbatim inside a
    ``200`` response whose ``status`` field read ``UP``, so the endpoint would
    attest to its own health while describing itself in a form no consumer of the
    frozen contract could parse.
    """

    def config(self, **overrides):
        base = {
            "app.name": EXPECTED_DEFAULTS["app.name"],
            "app.version": EXPECTED_DEFAULTS["app.version"],
            "health.path": EXPECTED_DEFAULTS["health.path"],
            "app.host": LOOPBACK,
            "python.port": "0",
        }
        base.update(overrides)
        return base

    def test_the_shipped_configuration_is_accepted(self):
        """The positive control: this must not become a validator that refuses
        the very configuration the repository ships."""
        app.validate_config(self.config())
        app.validate_config(app.load_config())

    def test_a_malformed_version_is_refused(self):
        for version in ["not-a-version", "1.2", "1.2.3.4", "v1.2.3", "1.2.3-rc1",
                        "1..3", "1.2.", "01.02.03a", "\u0661.\u0662.\u0663"]:
            with self.subTest(version=version):
                with self.assertRaises(ValueError) as raised:
                    app.validate_config(self.config(**{"app.version": version}))
                self.assertIn("app.version", str(raised.exception))

    def test_a_multi_digit_three_part_version_is_accepted(self):
        app.validate_config(self.config(**{"app.version": "10.20.30"}))

    def test_an_empty_or_control_bearing_name_is_refused(self):
        for name in ["", "na\nme", "na\x00me", "na\x7fme"]:
            with self.subTest(name=name):
                with self.assertRaises(ValueError) as raised:
                    app.validate_config(self.config(**{"app.name": name}))
                self.assertIn("app.name", str(raised.exception))

    def test_a_route_that_is_not_a_visible_ascii_path_is_refused(self):
        for path in ["", "/heal th", "/health\r\nX-Injected: 1", "/h\u00e9alth"]:
            with self.subTest(path=path):
                with self.assertRaises(ValueError) as raised:
                    app.validate_config(self.config(**{"health.path": path}))
                self.assertIn("health.path", str(raised.exception))

    def test_a_network_path_reference_is_refused(self):
        """``//health`` is an authority, not a path - RFC 3986 section 4.2.

        The shared rule, and it exists because the three platform servers do NOT
        agree about such a target: CPython's request parser folds an inbound
        ``//health`` down to ``/health`` and the JDK's URI parser resolves it to an
        empty path.  A value every validator accepted but only the Node runtime
        could serve would let one implementation report itself up while its
        siblings answer nothing, so it is refused before a socket is bound.
        """
        for path in ["//health", "///health", "//health/", "//host/health"]:
            with self.subTest(path=path):
                with self.assertRaises(ValueError) as raised:
                    app.validate_config(self.config(**{"health.path": path}))
                self.assertIn("health.path", str(raised.exception))

    def test_a_route_without_a_leading_slash_is_accepted_and_normalised(self):
        """``HEALTH_PATH=healthz`` must behave identically in all three.

        The validator grades the NORMALISED route, not the raw configured value, so
        validation and routing cannot disagree.  Grading the raw value would refuse
        a path with no leading slash while this module's own router supplied that
        slash - refusing to start on a configuration the JavaScript and Java
        implementations serve, and on the one ``.env.example`` documents.
        """
        for configured, expected in [
            ("healthz", "/healthz"),
            ("health", "/health"),
            ("/health/", "/health"),
            ("/health?probe=1", "/health"),
        ]:
            with self.subTest(configured=configured):
                config = self.config(**{"health.path": configured})
                app.validate_config(config)
                self.assertEqual(app.health_route(config), expected)

    def test_a_host_carrying_a_control_character_is_refused(self):
        with self.assertRaises(ValueError) as raised:
            app.validate_config(
                self.config(**{"app.host": "127.0.0.1\n[app.py] forged line"})
            )
        self.assertIn("app.host", str(raised.exception))

    def test_a_rejection_message_cannot_forge_a_log_line(self):
        """CWE-117.  The message quotes a configured value, so a value carrying a
        CR, an LF or a terminal escape must not survive into it."""
        with self.assertRaises(ValueError) as raised:
            app.validate_config(
                self.config(**{"app.host": "127.0.0.1\r\n[app.py] forged\x1b[2J"})
            )
        message = str(raised.exception)
        self.assertNotIn("\n", message)
        self.assertNotIn("\r", message)
        self.assertNotIn("\x1b", message)

    def test_create_server_refuses_before_it_binds(self):
        """Fail closed at creation, not at first request: a server that bound a
        socket and then served an invalid document would have published it."""
        with self.assertRaises(ValueError):
            app.create_server(
                host=LOOPBACK, port=0, config=self.config(**{"app.version": "1.2"})
            )

    def test_the_probe_refuses_an_unpublishable_configuration(self):
        """A probe that accepted a configuration the server refuses would report
        a process healthy that cannot start - the most misleading verdict there
        is."""
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(self.config(**{"app.version": "not-a-version"}))
        self.assertEqual(verdict, 1)
        self.assertIn("probe cannot run", stderr.getvalue())
        self.assertIn("app.version", stderr.getvalue())


class TestPortGrammar(unittest.TestCase):
    """One configured port value, read identically by all three runtimes.

    The proven divergence: ``int()`` honours PEP 515 separators and every Unicode
    decimal digit, so Python bound port 8001 for ``PORT=8_001`` while Node and
    Java refused it and exited non-zero.  One deployment, one value, two
    different ports.
    """

    def test_a_non_ascii_decimal_port_is_refused(self):
        """Refused, AND the message names the offending value and the grammar.

        The exception is what an operator sees when the server refuses to start, so
        a regression that stopped naming the value would leave them reading "invalid
        port" with no indication of which value was invalid.  Asserting only that
        ``ValueError`` was raised cannot detect that.  The value is safe to name
        here precisely because this text is an exception rather than a log line: the
        diagnostics that DO reach stderr name the setting and never quote it, and a
        separate test asserts that.
        """
        for value in ["8_001", "\u0668\u0660\u0660\u0661", "0x50", "8O01",
                      "8001.0", "eight", "1e3", "", "8 001", "0b11"]:
            with self.subTest(value=value):
                with self.assertRaises(ValueError) as raised:
                    app._as_port(value)
                message = str(raised.exception)
                self.assertIn(repr(value), message)
                self.assertIn("ASCII decimal", message)

    def test_an_out_of_range_port_is_refused(self):
        """The range is stated, so the message is actionable without the source."""
        for value in ["-1", "65536", "99999"]:
            with self.subTest(value=value):
                with self.assertRaises(ValueError) as raised:
                    app._as_port(value)
                message = str(raised.exception)
                self.assertIn(value, message)
                self.assertIn("0-65535", message)

    def test_a_plain_ascii_decimal_port_is_accepted(self):
        self.assertEqual(app._as_port("8001"), 8001)
        self.assertEqual(app._as_port("+8001"), 8001)
        self.assertEqual(app._as_port("  8001  "), 8001)
        self.assertEqual(app._as_port("0"), 0)
        self.assertEqual(app._as_port("65535"), 65535)


class StubListener:
    """A one-shot loopback listener that answers with fixed bytes.

    Used to point ``probe`` at a hostile responder without a subprocess and
    without a network.  It binds an ephemeral port, serves every connection it
    accepts on a daemon thread, and closes when the context exits.
    """

    def __init__(self, response):
        self.response = response
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.socket.bind((LOOPBACK, 0))
        self.socket.listen(8)
        self.port = self.socket.getsockname()[1]
        self._thread = threading.Thread(target=self._serve, daemon=True)
        self._running = True
        self._thread.start()

    def _serve(self):
        while self._running:
            try:
                client, _ = self.socket.accept()
            except OSError:
                return
            with client:
                try:
                    client.settimeout(REQUEST_TIMEOUT_SECONDS)
                    client.recv(65536)
                    client.sendall(self.response)
                except OSError:
                    pass

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self._running = False
        self.socket.close()
        self._thread.join(timeout=THREAD_JOIN_TIMEOUT_SECONDS)
        return False


def http_response(body, status=200, content_type="application/json"):
    """Build a complete, correctly framed HTTP response around a body."""
    encoded = body if isinstance(body, bytes) else body.encode("utf-8")
    head = (
        f"HTTP/1.1 {status} Stub\r\n"
        f"Content-Type: {content_type}\r\n"
        f"Content-Length: {len(encoded)}\r\n"
        f"Connection: close\r\n\r\n"
    )
    return head.encode("iso-8859-1") + encoded


class TestProbeHardening(unittest.TestCase):
    """A self-check must PROVE health, not be talked into reporting it.

    Three independent ways to talk one into it converge here.  A verdict taken
    from a substring test grades a truncated body healthy.  A response buffered
    with no ceiling is unbounded work.  And a request sent through ``urllib``'s
    default opener reads proxy settings out of the environment, so an injected
    ``HTTP_PROXY`` can answer on behalf of a process that is not running.
    """

    HEALTHY = json.dumps(
        {
            "name": EXPECTED_DEFAULTS["app.name"],
            "version": EXPECTED_DEFAULTS["app.version"],
            "timestamp": "2026-07-28T13:47:08Z",
            "status": EXPECTED_STATUS,
        },
        separators=(",", ":"),
    )

    def test_a_well_formed_document_is_accepted(self):
        """The positive control for every rejection below."""
        self.assertIsNone(app.probe_rejection(200, self.HEALTHY.encode("utf-8")))

    def test_a_truncated_body_quoting_the_healthy_fragment_is_refused(self):
        """The exact fail-open the substring test allowed: this is not JSON at
        all, yet ``body.contains('"status":"UP"')`` is true of it."""
        self.assertIsNotNone(app.probe_rejection(200, b'{"status":"UP"'))

    def test_a_body_that_says_down_while_quoting_up_is_refused(self):
        document = json.dumps(
            {
                "name": '{"status":"UP"}',
                "version": "1.1.0",
                "timestamp": "2026-07-28T13:47:08Z",
                "status": "DOWN",
            },
            separators=(",", ":"),
        )
        self.assertIsNotNone(app.probe_rejection(200, document.encode("utf-8")))

    def test_a_document_with_the_wrong_key_set_or_order_is_refused(self):
        extra = '{"name":"n","version":"1.1.0","timestamp":"2026-07-28T13:47:08Z"' \
                ',"status":"UP","extra":"x"}'
        reordered = '{"status":"UP","name":"n","version":"1.1.0"' \
                    ',"timestamp":"2026-07-28T13:47:08Z"}'
        missing = '{"name":"n","version":"1.1.0","status":"UP"}'
        for body in (extra, reordered, missing):
            with self.subTest(body=body):
                self.assertIsNotNone(app.probe_rejection(200, body.encode("utf-8")))

    def test_the_key_set_reason_is_worded_exactly_as_the_other_two_word_it(self):
        """The reason strings are part of the shared contract: an operator greps one
        deployment's logs, not one language's.  This is the easiest reason in the
        set to drift, because every language has a different natural way to print a
        list - a Python repr uses apostrophes and spaces, Java's ``List.toString``
        drops the quotes entirely - so the byte-exact form is pinned here and in the
        other two harnesses rather than left to whichever renderer is nearest."""
        body = b'{"name":"n","version":"1.1.0","timestamp":"2026-07-28T13:47:08Z"}'
        self.assertEqual(
            app.probe_rejection(200, body),
            'body does not carry exactly the keys '
            '["name","version","timestamp","status"] in order',
        )

    def test_a_repeated_key_is_refused(self):
        """``json.loads`` keeps the last value silently, which turns a
        contradictory document into a plausible one.  The Java reader refuses it,
        so this one must too."""
        body = ('{"name":"n","version":"1.1.0","timestamp":"2026-07-28T13:47:08Z"'
                ',"status":"DOWN","status":"UP"}')
        self.assertIsNotNone(app.probe_rejection(200, body.encode("utf-8")))

    def test_a_malformed_field_value_is_refused(self):
        for field, value in [("version", "not-a-version"), ("version", "1.2"),
                             ("timestamp", "2026-07-28 13:47:08"),
                             ("timestamp", "2026-07-28T13:47:08.123Z"),
                             ("name", ""), ("status", "DOWN")]:
            with self.subTest(field=field, value=value):
                document = json.loads(self.HEALTHY)
                document[field] = value
                body = json.dumps(document, separators=(",", ":")).encode("utf-8")
                self.assertIsNotNone(app.probe_rejection(200, body))

    def test_a_non_object_or_non_string_document_is_refused(self):
        for body in [b"[]", b'"UP"', b"42", b"null",
                     b'{"name":{"n":"x"},"version":"1.1.0"'
                     b',"timestamp":"2026-07-28T13:47:08Z","status":"UP"}',
                     self.HEALTHY.encode("utf-8") + b"trailing"]:
            with self.subTest(body=body):
                self.assertIsNotNone(app.probe_rejection(200, body))

    def test_a_non_200_status_is_refused(self):
        for status in (204, 302, 404, 500):
            with self.subTest(status=status):
                self.assertIsNotNone(
                    app.probe_rejection(status, self.HEALTHY.encode("utf-8"))
                )

    def test_a_body_over_the_ceiling_is_refused(self):
        oversized = b"x" * (app.MAX_PROBE_BODY_BYTES + 1)
        reason = app.probe_rejection(200, oversized)
        self.assertIsNotNone(reason)
        self.assertIn(str(app.MAX_PROBE_BODY_BYTES), reason)

    def test_the_probe_stops_reading_an_endless_response(self):
        """End to end over a socket: a responder that declares a huge body is
        refused on size rather than buffered."""
        padded = json.dumps(
            {
                "name": EXPECTED_DEFAULTS["app.name"] + "y" * (
                    app.MAX_PROBE_BODY_BYTES * 2
                ),
                "version": EXPECTED_DEFAULTS["app.version"],
                "timestamp": "2026-07-28T13:47:08Z",
                "status": EXPECTED_STATUS,
            },
            separators=(",", ":"),
        )
        with StubListener(http_response(padded)) as stub:
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                verdict = app.probe(self._config_for(stub.port))
        self.assertEqual(verdict, 1)
        self.assertIn(str(app.MAX_PROBE_BODY_BYTES), stderr.getvalue())

    def test_the_probe_refuses_a_stub_that_only_looks_healthy(self):
        with StubListener(http_response('{"status":"UP"')) as stub:
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                verdict = app.probe(self._config_for(stub.port))
        self.assertEqual(verdict, 1)
        self.assertIn("probe rejected", stderr.getvalue())

    def test_the_probe_accepts_a_stub_that_serves_the_real_contract(self):
        """The end-to-end positive control: the socket path, the bounded read and
        the document check all have to work together for this to pass."""
        with StubListener(http_response(self.HEALTHY)) as stub:
            self.assertEqual(app.probe(self._config_for(stub.port)), 0)

    def test_an_injected_proxy_cannot_answer_for_the_endpoint(self):
        """The proven exploit: with a dead target port and a local ``HTTP_PROXY``
        serving a fabricated healthy document, the probe returned 0 without ever
        touching the process it was meant to be checking."""
        dead_port = unused_port()
        with StubListener(http_response(self.HEALTHY)) as proxy:
            proxy_url = f"http://{LOOPBACK}:{proxy.port}"
            injected = {
                "http_proxy": proxy_url,
                "HTTP_PROXY": proxy_url,
                "all_proxy": proxy_url,
                "ALL_PROXY": proxy_url,
                "no_proxy": "",
                "NO_PROXY": "",
            }
            stderr = io.StringIO()
            with mock.patch.dict(os.environ, injected), contextlib.redirect_stderr(
                stderr
            ):
                verdict = app.probe(self._config_for(dead_port))
        self.assertEqual(verdict, 1)
        self.assertIn("could not reach", stderr.getvalue())

    def test_a_diagnostic_cannot_be_forged_through_a_configured_value(self):
        """CWE-117 on the probe path.  Every configured value that reaches the
        diagnostic is either refused by validation or sanitised, so a single call
        can never emit two lines."""
        stderr = io.StringIO()
        config = self._config_for(unused_port())
        config["app.host"] = "127.0.0.1\n[app.py] FORGED"
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(config)
        self.assertEqual(verdict, 1)
        self.assertEqual(len(stderr.getvalue().strip().splitlines()), 1)
        self.assertNotIn("FORGED\n", stderr.getvalue())

    def _config_for(self, port):
        return {
            "app.name": EXPECTED_DEFAULTS["app.name"],
            "app.version": EXPECTED_DEFAULTS["app.version"],
            "health.path": EXPECTED_DEFAULTS["health.path"],
            "app.host": LOOPBACK,
            "python.port": str(port),
        }


# Probe identity.  ``probe_rejection`` proves an answer satisfies the frozen
# contract - which is exactly what ANY conforming implementation would serve.  On its
# own it therefore grades a different application holding this loopback port healthy
# and reports this one up while it is down, and because --probe is the container
# health check, that verdict keeps a dead container in service.
# ``identity_rejection`` is the step that closes it: the media type must be ours and
# the two identity fields must be ours.  Every rule is asserted directly AND end to
# end over a socket, because a rule that holds only in a unit call is a rule the
# transport can still fail to apply.


class TestProbeIdentity(unittest.TestCase):
    """An answer proves THIS application's health, or it proves nothing."""

    NAME = EXPECTED_DEFAULTS["app.name"]
    VERSION = EXPECTED_DEFAULTS["app.version"]

    #: The three reasons, written out rather than read from the module, so this is a
    #: gate on the shared wording and not a mirror of it.  All three
    #: implementations emit these bytes after their own log prefix.
    MEDIA_TYPE_REASON = "the answer is not served as application/json"
    NAME_REASON = "the name field is not this application's name"
    VERSION_REASON = "the version field is not this application's version"

    def _document(self, name=None, version=None):
        """Render a conforming health document carrying a stated identity."""
        return json.dumps(
            {
                "name": self.NAME if name is None else name,
                "version": self.VERSION if version is None else version,
                "timestamp": "2026-07-28T13:47:08Z",
                "status": EXPECTED_STATUS,
            },
            separators=(",", ":"),
        ).encode("utf-8")

    def _config_for(self, port, name=None, version=None):
        return {
            "app.name": self.NAME if name is None else name,
            "app.version": self.VERSION if version is None else version,
            "health.path": EXPECTED_DEFAULTS["health.path"],
            "app.host": LOOPBACK,
            "python.port": str(port),
        }

    def _reject(self, content_types, body=None):
        """Grade an answer against the default identity."""
        return app.identity_rejection(
            content_types,
            self._document() if body is None else body,
            self.NAME,
            self.VERSION,
        )

    def test_the_sole_media_type_of_one_value_strips_parameters_and_folds_case(self):
        """RFC 9110 sections 8.3, 8.3.1 and 5.6.2, one row each."""
        accepted = {
            "application/json": "application/json",
            "application/json; charset=utf-8": "application/json",
            "application/json;charset=UTF-8": "application/json",
            "APPLICATION/JSON": "application/json",
            "  application/json  ": "application/json",
            "text/html": "text/html",
        }
        for value, expected in accepted.items():
            with self.subTest(value=value):
                self.assertEqual(app.sole_media_type([value]), expected)

    def test_no_media_type_and_more_than_one_both_reduce_to_nothing(self):
        """The rule that keeps the three implementations in step.

        Their clients disagree about a REPEATED ``Content-Type``: this one joins the
        values with ``", "``, Node keeps the first and discards the rest, and the
        JDK exposes every one.  Grading whichever value a client surfaced would let
        one implementation accept a duplicate the other two refused, so every
        answer that does not name exactly one media type reduces to ``""``.
        """
        for values in (
            (),
            None,
            ["application/json", "text/html"],
            ["text/html", "application/json"],
            ["application/json", "application/json"],
            [None],
            [42],
        ):
            with self.subTest(values=values):
                self.assertEqual(app.sole_media_type(values), "")

    def test_our_own_document_served_as_json_is_accepted(self):
        """The positive control for every rejection below."""
        self.assertIsNone(self._reject(["application/json"]))
        self.assertIsNone(self._reject(["application/json; charset=utf-8"]))

    def test_a_conforming_document_served_as_another_media_type_is_refused(self):
        for value in ("text/html", "text/plain", "application/health+json", ""):
            with self.subTest(value=value):
                self.assertEqual(self._reject([value]), self.MEDIA_TYPE_REASON)

    def test_a_conforming_document_with_no_media_type_at_all_is_refused(self):
        self.assertEqual(self._reject([]), self.MEDIA_TYPE_REASON)
        self.assertEqual(self._reject(None), self.MEDIA_TYPE_REASON)

    def test_another_application_s_name_is_refused(self):
        for name in ("IMPOSTOR", "", self.NAME + "x", self.NAME.upper(), " " + self.NAME):
            with self.subTest(name=name):
                self.assertEqual(
                    self._reject(["application/json"], self._document(name=name)),
                    self.NAME_REASON,
                )

    def test_another_version_of_this_application_is_refused(self):
        """A rolling deployment is the case that matters: the answer is a valid
        health document from the same codebase at a different version, so only an
        exact comparison can tell it apart from this process's own answer."""
        for version in ("9.9.9", "1.1.1", "1.2.0", "0.1.1"):
            with self.subTest(version=version):
                self.assertEqual(
                    self._reject(["application/json"], self._document(version=version)),
                    self.VERSION_REASON,
                )

    def test_the_media_type_is_graded_before_the_identity(self):
        """The order is part of the contract, so it is asserted rather than
        assumed: with the framing and both identity fields wrong at once, the
        framing is what gets reported."""
        wrong = self._document(name="IMPOSTOR", version="9.9.9")
        self.assertEqual(self._reject(["text/html"], wrong), self.MEDIA_TYPE_REASON)
        self.assertEqual(self._reject(["application/json"], wrong), self.NAME_REASON)

    def test_the_name_is_graded_before_the_version(self):
        wrong = self._document(name="IMPOSTOR", version="9.9.9")
        self.assertEqual(self._reject(["application/json"], wrong), self.NAME_REASON)

    def test_no_reason_echoes_a_value_the_answer_supplied(self):
        """A response body is an input, and an input reaching a log line verbatim
        is how a forged entry gets written."""
        planted = "QaW002IdentityMarker"
        bodies = (
            self._document(name=planted),
            self._document(version=planted),
        )
        for body in bodies:
            for values in (["application/json"], [planted]):
                with self.subTest(values=values):
                    reason = self._reject(values, body)
                    self.assertIsNotNone(reason)
                    self.assertNotIn(planted, reason)

    def test_a_body_that_is_not_a_document_fails_closed_on_a_direct_call(self):
        """Unreachable through ``probe``, which grades shape first, and asserted
        anyway: this function is exported, so it has to be total."""
        self.assertIsNotNone(self._reject(["application/json"], b'{"status":"UP"'))
        self.assertIsNotNone(self._reject(["application/json"], b"[]"))
        self.assertIsNotNone(self._reject(["application/json"], b"null"))
        self.assertIsNotNone(self._reject(["application/json"], b""))
        self.assertIsNotNone(
            self._reject(["application/json"], b'{"name":' + b"\xc3\x28" + b"}")
        )

    def test_a_decoy_serving_a_conforming_document_is_refused_end_to_end(self):
        """The finding itself, over a real socket: a well-formed health document
        from something that is not this application, on this application's port."""
        cases = (
            (self._document(name="IMPOSTOR"), "application/json", self.NAME_REASON),
            (self._document(version="9.9.9"), "application/json", self.VERSION_REASON),
            (self._document(), "text/html", self.MEDIA_TYPE_REASON),
        )
        for body, content_type, expected in cases:
            with self.subTest(content_type=content_type):
                response = http_response(body, content_type=content_type)
                with StubListener(response) as stub:
                    stderr = io.StringIO()
                    stdout = io.StringIO()
                    with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(
                        stdout
                    ):
                        verdict = app.probe(self._config_for(stub.port))
                written = stderr.getvalue()
                self.assertEqual(verdict, 1)
                self.assertEqual(stdout.getvalue(), "")
                self.assertEqual(written, f"[app.py] probe rejected: {expected}\n")

    def test_the_real_contract_is_still_healthy_and_silent_end_to_end(self):
        """The end-to-end positive control: the decoys above must fail because of
        what they served, not because the identity step refuses everything."""
        with StubListener(http_response(self._document())) as stub:
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                verdict = app.probe(self._config_for(stub.port))
        self.assertEqual(verdict, 0, msg=stderr.getvalue())
        self.assertEqual(stderr.getvalue(), "")

    def test_a_deployment_that_renames_itself_still_probes_itself_healthy(self):
        """Identity is compared against the CONFIGURATION, not against a literal,
        so an overridden name and version are what the probe then requires."""
        renamed = self._document(name="renamed-service", version="4.5.6")
        with StubListener(http_response(renamed)) as stub:
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                verdict = app.probe(
                    self._config_for(
                        stub.port, name="renamed-service", version="4.5.6"
                    )
                )
        self.assertEqual(verdict, 0, msg=stderr.getvalue())
        self.assertEqual(stderr.getvalue(), "")


# The probe body ceiling, from the operator's side.  F-15: the ceiling is what stops
# an endless stream, so it is deliberately not raised - which means a long enough
# app.name makes this application's OWN healthy answer too large to read and the
# probe fail closed on a working process.  The budget is documented in
# app.config.properties and .env.example, and these tests are what keep the
# documented arithmetic true.


class TestProbeCeilingBudget(unittest.TestCase):
    """The documented app.name budget is the measured one."""

    #: Bytes of the rendered document that are neither the name nor the version:
    #: the four keys, the punctuation, the 20-character instant and the status.
    FIXED_OVERHEAD_BYTES = 73

    def _rendered(self, name, version):
        """The bytes this application would put on the wire for that identity."""
        payload = app.build_payload({"app.name": name, "app.version": version})
        return app.render_payload(payload).encode("utf-8")

    def _rendered_length(self, name, version):
        return len(self._rendered(name, version))

    def test_the_documented_overhead_is_the_measured_overhead(self):
        name = EXPECTED_DEFAULTS["app.name"]
        version = EXPECTED_DEFAULTS["app.version"]
        self.assertEqual(
            self._rendered_length(name, version) - len(name) - len(version),
            self.FIXED_OVERHEAD_BYTES,
        )
        self.assertEqual(self._rendered_length(name, version), REFERENCE_BODY_LENGTH)

    def test_the_budget_is_exact_on_both_sides(self):
        """The largest name whose own answer the probe can still read, and the
        first one it cannot."""
        version = EXPECTED_DEFAULTS["app.version"]
        budget = PROBE_BODY_CEILING - self.FIXED_OVERHEAD_BYTES - len(version)
        self.assertEqual(self._rendered_length("a" * budget, version), PROBE_BODY_CEILING)
        self.assertEqual(
            self._rendered_length("a" * (budget + 1), version), PROBE_BODY_CEILING + 1
        )
        self.assertIsNone(
            app.probe_rejection(200, app.render_payload(
                app.build_payload({"app.name": "a" * budget, "app.version": version})
            ).encode("utf-8"))
        )
        over = app.render_payload(
            app.build_payload({"app.name": "a" * (budget + 1), "app.version": version})
        ).encode("utf-8")
        self.assertIn("exceeds the probe limit", app.probe_rejection(200, over))

    def test_the_budget_counts_bytes_and_not_characters(self):
        """An operator setting a name in an astral script spends four bytes per
        character, which is the part of the budget a character count would miss."""
        version = EXPECTED_DEFAULTS["app.version"]
        budget = PROBE_BODY_CEILING - self.FIXED_OVERHEAD_BYTES - len(version)
        fitting = "\U0001f600" * (budget // 4)
        # One astral character is four bytes of UTF-8, so the largest whole-character
        # name that fits is 2028 characters and 8112 bytes, not 8114 of either.
        self.assertEqual(len(fitting), budget // 4)
        self.assertEqual(len(fitting.encode("utf-8")), 4 * len(fitting))
        rendered = self._rendered_length(fitting, version)
        self.assertLessEqual(rendered, PROBE_BODY_CEILING)
        self.assertGreater(rendered, PROBE_BODY_CEILING - 4)
        self.assertIsNone(app.probe_rejection(200, self._rendered(fitting, version)))
        # One character more crosses the ceiling four bytes at a time.
        over = fitting + "\U0001f600"
        self.assertGreater(self._rendered_length(over, version), PROBE_BODY_CEILING)
        self.assertIn(
            "exceeds the probe limit",
            app.probe_rejection(200, self._rendered(over, version)),
        )
        # The same character count in ASCII is nowhere near the ceiling, which is
        # exactly the difference a character-counted budget would hide.
        self.assertLess(self._rendered_length("a" * len(fitting), version), 4000)

    def test_the_operator_documentation_states_the_measured_numbers(self):
        """The drift detector.  Two files tell an operator what the budget is; if
        the arithmetic above changes, they must change with it."""
        version = EXPECTED_DEFAULTS["app.version"]
        name_budget = PROBE_BODY_CEILING - self.FIXED_OVERHEAD_BYTES - len(version)
        pair_budget = PROBE_BODY_CEILING - self.FIXED_OVERHEAD_BYTES
        here = os.path.dirname(os.path.abspath(__file__))
        for filename in ("app.config.properties", ".env.example"):
            with self.subTest(filename=filename):
                with open(os.path.join(here, filename), encoding="utf-8") as handle:
                    text = handle.read()
                for number in (
                    str(PROBE_BODY_CEILING),
                    str(self.FIXED_OVERHEAD_BYTES),
                    str(name_budget),
                    str(pair_budget),
                ):
                    self.assertIn(number, text, msg=f"{filename} omits {number}")


if __name__ == "__main__":
    unittest.main()
