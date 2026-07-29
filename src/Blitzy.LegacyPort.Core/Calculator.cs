namespace Blitzy.LegacyPort.Core;

/// <summary>
/// Provides the integer arithmetic ported from the legacy JavaScript script <c>index.js</c>,
/// whose sole reusable declaration was <c>function add(a, b) { return a + b; }</c>.
/// </summary>
/// <remarks>
/// The legacy script's driver code - the operand values it supplied and the number of times it
/// echoed the result - is deliberately absent from this type. That is composition-root behavior
/// and is owned by the executable that consumes this library, which keeps the arithmetic here
/// pure, free of I/O, and assertable in isolation.
/// </remarks>
public static class Calculator
{
    /// <summary>
    /// Adds two integers.
    /// </summary>
    /// <param name="a">The first addend.</param>
    /// <param name="b">The second addend.</param>
    /// <returns>The sum of <paramref name="a"/> and <paramref name="b"/>.</returns>
    public static int Add(int a, int b) => a + b;
}
