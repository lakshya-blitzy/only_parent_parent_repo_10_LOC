using Blitzy.LegacyPort.Core;

namespace Blitzy.LegacyPort.Core.Tests;

public class GreeterTests
{
    [Fact]
    public void Greet_WithLegacyUserName_ReturnsGreeting()
    {
        Assert.Equal("Hello Lakshya", Greeter.Greet("Lakshya"));
    }

    [Fact]
    public void Greet_WithAlternateName_ReturnsGreeting()
    {
        Assert.Equal("Hello Test", Greeter.Greet("Test"));
    }

    // The expected value ends in a significant space: the prefix is a salutation followed by
    // exactly one space, so an empty name yields the prefix alone.
    [Fact]
    public void Greet_WithEmptyName_ReturnsPrefixOnly()
    {
        Assert.Equal("Hello ", Greeter.Greet(string.Empty));
    }
}
