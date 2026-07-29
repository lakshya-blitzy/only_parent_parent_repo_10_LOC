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
        """Run the current interpreter with ``arguments`` in app.py's directory."""
        completed = subprocess.run(
            [sys.executable, *arguments],
            cwd=APP_DIRECTORY,
            capture_output=True,
            text=True,
            timeout=SUBPROCESS_TIMEOUT_SECONDS,
            check=False,
        )
        return completed

    def test_importing_app_writes_nothing_to_stdout(self):
        """Importing the module must have no observable side effect.

        The greeting lives behind the ``__main__`` guard.  If it ever escaped
        that guard, importing ``app`` - as this very test file does, and as any
        other consumer would - would print to standard output.
        """
        completed = self._run("-c", "import app")
        self.assertEqual(completed.returncode, 0, msg=completed.stderr)
        self.assertEqual(completed.stdout, "")
        self.assertNotIn("Hello", completed.stderr)

    def test_importing_app_exposes_the_public_surface(self):
        """A consumer that imports the module can reach every documented name."""
        expected = (
            "greet",
            "read_properties",
            "config_value",
            "load_config",
            "normalize_path",
            "health_route",
            "health_timestamp",
            "build_payload",
            "render_payload",
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
        self.assertEqual(completed.returncode, 0, msg=completed.stderr)
        self.assertEqual(completed.stdout, "Hello Lakshya\n")
        self.assertEqual(len(completed.stdout.encode("utf-8")), 14)

    def test_default_invocation_writes_nothing_to_stderr(self):
        """The default mode is silent apart from its one line of output."""
        completed = self._run("app.py")
        self.assertEqual(completed.stderr, "")

    def test_unrecognised_flag_falls_back_to_the_default_mode(self):
        """An unknown argument must not change the legacy behaviour.

        The dispatcher recognises ``--serve`` and ``--probe`` and treats
        everything else as the default invocation, so a stray argument prints the
        greeting instead of starting a listener or failing.
        """
        completed = self._run("app.py", "--not-a-real-flag")
        self.assertEqual(completed.returncode, 0, msg=completed.stderr)
        self.assertEqual(completed.stdout, "Hello Lakshya\n")


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
        """A probe that cannot prove health must report unhealthy."""
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(self._config_for(LOOPBACK, unused_port()))
        self.assertEqual(verdict, 1)
        self.assertIn("unreachable", stderr.getvalue())

    def test_the_probe_fails_closed_on_a_non_health_route(self):
        """Pointed at a path that answers 404, the verdict is unhealthy."""
        stderr = io.StringIO()
        config = self._config_for(LOOPBACK, self.port)
        config["health.path"] = "/not-the-health-route"
        with contextlib.redirect_stderr(stderr):
            verdict = app.probe(config)
        self.assertEqual(verdict, 1)
        self.assertIn("404", stderr.getvalue())


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


if __name__ == "__main__":
    unittest.main()

