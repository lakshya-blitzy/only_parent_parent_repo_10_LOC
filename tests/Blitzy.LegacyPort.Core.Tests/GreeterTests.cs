using Blitzy.LegacyPort.Core;

namespace Blitzy.LegacyPort.Core.Tests;

// Behaviour net for Greeter, the C# port of the module-level Python function greet(name) that the
// retired app.py declared on its first two lines. The migration promised to preserve the observable
// behaviour of the legacy program rather than merely to translate its syntax, and the three facts
// below are the unit-level half of that promise: they pin the greeting format itself, so a later
// edit to Greeter cannot quietly change what the greeter executable prints.
//
// Scope is deliberately narrow. Greeter.Greet is pure - it reads and writes no state, performs no
// input or output, and returns a value computed solely from its argument - so there is nothing to
// arrange, nothing to release, and no fixture to share between cases. This class therefore declares
// no constructor and no lifetime hook, and every fact is a single independent assertion.
//
// Confirming what each published executable actually writes to standard output is a different
// concern verified at a different level: that byte-for-byte comparison runs against the published
// binaries in continuous integration, never from a unit test, because a unit test observes a return
// value rather than a process. Nothing here starts a process or redirects a console stream.
//
// Method names follow the Method_Scenario_Expectation convention universal to xUnit suites. Its
// underscores collide with the code-analysis rule CA1707, which the repository .editorconfig
// switches off for the test tree alone and leaves fatal everywhere under src/, so the collision is
// resolved once in that conventions file and no local exemption belongs in this file.
public class GreeterTests
{
    // The name the shipping program greets, taken verbatim from the assignment on line 5 of the
    // retired app.py. Standard output of the legacy program was that greeting followed by a single
    // newline - 14 bytes in total - and this assertion is what keeps the 13 characters of the line
    // honest at the unit level, independently of the executable that prints them.
    [Fact]
    public void Greet_WithLegacyUserName_ReturnsGreeting()
    {
        Assert.Equal("Hello Lakshya", Greeter.Greet("Lakshya"));
    }

    // A second, different name proves the greeting is genuinely parameterised rather than a
    // constant that merely happens to end in the first name. The value chosen is the literal the
    // retired Java program printed, so this case also carries that value through the greeting
    // contract and shows the two ported programs share one consistent formatting rule.
    [Fact]
    public void Greet_WithAlternateName_ReturnsGreeting()
    {
        Assert.Equal("Hello Test", Greeter.Greet("Test"));
    }

    // The boundary the original never exercised: an empty name is legitimate rather than
    // exceptional and yields the bare prefix - the five characters of the salutation followed by
    // exactly one space - because interpolating an empty string contributes nothing. The expected
    // value is written as a plain string literal precisely so that the space before the closing
    // quote is visible in a review diff; assembling it by concatenation or interpolation would hide
    // the single character this case exists to protect. Trimming that space, or doubling it, would
    // corrupt every greeting the solution produces, and this is the assertion that fails loudly the
    // moment it happens.
    [Fact]
    public void Greet_WithEmptyName_ReturnsPrefixOnly()
    {
        Assert.Equal("Hello ", Greeter.Greet(string.Empty));
    }
}
