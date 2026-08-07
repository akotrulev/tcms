# syntax=docker/dockerfile:1.7
#
# TCMS backend — the production artifact.
#
# The same image serves both roles the jar serves: the long-running HTTP server
# (default) and the operator CLI (`docker compose run --rm backend provision-company …`),
# because TcmsApplication decides between them from the raw arguments. Do not add a
# server-only ENTRYPOINT wrapper — it would break the CLI path silently.

# ---- Build stage ----
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src

# Wrapper + build definition first, so the dependency layer is cached independently of
# source edits. The version catalog is part of the build definition, not of the sources:
# leave it out and every `libs.` accessor is unresolvable in this stage.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./

# `|| true`: this warm-up exists purely to populate the cache mount. A resolution failure
# here must not fail the build — the real resolution happens in the bootJar step below,
# where a failure genuinely is one.
#
# ~~--refresh-dependencies~~ → REMOVED 2026-08-07. It tells Gradle to IGNORE cached module
# metadata and re-check every dependency against the remote repositories, which is the exact
# opposite of "populate the cache mount" — the flag made this step re-do on every build the
# work the mount exists to avoid. It buys correctness only against mutating versions, and
# this build has none: the version catalog pins every dependency and there are no dynamic
# ranges or -SNAPSHOTs in it. Put it back if that stops being true.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon || true

COPY src src

# Tests are NOT run here on purpose. Every test in this suite boots a real Postgres 17
# through Testcontainers, which needs a Docker socket the build stage does not have and
# must not be given. `./gradlew build` in CI is the test gate; this stage only packages.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -x test

# Split the fat jar into layers so Docker caches dependencies separately from application
# classes. Boot 3.3+ spells this `-Djarmode=tools … extract`; the older `layertools` form
# is deprecated.
#
# The glob must exclude `-plain.jar`: the Gradle `jar` task emits one alongside the boot
# jar, `java -jar` on it fails with "no main manifest attribute", and `ls build/libs/*.jar`
# picks whichever sorts first.
RUN set -eu; \
    jar="$(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -n1)"; \
    test -n "$jar" || { echo "no boot jar produced" >&2; exit 1; }; \
    java -Djarmode=tools -jar "$jar" extract --layers --launcher --destination /extracted

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-noble AS runtime

ARG VERSION="0.0.0"
ARG REVISION="dev"
LABEL org.opencontainers.image.title="tcms-backend" \
      org.opencontainers.image.description="TCMS — multi-company test case management, Spring Boot API + operator CLI" \
      org.opencontainers.image.source="https://github.com/akotrulev/tcms" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="UNLICENSED"

# This image carries a Docker HEALTHCHECK, against this project's usual "platform probes
# only" default, because there is no platform probe layer: the deployment is
# `docker compose` on a single VM with Caddy in front, so Docker's own healthcheck is the
# only thing that knows whether the container is serving.
#
# That needs an HTTP client, and the guidance is that temurin ships neither curl nor wget.
# Measured: 21-jre-noble DOES ship wget (1.21.4). So this asserts it rather than
# installing it — no apt layer, no unpinned package pulled at build time, and if a future
# base tag drops wget the build fails here instead of shipping a container that reports
# unhealthy forever. An `apt-get install` would have hidden that change, which is the
# failure the guidance is actually warning about.
#
# UID 10001, not the conventional 1000: Ubuntu 24.04 (noble) base images already ship an
# `ubuntu` user at 1000, so `groupadd --gid 1000` fails outright. It failing loudly is the
# good case — reusing that account silently would run the application as a pre-existing
# identity the base image controls.
RUN command -v wget >/dev/null 2>&1 \
      || { echo "base image no longer ships wget — the HEALTHCHECK below cannot run" >&2; exit 1; } \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --shell /usr/sbin/nologin --no-create-home app

WORKDIR /app
USER 10001:10001

# Dependency-stable order: the first three layers change only when the build file does.
COPY --from=build --chown=10001:10001 /extracted/dependencies/          ./
COPY --from=build --chown=10001:10001 /extracted/spring-boot-loader/    ./
COPY --from=build --chown=10001:10001 /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /extracted/application/           ./

EXPOSE 8080

# No SPRING_PROFILES_ACTIVE here, deliberately — the skill template bakes `prod` into the
# image, but one image serves local compose and the VM, and a profile baked in is a
# per-environment value that cannot be un-set from outside. The deployment sets it.
#
# MaxRAMPercentage (not -Xmx) so the heap tracks the container's memory limit rather than
# the host's RAM. Box-specific tuning (GC choice, a hard ceiling) goes in JDK_JAVA_OPTIONS
# at the deployment, which the launcher honours *alongside* this rather than replacing it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# readiness, not health: `health` reports UP before the datasource and Flyway have settled
# on a cold start, so it would mark the container healthy while the first request still
# 500s. start-period covers migration time on an empty database.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
