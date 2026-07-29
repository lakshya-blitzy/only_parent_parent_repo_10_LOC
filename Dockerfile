# Container build for the Blitzy.LegacyPort solution (C# 12 on net8.0).
#
# Two stages, each pinned to an exact patch tag so the same input always produces the same image:
#
#   build   - the .NET SDK restores, compiles, tests, and publishes the solution.
#   final   - a chiseled .NET runtime image carries nothing but the published payload.
#
# The image is a reproducible build / test / run verification artifact. It carries three console
# programs that write to stdout and exit; it has no listening surface, and it takes no part in
# publishing, tagging, or releasing anything.
#
#   docker build -t legacyport .
#   docker run --rm legacyport

# =================================================================================================
# Stage 1 - build: restore, compile, test, and publish all three console programs.
#
# The SDK tag is the exact patch that global.json pins, so the compiler and analyzers that gate this
# build are the same ones a developer runs locally.
# =================================================================================================
FROM mcr.microsoft.com/dotnet/sdk:8.0.423-noble AS build

# The solution root inside the image. `COPY src/ ./src/` below therefore lands at /source/src, which
# keeps every solution- and project-relative path in LegacyPort.sln resolving exactly as it does in
# a developer checkout - no path rewriting anywhere.
WORKDIR /source

# Build-stage only, and deliberately limited to CLI chatter: suppress the first-run banner and the
# telemetry upload so the build log is signal and the build makes no unnecessary network call.
# Neither variable touches culture, encoding, or line endings, so neither can influence a single
# byte that the published programs write.
ENV DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_NOLOGO=1

# Root manifests first, on their own layer, ahead of any source. They pin the toolchain, the target
# framework, and every package version, and they change far less often than code - so a source-only
# edit does not invalidate this layer, and the policy that governs the build is fixed in the image
# before a single line of code enters it.
#
#   global.json              pins the SDK band.
#   Directory.Build.props    the single TargetFramework declaration plus the zero-warning gate.
#   Directory.Packages.props Central Package Management; every package version lives here.
#   .editorconfig            REQUIRED at compile time, not merely in an editor. Directory.Build.props
#                            turns on EnforceCodeStyleInBuild and makes warnings fatal, so the
#                            path-scoped analyzer exemption for the test tree must be present in the
#                            build context or the compile below fails on test-method naming alone.
#   LegacyPort.sln           the restore, build, and test unit.
COPY global.json Directory.Build.props Directory.Packages.props .editorconfig LegacyPort.sln ./

COPY src/ ./src/
COPY tests/ ./tests/

# Restoring follows the sources rather than preceding them, because restoring the solution reads
# every project file the solution registers. It is also the only step here that reaches the network,
# and the graph it resolves is exact: every version comes from Directory.Packages.props.
RUN dotnet restore LegacyPort.sln

# No quality switch is passed here on purpose: Directory.Build.props already promotes every warning,
# analyzer diagnostic, and code-style violation in the whole solution to an error.
RUN dotnet build LegacyPort.sln -c Release --no-restore

# The unit tests gate the image. A failing assertion fails `docker build`, so an image can never be
# produced from a solution whose ported behavior has drifted.
RUN dotnet test LegacyPort.sln -c Release --no-build

# All three programs publish into one payload directory and coexist there because they share a
# single Blitzy.LegacyPort.Core.dll. Each publish reuses the artifacts just built (--no-build), and
# each is framework-dependent: the shared framework comes from the base image of the next stage, so
# nothing is trimmed, embedded, or compiled ahead of time.
RUN dotnet publish src/Blitzy.LegacyPort.GreeterApp/Blitzy.LegacyPort.GreeterApp.csproj -c Release --no-build -o /app/publish
RUN dotnet publish src/Blitzy.LegacyPort.CalculatorApp/Blitzy.LegacyPort.CalculatorApp.csproj -c Release --no-build -o /app/publish
RUN dotnet publish src/Blitzy.LegacyPort.UserApp/Blitzy.LegacyPort.UserApp.csproj -c Release --no-build -o /app/publish

# =================================================================================================
# Stage 2 - final: the smallest image that can execute the payload.
#
# The chiseled runtime tag is the least-privilege choice and the exact patch that matches the SDK
# above: it ships no shell and no package manager, and it runs as a non-root account by default.
# Nothing below relaxes that posture - no privilege change, and no package installation, because
# there is no package manager to install with. Only the published payload crosses the stage
# boundary; the SDK, the NuGet cache, the test host, and the intermediate output all stay behind.
# =================================================================================================
FROM mcr.microsoft.com/dotnet/runtime:8.0.29-noble-chiseled AS final

WORKDIR /app

COPY --from=build /app/publish ./

# The calculator program runs by default. The arguments are literal because a chiseled image has no
# shell to expand anything: exec form is the only form that can work here, and a build argument
# selecting the program would publish one and launch another.
#
# Run a sibling program by overriding the entrypoint:
#   docker run --rm --entrypoint dotnet <image> Blitzy.LegacyPort.GreeterApp.dll
#   docker run --rm --entrypoint dotnet <image> Blitzy.LegacyPort.UserApp.dll
ENTRYPOINT ["dotnet", "Blitzy.LegacyPort.CalculatorApp.dll"]
