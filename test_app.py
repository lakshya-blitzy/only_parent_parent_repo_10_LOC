"""Unit tests for app.py - the preserved legacy behaviour and the /health endpoint.

Run them with the standard-library runner, from the repository root:

    python -m unittest test_app.py -v
    python -m unittest discover -s . -p "test_*.py" -v

This file is a flat sibling of ``app.py`` rather than a member of a ``tests/``
package, matching the repository's flat layout, and it imports nothing outside
the Python standard library plus ``app`` itself.  That keeps the zero-dependency
property of the project intact for its tests as well as for its application
code: there is no pytest, no coverage tool and no lockfile to install before
these assertions can run.

What is asserted here, and why each part matters:

Preserved behaviour
    ``greet`` and the default invocation of ``app.py`` are the program's
    behaviour as it existed before the health endpoint was added.  Both are
    pinned byte-exactly, so a future change to either fails here rather than
    surfacing as a broken downstream consumer.

The frozen response contract
    Field names, field ORDER, the ``UP`` literal, the compact serialisation and
    the 108-byte reference body length are asserted as constants written out in
    full rather than read back from ``app``.  A test that imports the value it
    is checking cannot detect a change to that value; these tests can.

Configuration precedence
    Environment variable, then properties file, then built-in default - proven
    through ``load_config``'s injectable ``env`` and ``path`` parameters.  The
    real ``os.environ`` is never mutated by these tests, so they neither depend
    on nor disturb the environment they run in.

Routing and least disclosure
    A real ``HealthServer`` is bound to an EPHEMERAL loopback port and driven
    over HTTP.  No port number is hard-coded, so a developer already running
    ``python app.py --serve`` on port 8000 cannot make this suite fail.  The
    absence of the ``Server`` and ``Date`` headers is asserted explicitly: the
    implementation suppresses them deliberately, and that is exactly the kind of
    property a later refactor towards ``send_response`` would silently undo.

Two rules govern the assertions themselves.

The timestamp is asserted by FORMAT and never by value.  It is the only
non-deterministic field in the payload, and comparing it to a computed instant
would make this suite fail for reasons unrelated to correctness.  No assertion
in this file compares a timestamp, a duration or an elapsed time to anything.

Every header assertion is case-insensitive.  RFC 9110 makes field names
case-insensitive, and the sibling Java implementation normalises their casing,
so matching case-insensitively is what keeps the three language suites
asserting the same contract rather than three dialects of it.
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

# --------------------------------------------------------------------------- #
# The frozen contract, written out in full.
#
# These literals are deliberately NOT imported from app: a test that reads its
# expectation out of the module under test can never fail when that module
# changes.  Duplication here is the point - it is what makes these constants a
# gate rather than a mirror.
# --------------------------------------------------------------------------- #

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

#: Short by design.  A hung request must fail the test quickly instead of
#: stalling a CI job until the job-level timeout fires.
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


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #


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


def padded_document(length):
    """Return a VALID healthy health document padded to exactly ``length`` bytes.

    Used to probe the body ceiling from both sides.  The padding goes in the
    ``name`` field, so the document stays a well-formed JSON object reporting the
    healthy status and the only thing under test is its length.
    """
    prefix = b'{"name":"'
    suffix = b'","version":"1.1.0","timestamp":"2026-01-01T00:00:00Z","status":"UP"}'
    padding = length - len(prefix) - len(suffix)
    if padding < 0:
        raise ValueError(f"{length} is shorter than the smallest valid document")
    return prefix + b"a" * padding + suffix


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

        :param head: the response head to send, if any.
        :param body: the response body to send after the head.
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


def neutralize_health_environment():
    """Remove every health-related variable from ``os.environ``, restorably.

    The request handler resolves its configuration per request, from the real
    process environment and the committed properties file.  A test that drives a
    live server therefore cannot hand it an injected mapping the way the loader
    tests do: the ambient variables have to be taken out of the way instead, or a
    developer with ``HEALTH_PATH`` exported would see this suite fail against a
    perfectly correct implementation.

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


# --------------------------------------------------------------------------- #
# Preserved legacy behaviour
#
# These tests are the backward-compatibility contract.  The health endpoint was
# added on the explicit condition that nothing already working changed, so the
# original greeting function and the default invocation of the script are pinned
# here byte-exactly.
# --------------------------------------------------------------------------- #


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
            "read_properties",
            "config_value",
            "load_config",
            "normalize_path",
            "strip_authority",
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


# --------------------------------------------------------------------------- #
# Configuration
#
# Every test in this section injects its own environment mapping and its own
# properties path.  os.environ is never written to: these tests must not depend
# on the environment they run in, and must not leave it altered for the tests
# that follow.
# --------------------------------------------------------------------------- #


class TestReadProperties(unittest.TestCase):
    """Parsing of the Java-native ``key=value`` configuration file."""

    def test_reads_keys_and_values(self):
        path = write_properties(self, "app.name=configured\napp.version=4.5.6\n")
        self.assertEqual(
            app.read_properties(path),
            {"app.name": "configured", "app.version": "4.5.6"},
        )

    def test_ignores_comments_blank_lines_and_lines_without_a_separator(self):
        """Comment markers are ``#`` and ``!``, matching java.util.Properties."""
        text = (
            "# a hash comment\n"
            "! a bang comment\n"
            "\n"
            "   \n"
            "a line with no equals sign\n"
            "app.name=kept\n"
        )
        path = write_properties(self, text)
        self.assertEqual(app.read_properties(path), {"app.name": "kept"})

    def test_splits_on_the_first_separator_only(self):
        """A value may itself contain ``=``; surrounding whitespace is stripped."""
        path = write_properties(self, "app.name = a=b=c \n")
        self.assertEqual(app.read_properties(path), {"app.name": "a=b=c"})

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
        beginning; these two did not, so the same request reached the route on one
        implementation and returned 404 on the other two.
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


# --------------------------------------------------------------------------- #
# The health document
#
# The payload is built from a defaults-only configuration so that these
# assertions are identical on a developer's machine, in CI and inside a
# container, whatever the environment or the properties file happens to hold.
# --------------------------------------------------------------------------- #


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
        regression before it can reach the cross-language verification script.
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


# --------------------------------------------------------------------------- #
# The live endpoint
#
# These tests drive a real HealthServer over a real socket, which is the only
# way to assert the parts of the contract that are properties of the response
# rather than of the payload: the status codes, the header set, and the two
# headers that must NOT be there.
# --------------------------------------------------------------------------- #


class HealthServerTestCase(unittest.TestCase):
    """Base class: a HealthServer bound to an ephemeral loopback port.

    Port 0 lets the kernel choose the port, which is then read back from the
    bound socket.  Nothing here hard-codes 8000, so the suite passes while a
    developer has ``python app.py --serve`` running on the configured port.

    The handler resolves configuration per request from the process environment
    and the committed properties file, so it cannot be given an injected mapping
    the way the loader tests do.  The health-related variables are therefore
    removed from ``os.environ`` for the lifetime of the class - through
    ``patch.dict``, which snapshots the whole mapping and restores it even if a
    test fails - leaving the committed file as the effective configuration.  No
    other variable is touched, and nothing is added.
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
    """The in-process self-check used as the container HEALTHCHECK."""

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
        substitution the container health check could never reach its own
        endpoint.
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

        ``app.host`` is an input.  If the probe honoured it, a configured value
        pointing off the machine would turn the container health check into an
        outbound HTTP client - reporting this application healthy because some
        other host answered.  The live endpoint here is on loopback and nothing is
        listening on the named host, so a verdict of healthy is only possible if
        loopback was dialled.
        """
        stderr = io.StringIO()
        config = self._config_for("monitoring.example.com", self.port)
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(config)
        written = stderr.getvalue()
        self.assertEqual(verdict, 0)
        self.assertIn("not loopback", written)
        self.assertNotIn("example.com", written)


# --------------------------------------------------------------------------- #
# Diagnostic safety
#
# Everything this module says about itself goes to stderr, and every line of it
# is a fixed category.  These tests are the gate on that: a configured value or
# an exception string reaching a log line would both disclose the deployment and
# - for any value carrying a CR or an LF - let a caller forge log entries.
# --------------------------------------------------------------------------- #


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
        """The reproduced log-forgery case, asserted as fixed.

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


# --------------------------------------------------------------------------- #
# Probe destination allowlist
# --------------------------------------------------------------------------- #


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


# --------------------------------------------------------------------------- #
# Probe bounds
#
# The probe is a client, and a client is only as safe as its behaviour against a
# peer that does not cooperate.  Each test here points it at a deliberately
# broken endpoint: one that answers forever, one that never answers, one that
# answers with far too much, and one that answers with something that merely
# looks right.  Every one of them must end in a bounded, unhealthy verdict.
# --------------------------------------------------------------------------- #


class TestProbeBounds(unittest.TestCase):
    """Bounded in time, bounded in bytes, and strict about the document."""

    def _probe(self, port, path="/health", host=LOOPBACK):
        """Probe ``port`` and return ``(verdict, stderr)``."""
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(
                {"app.host": host, "python.port": str(port), "health.path": path}
            )
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
        """Both sides of the limit, so an off-by-one cannot hide in either."""
        at_limit = padded_document(PROBE_BODY_CEILING)
        accepted = hostile_endpoint(
            self, head=json_head(len(at_limit)), body=at_limit
        )
        verdict, written = self._probe(accepted.port)
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
        verdict, written = self._probe(endpoint.port)
        self.assertEqual(verdict, 0, msg=written)
        self.assertEqual(written, "")


# --------------------------------------------------------------------------- #


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


class TestServerLifecycle(unittest.TestCase):
    """Binding, port validation, and leaving nothing behind on shutdown."""

    def setUp(self):
        # This class drives a live server too, and the handler resolves its route
        # from the real environment on every request.  Without this the suite
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
        for value in ("not-a-port", "70000", "-1", ""):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    app.create_server(host=LOOPBACK, port=value, config=config)

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


# --------------------------------------------------------------------------- #
# Security regressions
#
# Every test below reproduces something this endpoint once permitted, and each
# one fails if the defence is removed.  They are named after what an attacker
# would have achieved rather than after the function under test, so that a future
# reader can tell at a glance that they are not stylistic assertions.
# --------------------------------------------------------------------------- #


class TestConfigurationValidation(unittest.TestCase):
    """An unpublishable configuration is refused, not served.

    The proven defect: ``APP_VERSION=not-a-version`` was served verbatim inside a
    ``200`` response whose ``status`` field read ``UP``, so the endpoint attested
    to its own health while describing itself in a form no consumer of the frozen
    contract could parse.
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
        for path in ["health", "", "/heal th", "/health\r\nX-Injected: 1", "/h\u00e9alth"]:
            with self.subTest(path=path):
                with self.assertRaises(ValueError) as raised:
                    app.validate_config(self.config(**{"health.path": path}))
                self.assertIn("health.path", str(raised.exception))

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
        for value in ["8_001", "\u0668\u0660\u0660\u0661", "0x50", "8O01",
                      "8001.0", "eight", "1e3", "", "8 001", "0b11"]:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    app._as_port(value)

    def test_an_out_of_range_port_is_refused(self):
        for value in ["-1", "65536", "99999"]:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    app._as_port(value)

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

    Three separate defects converge here.  The verdict was taken from a substring
    test, so a truncated body reported healthy.  The whole response was buffered
    with no ceiling.  And the request went through ``urllib``'s default opener,
    which reads proxy settings out of the environment - so an injected
    ``HTTP_PROXY`` could answer on behalf of a process that was not running.
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


if __name__ == "__main__":
    unittest.main()
