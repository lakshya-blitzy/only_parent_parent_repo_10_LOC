# =============================================================================
# Dockerfile - ONE multi-stage container definition for all three applications.
#
# WHY THIS FILE EXISTS
#   The requirement is "modify the Dockerfile if required", and the condition
#   evaluates to true: a container health probe is the standard consumer of a
#   /health endpoint, and it is the only mechanism by which a container runtime
#   can act on that endpoint's result. The noun in that requirement is
#   SINGULAR, so this is exactly one file with a selectable target per language
#   rather than three Dockerfiles. Never add a Dockerfile.python,
#   Dockerfile.node or Dockerfile.java beside it.
#
# TARGETS
#   docker build --target python     .   Python  implementation, serves :8000
#   docker build --target node       .   Node.js implementation, serves :8001
#   docker build --target java-build .   compiles User.java; NOT runnable
#   docker build --target java       .   Java    implementation, serves :8002
#
#   The service definitions in compose.yaml and the container job of
#   .github/workflows/ci.yml select these stages by name, so the four names
#   above are a contract rather than a label: renaming one breaks both.
#
# CONTAINERISATION IS OPTIONAL HERE - a recorded tension, deliberately not hidden
#   The technical specification for this repository records that
#   containerisation is not RECOMMENDED at this scale, while the requirement
#   asks for it explicitly. The resolution is to honour the requirement and
#   keep the container surface minimal and purely additive: nothing in this file
#   changes application behaviour, nothing in it adds a dependency, and no test,
#   script or workflow needs a container in order to run. Using this file is
#   entirely optional, and the tension is written down here rather than papered
#   over.
#
# NOTHING IS INSTALLED, IN ANY STAGE
#   No package manager runs anywhere in this file - no apt, no pip, no npm, no
#   apk, nothing. The repository's zero-dependency self-containment is a
#   property worth preserving, and every capability the endpoint needs (an HTTP
#   server, a JSON document, an instant, a properties parser, and an HTTP client
#   for the self-check) already ships in each language's standard library. The
#   only command executed at build time in this entire file is a single `javac`.
#
# THE HEALTH CHECK IS THE APPLICATION CHECKING ITSELF
#   The most commonly published health-check pattern shells out to a
#   command-line HTTP client, and it is the wrong pattern for these images: a
#   slim Debian base and a JRE base ship no HTTP client at all. Verified in
#   these exact three images - `command -v` finds neither of the two
#   conventional command-line fetchers in any of them. Installing one would
#   enlarge the image, widen its attack surface, add patch burden, and hand a
#   post-exploitation attacker a ready download-and-run helper.
#
#   So every HEALTHCHECK below invokes the application's own --probe mode. It
#   requests its own endpoint using the runtime that is already present, asserts
#   the whole frozen response contract and the application's identity, and exits
#   0 when healthy or 1 when not - which is exactly what the container runtime
#   reads. For Java this is also why --probe lives inside User itself: the
#   runtime image is a JRE with no compiler, so a separate probe source file
#   could not be launched there at all.
#
# CONFIGURATION
#   app.config.properties travels into every runnable image beside the program,
#   because that is where each implementation looks for it (app.py resolves it
#   from __file__, index.js from __dirname, User.java from its code-source
#   directory). It stays the single source of truth: APP_NAME, APP_VERSION and
#   HEALTH_PATH are deliberately NOT baked in as ENV, because an image-level
#   value outranks the file and would silently shadow it. Every value is still
#   overridable one key at a time at run time:
#     docker run -e APP_VERSION=1.2.0 -e PORT=9000 -p 9000:9000 opprl10-python
#
# BUILD AND RUN
#   docker build --target python -t opprl10-python .
#   docker run --rm -d --name opprl10-python -p 8000:8000 opprl10-python
#   docker inspect --format '{{.State.Health.Status}}' opprl10-python # healthy
#   All three at once, with health reported declaratively:
#   docker compose up --build
#
# BASE IMAGES
#   Pinned at patch level so a rebuild resolves the same bytes; every tag was
#   verified to exist. The floating equivalents python:3.14-slim, node:22-slim,
#   eclipse-temurin:25-jdk and eclipse-temurin:25-jre also exist and are the
#   documented fallbacks, but they are not what this file uses.
#
#   There is deliberately no `# syntax=` frontend directive. The built-in
#   frontend builds these four stages exactly as written, while pinning an
#   external frontend image would make every build - including an air-gapped one
#   - depend on pulling that image first.
# =============================================================================


# -----------------------------------------------------------------------------
# Stage `python` - the Python implementation, serving /health on port 8000.
# -----------------------------------------------------------------------------
FROM python:3.14.6-slim AS python

# A dedicated unprivileged account. groupadd and useradd already exist in this
# Debian base, so nothing is installed to obtain one. The uid/gid pair is fixed
# so the identity is stable across rebuilds and usable by an orchestrator policy
# that requires a numeric non-root id; no home directory is created because
# nothing writes to one, and the shell is nologin because nothing ever logs in.
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

# APP_HOST is the one setting that is both safe and necessary to fix in the
# image: a listener bound to loopback is unreachable from outside the container,
# so the wildcard bind is what makes a published port work at all. It remains
# overridable with `docker run -e APP_HOST=...`. PYTHONDONTWRITEBYTECODE stops a
# bytecode cache from ever being attempted beside root-owned sources, and
# PYTHONUNBUFFERED puts the startup line and any warning into `docker logs`
# immediately instead of at exit.
ENV APP_HOST=0.0.0.0 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# One layer, and app.config.properties lands beside app.py, which is where
# app.py resolves it from. Both files stay root-owned and world-readable: the
# unprivileged runtime user can read the code it executes and cannot modify it.
COPY app.py app.config.properties ./

# Documents the default from app.config.properties (python.port=8000). PORT
# outranks every other source at run time, so `-e PORT=9000 -p 9000:9000` moves
# the listener with no rebuild; this line does not constrain that.
EXPOSE 8000

USER app

# Exit 0 healthy, exit 1 unhealthy. Exec form, so no shell is involved.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD ["python", "app.py", "--probe"]

# Serve mode. The default, no-flag invocation is untouched and still prints the
# legacy greeting: `docker run --rm opprl10-python python app.py`.
CMD ["python", "app.py", "--serve"]


# -----------------------------------------------------------------------------
# Stage `node` - the JavaScript implementation, serving /health on port 8001.
#
# The tag tracks .nvmrc (22.23.1) rather than a newer line, because .nvmrc is
# the single source of truth the CI setup action reads natively: a container
# running a version no gate ever exercises is precisely the local/CI divergence
# that pinning exists to prevent. package.json declares engines.node
# ">=22.12.0", which this satisfies. If .nvmrc is ever moved forward, move this
# tag with it in the same commit - node:24.18.0-slim is the patch-level tag the
# specification originally named, and it is equally valid the moment the pin is.
# -----------------------------------------------------------------------------
FROM node:22.23.1-slim AS node

WORKDIR /app

# Same reasoning as the python stage: the wildcard bind is what makes the
# published port reachable, and nothing else about the application's identity is
# baked in.
ENV APP_HOST=0.0.0.0

# index.js resolves app.config.properties from __dirname, so the two travel
# together into the same directory.
COPY index.js app.config.properties ./

# Documents the default from app.config.properties (node.port=8001).
EXPOSE 8001

# This base already ships an unprivileged `node` account (uid 1000), so no
# account is created here - dropping root costs one instruction.
USER node

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD ["node", "index.js", "--probe"]

# The default, no-flag invocation still writes its five legacy lines:
# `docker run --rm opprl10-node node index.js`.
CMD ["node", "index.js", "--serve"]


# -----------------------------------------------------------------------------
# Stage `java-build` - the only stage that needs a compiler.
#
# Not runnable by design: no EXPOSE, no HEALTHCHECK, no CMD and no user. Its
# sole output is the compiled unit that the `java` stage below copies.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25.0.3_9-jdk AS java-build

WORKDIR /build

COPY User.java ./

# -d writes the whole compiled unit into one directory, and that DIRECTORY - not
# the single file User.class - is what the runtime stage copies, because javac
# emits five class files here: User.class plus the nested User$Answer,
# User$Config, User$HealthServer and User$JsonReader. Copying only User.class
# yields a container that dies at start-up with `NoClassDefFoundError:
# User$Config` the moment serve mode loads its configuration; that was confirmed
# by running it that way rather than assumed. No -Xlint flags:
# compiler lint is the CI compile gate's job, and repeating it here would only
# add a way for an image build to fail for something the workflow already
# reports.
RUN javac -d /build/classes User.java


# -----------------------------------------------------------------------------
# Stage `java` - the Java implementation, serving /health on port 8002.
# A JRE, not a JDK: the compiler stayed behind in `java-build`.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25.0.3_9-jre AS java

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

ENV APP_HOST=0.0.0.0

# The compiled unit, then the configuration beside it: User resolves
# app.config.properties from its own code-source directory - the classpath
# directory - before falling back to the working directory, and here those are
# the same directory. `.dockerignore` excludes *.class from the build context on
# purpose, so a stale locally compiled class can never leak in; COPY --from is
# not subject to that exclusion and is the only route class files take.
COPY --from=java-build /build/classes/ ./
COPY app.config.properties ./

# Documents the default from app.config.properties (java.port=8002).
EXPOSE 8002

USER app

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD ["java", "-cp", "/app", "User", "--probe"]

# An absolute -cp, so the command does not depend on the working directory. A
# JRE ships no compiler, so `java User.java` single-file source launch is
# unavailable here - which is exactly why --probe lives inside User itself. The
# two modules the server and the probe need are present in this image:
# `java --describe-module jdk.httpserver` reports jdk.httpserver@25.0.3
# requiring only java.base, and java.net.http@25.0.3 is there too, so no
# --add-modules flag is needed. Were a future JRE tag ever to drop them, the
# fallback is to build this stage from eclipse-temurin:25.0.3_9-jdk instead.
# The default, no-flag invocation still prints the legacy name:
# `docker run --rm opprl10-java java -cp /app User`.
CMD ["java", "-cp", "/app", "User", "--serve"]
