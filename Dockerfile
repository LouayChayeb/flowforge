# syntax=docker/dockerfile:1.7
#
# One Dockerfile for both services. Pick the service with MODULE:
#   docker build --build-arg MODULE=flowforge-orchestrator -t flowforge-orchestrator .
#   docker build --build-arg MODULE=flowforge-worker       -t flowforge-worker .
#   docker build --build-arg MODULE=flowforge-payments     -t flowforge-payments .
#
# Tests are not run here; CI runs them against real containers before images are built.

ARG JAVA_VERSION=17

FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build
ARG MODULE
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY flowforge-common/ flowforge-common/
COPY flowforge-orchestrator/ flowforge-orchestrator/
COPY flowforge-worker/ flowforge-worker/
COPY flowforge-payments/ flowforge-payments/
# The Maven repository is cached between builds, so only changed code is recompiled.
RUN --mount=type=cache,target=/root/.m2 \
    test -n "$MODULE" || (echo "Set --build-arg MODULE" >&2; exit 1); \
    sh mvnw -B -q -pl "$MODULE" -am package -DskipTests \
    && cp "$MODULE"/target/"$MODULE"-*.jar /app.jar
# Split the fat jar into layers, so a code change only replaces the small application layer.
RUN java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:${JAVA_VERSION}-jre
RUN groupadd --system --gid 10001 flowforge \
    && useradd --system --uid 10001 --gid flowforge --no-create-home flowforge
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
# Size the heap from the container's memory limit rather than the node's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
