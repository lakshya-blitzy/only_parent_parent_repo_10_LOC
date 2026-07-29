using Blitzy.LegacyPort.Core;

namespace Blitzy.LegacyPort.Core.Tests;

public class UserTests
{
    // Each fact asserts two names: a single expected value would also be satisfied by an
    // implementation that discarded its constructor argument and returned a fixed string.
    [Fact]
    public void Name_ReturnsConstructorArgument()
    {
        User user = new("Test");
        User otherUser = new("Ada");

        Assert.Equal("Test", user.Name);
        Assert.Equal("Ada", otherUser.Name);
    }

    [Fact]
    public void ToString_ReturnsName()
    {
        User user = new("Test");
        User otherUser = new("Grace");

        Assert.Equal("Test", user.ToString());
        Assert.Equal("Grace", otherUser.ToString());
    }
}
