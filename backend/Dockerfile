# Build only after Maven verify. Exact official multiarch JRE digest; no SDK or source checkout.
FROM eclipse-temurin:21.0.12.1_1-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c

WORKDIR /opt/hackatlas
COPY --chown=10001:10001 target/hackatlas-backend-0.1.0-SNAPSHOT.jar /opt/hackatlas/app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=20 -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/hackatlas/app.jar"]
