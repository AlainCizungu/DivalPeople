# Backend image.
#
# Two stages, because a JDK, Gradle and the whole dependency cache have no business being on a
# server. The runtime layer carries a JRE and one jar.
#
# Tags are pinned to a patch version rather than to `21`, so a rebuild six months from now
# produces the same image rather than a surprise. Pinning to a digest would be stricter still and
# is the right next step once there is a registry mirror to pin against.

# --- build -------------------------------------------------------------------
FROM eclipse-temurin:21.0.6_7-jdk-jammy AS build

WORKDIR /src

# The wrapper and dependency declarations first, so a source-only change does not re-download
# every dependency. This is the whole reason the copies are split.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src

# Tests do not run here. They run in CI, against Testcontainers, on a machine that has Docker;
# running them again inside a build that has no Docker socket would either fail or silently skip,
# and a build that silently skips its tests is worse than one that never had them.
RUN ./gradlew --no-daemon bootJar -x test

# Unpacked so the layers below can be cached separately: dependencies change rarely, application
# classes change every commit.
RUN java -Djarmode=tools -jar build/libs/*.jar extract --layers --destination extracted

# --- runtime -----------------------------------------------------------------
FROM eclipse-temurin:21.0.6_7-jre-jammy AS runtime

# An unprivileged, named account. Running as root inside a container means a container escape
# starts as root, and there is no reason for this process to own anything.
#
# NOT called `dip`: that is a standard Debian and Ubuntu system group (dial-out networking, GID
# 30) and exists in both base images, so groupadd refuses and the build fails.
RUN groupadd --system --gid 10001 dipsvc \
    && useradd --system --uid 10001 --gid dipsvc --no-create-home --shell /usr/sbin/nologin dipsvc

WORKDIR /app

COPY --from=build --chown=dipsvc:dipsvc /src/extracted/dependencies/ ./
COPY --from=build --chown=dipsvc:dipsvc /src/extracted/spring-boot-loader/ ./
COPY --from=build --chown=dipsvc:dipsvc /src/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=dipsvc:dipsvc /src/extracted/application/ ./

USER dipsvc:dipsvc

EXPOSE 8080

# Container-aware heap sizing. Without it the JVM reads the host's memory rather than the
# container limit and is killed by the OOM killer under load, which looks like a mysterious
# restart rather than a memory setting.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Actuator health is already exposed with details hidden. The orchestrator decides what to do
# with an unhealthy container; this only says whether it is.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD ["/bin/sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
