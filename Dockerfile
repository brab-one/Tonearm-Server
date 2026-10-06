# The app is plain JVM bytecode, so it's built once on the build machine's own platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q -Pkotlin.compiler.execution.strategy=in-process help
COPY src src
RUN ./gradlew --no-daemon -q -Pkotlin.compiler.execution.strategy=in-process installDist

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 1000 tonearm && adduser -S -D -H -u 1000 -G tonearm tonearm \
    && mkdir /data && chown tonearm:tonearm /data
COPY --from=build /src/build/install/tonearm-server /opt/tonearm-server
USER tonearm
ENV PORT=8790 BASE_PATH=/connect-tonearm DATA_DIR=/data
EXPOSE 8790
VOLUME /data
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
    CMD wget -qO- "http://127.0.0.1:${PORT}${BASE_PATH}/health" >/dev/null || exit 1
ENTRYPOINT ["/opt/tonearm-server/bin/tonearm-server"]
