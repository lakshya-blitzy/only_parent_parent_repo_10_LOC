using Blitzy.LegacyPort.Core;

const int FirstAddend = 5;
const int SecondAddend = 7;
const int OutputRepetitions = 5;

int result = Calculator.Add(FirstAddend, SecondAddend);

for (int i = 0; i < OutputRepetitions; i++)
{
    Console.WriteLine(result);
}
