using Blitzy.LegacyPort.Core;

const string DefaultUserName = "Test";

User user = new(DefaultUserName);

Console.WriteLine(user.Name);
