namespace Blitzy.LegacyPort.Core;

/// <summary>
/// Represents a named user, ported from the legacy Java class <c>User</c>.
/// </summary>
/// <remarks>
/// The Java original kept its name in a mutable local variable inside the program entry
/// point. The port promotes that value onto the type itself as a get-only property, so a
/// <see cref="User"/> is immutable and its name cannot drift after construction. The value
/// is stored and returned verbatim - never trimmed, re-cased, or replaced by a fallback -
/// because the behaviour being preserved is the exact text the original program printed.
/// The name literal itself belongs to the executable that composes this type rather than to
/// this library, which is what keeps the library free of any program-specific value.
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
