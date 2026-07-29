using Blitzy.LegacyPort.Core;

// Composition root for the greeter program, ported from the legacy Python module app.py. The
// original guarded driver block bound a name literal and printed the greeting built from it;
// the malformed duplicate block trailing it never parsed, so it carries no behavior and is
// not ported. The name literal below is observable behavior and survives verbatim as a named
// constant rather than being inlined into the call. The greeting text itself is owned by
// Greeter in the Blitzy.LegacyPort.Core library and is deliberately not restated here.
const string UserName = "Lakshya";

// Exactly one line reaches standard output: the greeting plus a single line feed, byte for
// byte what the original program emitted.
Console.WriteLine(Greeter.Greet(UserName));
