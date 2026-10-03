# Build from the repository root: docker build -f server/Dockerfile .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon -Ppushbeam.serverOnly=true :server:installDist

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 pushbeam && mkdir /data && chown pushbeam /data
COPY --from=build /src/server/build/install/server /app
USER pushbeam
ENV PUSHBEAM_DATA_DIR=/data \
    PUSHBEAM_LISTEN=0.0.0.0:8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD ["sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /health/ready HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q READY <&3"]
ENTRYPOINT ["/app/bin/server"]
