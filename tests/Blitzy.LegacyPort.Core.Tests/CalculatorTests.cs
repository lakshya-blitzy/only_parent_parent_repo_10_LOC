using Blitzy.LegacyPort.Core;

namespace Blitzy.LegacyPort.Core.Tests;

// Unit-level behavior net for the integer arithmetic ported out of the legacy JavaScript script
// index.js, whose sole reusable declaration was: function add(a, b) { return a + b; }
//
// The migration that produced this file replaced three single-file scripts, written in three
// different languages, with one .NET 8 solution. "Preserve all existing functionality and
// business logic" is only meaningful if something asserts it, and the cases below are that
// assertion at the unit level: the ported arithmetic, exercised directly, with no I/O in the way.
//
// What this file deliberately leaves alone, and why:
//
//   * The legacy driver code - the operand values the script supplied, and the number of times it
//     echoed the result to standard output - is composition-root behavior owned by the console
//     executable that consumes this library, not by the library itself. A unit test cannot prove
//     a process's standard output in any case, so that parity assertion lives in the CI workflow,
//     which compares each executable's real output against the captured legacy output.
//
//   * The numeric width of the port is settled rather than open. The legacy function performed
//     JavaScript arithmetic; the ported method performs integer arithmetic. That narrowing is a
//     disclosed, deliberate decision, because the only operands the legacy program ever supplied
//     are exactly representable under either scheme. Probing values outside the ported contract
//     would test a promise the port does not make.
//
//   * The ported method performs no argument validation and no overflow detection, so it has no
//     failure mode for a test to provoke.
//
// Naming: the Method_Scenario_Expectation convention used below is underscored, which CA1707
// rejects. This repository grants that exemption in exactly one place - a path-scoped
// [tests/**/*.cs] severity override in the root .editorconfig - which is why this file has to
// stay under tests/ for the build to remain warning-free, and why a local suppression is not an
// acceptable substitute for it.
public class CalculatorTests
{
    // One theory over three rows, each of which the test runner executes as its own case: the
    // computation the legacy program actually performed, plus the two boundaries the original
    // never exercised.
    [Theory]
    [InlineData(5, 7, 12)]
    [InlineData(0, 0, 0)]
    [InlineData(-3, 3, 0)]
    public void Add_WithIntegerOperands_ReturnsSum(int a, int b, int expected)
    {
        // Row 1 carries the exact operands the legacy script passed [index.js:L5], so it pins the
        // only value that program ever printed. Row 2 pins the additive identity. Row 3 pins
        // signed arithmetic: a port that summed magnitudes rather than values would answer 6 here
        // and fail.
        //
        // Assert.Equal takes the expectation first and the observed value second - reversing the
        // two still passes, but inverts every failure message. The observed value is produced by
        // calling the ported method, never by recomputing the sum inline with C#'s own + operator:
        // that would assert the language instead of the port, and would pass however the port
        // happened to be implemented.
        Assert.Equal(expected, Calculator.Add(a, b));
    }
}
