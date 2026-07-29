using Blitzy.LegacyPort.Core;

namespace Blitzy.LegacyPort.Core.Tests;

public class CalculatorTests
{
    [Theory]
    [InlineData(5, 7, 12)]
    [InlineData(0, 0, 0)]
    [InlineData(-3, 3, 0)]
    public void Add_WithIntegerOperands_ReturnsSum(int a, int b, int expected)
    {
        Assert.Equal(expected, Calculator.Add(a, b));
    }
}
