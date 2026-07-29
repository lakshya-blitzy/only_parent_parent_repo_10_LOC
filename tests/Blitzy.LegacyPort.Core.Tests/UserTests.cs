using Blitzy.LegacyPort.Core;

namespace Blitzy.LegacyPort.Core.Tests;

// Behavior contract for the C# port of the legacy Java class User.
//
// The Java original held its name in a mutable local variable inside the program entry point,
// assigned the literal "Test" to it, and printed that variable - five bytes on standard output,
// the four characters of the name followed by one line feed. The port promotes that value onto
// the type itself, so the two facts below are what keep the recorded parity honest at the unit
// level: the first pins the value the type carries, the second pins the text it projects.
//
// The type under test is deliberately minimal, and that shapes every assertion here. It is
// sealed, so it can be neither subclassed nor faked and is constructed directly. It declares no
// value-equality members, so each assertion compares strings rather than instances - comparing
// two instances would silently fall back to reference identity and assert nothing about the
// ported behavior. It validates no input, so there is no failure path to exercise. Proving the
// executable's actual standard output is the job of the continuous-integration parity step,
// which runs the published program; a unit test cannot observe a process stream.
public class UserTests
{
    // The name a User is constructed with must come back out of Name completely unchanged -
    // never trimmed, never re-cased, and never swapped for a fallback value. That verbatim
    // round-trip is what makes the type an immutable carrier of the legacy literal instead of
    // something that can quietly reshape it, so this is the guard on the immutability decision.
    [Fact]
    public void Name_ReturnsConstructorArgument()
    {
        User user = new("Test");

        Assert.Equal("Test", user.Name);
    }

    // The Java program printed a local variable; the port prints a property of a real type.
    // Pinning the string projection here gives that single output line a tested source, so the
    // text the type renders itself as cannot drift away from the name it was built with.
    [Fact]
    public void ToString_ReturnsName()
    {
        User user = new("Test");

        Assert.Equal("Test", user.ToString());
    }
}
