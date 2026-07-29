namespace Blitzy.LegacyPort.Core;

/// <summary>
/// Represents a named user.
/// </summary>
/// <remarks>
/// The type is immutable: the name is supplied at construction and returned verbatim, never
/// trimmed, re-cased, or replaced by a fallback.
/// </remarks>
/// <param name="name">The name to expose through <see cref="Name"/>.</param>
public sealed class User(string name)
{
    /// <summary>
    /// Gets the user's name, exactly as supplied at construction time.
    /// </summary>
    public string Name { get; } = name;

    /// <summary>
    /// Returns the user's name.
    /// </summary>
    /// <returns>The value of <see cref="Name"/>.</returns>
    public override string ToString() => Name;
}
