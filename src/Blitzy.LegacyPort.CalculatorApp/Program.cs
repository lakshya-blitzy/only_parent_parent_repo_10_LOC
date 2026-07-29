using Blitzy.LegacyPort.Core;

// Composition root for the C# port of the legacy index.js script.
//
// Two facts about that script are observable behavior, so both are preserved exactly here. First,
// it evaluated its sum a single time ("const result = add(5, 7);"). Second, it then wrote that one
// value to standard output five separate times, with five identical console.log calls. The
// operands and the write count are therefore bound to named constants at the one place that owns
// them, rather than being buried as inline literals, and the addition itself stays in the Core
// library where the unit tests reach it.
const int FirstAddend = 5;
const int SecondAddend = 7;
const int OutputRepetitions = 5;

// Evaluated once, before the loop: the legacy script computed the sum a single time and then
// reused the value, so calling into the library from inside the loop would change that shape.
int result = Calculator.Add(FirstAddend, SecondAddend);

// Five separate lines, one write each. Console.WriteLine emits Environment.NewLine, which on Linux
// is the same single line feed the legacy console.log produced, so the output is byte identical to
// the original: the value 12 on five lines, 15 bytes in total, and nothing on standard error.
for (int i = 0; i < OutputRepetitions; i++)
{
    Console.WriteLine(result);
}
