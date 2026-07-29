using Blitzy.LegacyPort.Core;

// Composition root for the C# port of the legacy Java program User.java.
//
// Provenance (User.java L2-L4): the Java "main" method declared a local "String name", assigned
// it the literal below, and emitted it once through System.out.println. That is the whole
// program, so this file does nothing but bind a named constant to the Core type and write the
// result to standard output; the name itself lives on Blitzy.LegacyPort.Core.User.
//
// Deliberate omissions, each of which preserves observable behavior rather than source shape:
//   * The Java class wrapper and the "main" signature are not translated. Top-level statements
//     are this assembly's start-up code, so no wrapping type is declared here.
//   * The unused command-line parameter of the original "main" is not reintroduced: the legacy
//     program never read it, and this port accepts no command-line input.
//   * The duplicate class that followed in the same compilation unit is not ported. It never
//     compiled, so it never ran, and it carries no behavior to preserve.
//
// Preserved contract: exactly one line on standard output, Test plus a single line feed; nothing
// on standard error; exit status zero.

// The name literal, carried over verbatim from User.java L3. It is observable behavior rather
// than a tunable default, so it may not be altered.
const string DefaultUserName = "Test";

// Java's mutable local becomes an immutable, sealed Core type that cannot drift.
User user = new(DefaultUserName);

// The port of System.out.println(name) [User.java:L4]: WriteLine appends Environment.NewLine,
// which is a line feed on Linux, so the emitted bytes match the original exactly.
Console.WriteLine(user.Name);
