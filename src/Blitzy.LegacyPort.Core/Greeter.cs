namespace Blitzy.LegacyPort.Core;

/// <summary>
/// Produces greeting text for a named subject.
/// </summary>
public static class Greeter
{
    /// <summary>
    /// Builds the greeting for the supplied name.
    /// </summary>
    /// <remarks>
    /// The method is pure and culture independent, and the prefix ends in a single significant
    /// space, so an empty name yields the prefix alone.
    /// </remarks>
    /// <param name="name">The name to greet.</param>
    /// <returns>The prefix <c>Hello </c> followed immediately by <paramref name="name"/>.</returns>
    public static string Greet(string name) => $"Hello {name}";
}
