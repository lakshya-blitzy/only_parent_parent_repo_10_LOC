using Blitzy.LegacyPort.Core;

namespace Blitzy.LegacyPort.Core.Tests;

public class UserTests
{
    [Fact]
    public void Name_ReturnsConstructorArgument()
    {
        User user = new("Test");

        Assert.Equal("Test", user.Name);
    }

    [Fact]
    public void ToString_ReturnsName()
    {
        User user = new("Test");

        Assert.Equal("Test", user.ToString());
    }
}
