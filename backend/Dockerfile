# The §22 market-data proxy (issue 6.7).
#
# Why:  the deliverable of issue 6.7 is a running service, and a service that only starts on the
#       machine that wrote it is not one. Two stages so the image carries a JRE and a jar rather than
#       a JDK, Gradle, and the whole Android build this repository also contains.
# What: stage one builds `:backend` alone; stage two runs its distribution.
# Result: `docker build -f backend/Dockerfile .` from the repository root produces a runnable image.
#         Note the context is the ROOT, not `backend/` — the module depends on `:core:model`, on
#         `build-logic`, and on the version catalog, none of which live inside `backend/`.
# Changelog: 2026-08-30 — Created for issue 6.7.

# --- build ------------------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# The wrapper and the build definition first, so a change to source code does not re-resolve every
# dependency. `--no-daemon` because a daemon that outlives a build stage is only a memory leak.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
RUN chmod +x gradlew

# Only what :backend actually needs. Copying the whole tree would drag in every Android module and
# make the build stage depend on the Android SDK, which is not present in this image and is not
# needed to compile a JVM server.
COPY core/model core/model
COPY backend backend
COPY contracts contracts

RUN ./gradlew --no-daemon :backend:installDist -x test

# --- run --------------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/backend/build/install/backend ./

# Nothing here needs to write to disk or be root: the service is stateless, its only cache is in
# memory, and it holds no user data at all (EXT-003).
RUN useradd --system --no-create-home cfo
USER cfo

# CFO_PORT is what a platform overrides; the TLS variables stay unset in production because the
# platform terminates TLS in front of this. See README.md.
ENV CFO_PORT=8080
EXPOSE 8080

ENTRYPOINT ["./bin/backend"]
