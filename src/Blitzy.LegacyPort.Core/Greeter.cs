namespace Blitzy.LegacyPort.Core;

/// <summary>
/// Produces greeting text for a named subject.
/// </summary>
/// <remarks>
/// <para>
/// This type is the C# port of the module-level Python function <c>greet(name)</c> declared on
/// lines 1 and 2 of the legacy <c>app.py</c>, and it is the single place in the solution where the
/// greeting format is defined. The class is declared <see langword="static"/> because the original
/// carried no instance state: a module-level function maps onto a static member rather than onto an
/// object, so this type is never instantiated.
/// </para>
/// <para>
/// The greeting format is observable behavior rather than an implementation detail. Standard output
/// of the legacy program was captured and compared byte for byte against this port, so the prefix -
/// the five characters <c>Hello</c> followed by exactly one space - must not be altered, reordered,
/// or padded. Trimming that space or doubling it would silently break the recorded parity of the
/// greeter executable, whose entire output is a single line of 14 bytes.
/// </para>
/// <para>
/// The name that the shipping program greets is supplied by the executable that composes this
/// library, never declared here. That separation keeps this assembly free of any package
/// dependency, any configuration source, and any input or output of its own, and it is what makes
/// the ported logic verifiable in isolation.
/// </para>
/// </remarks>
public static class Greeter
{
    /// <summary>
    /// Builds the greeting for the supplied name.
    /// </summary>
    /// <remarks>
    /// The method is pure. It reads and writes no state, performs no input or output, and allocates
    /// only the string it returns, so it yields the same result for the same argument on every call
    /// and is safe to invoke concurrently from any number of threads. The result is culture
    /// independent, because interpolating a value that is already text copies it verbatim with no
    /// numeric or date formatting involved, which is why this port stays byte-exact under the
    /// solution-wide invariant globalization setting.
    /// </remarks>
    /// <param name="name">
    /// The name to greet. An empty value is legitimate rather than exceptional and yields the bare
    /// prefix, exactly as the interpolation in the legacy module did. The parameter is non-nullable,
    /// so a null argument is rejected by the compiler at the call site instead of deferring the
    /// failure to run time.
    /// </param>
    /// <returns>The prefix <c>Hello </c> followed immediately by <paramref name="name"/>.</returns>
    public static string Greet(string name) => $"Hello {name}";
}
