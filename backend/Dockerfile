# Build only after Maven verify. Exact official multiarch JRE digest; no SDK or source checkout.
FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:03fedab2c0d7355868b5ba5330d64088b956a0bfac48f904e5c57207e261e2c9

WORKDIR /opt/hackatlas
COPY --chown=10001:10001 target/hackatlas-backend-0.1.0-SNAPSHOT.jar /opt/hackatlas/app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=20 -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/hackatlas/app.jar"]
