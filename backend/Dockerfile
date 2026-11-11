# Build only after Maven verify. Exact official multiarch JRE digest; no SDK or source checkout.
FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:03fedab2c0d7355868b5ba5330d64088b956a0bfac48f904e5c57207e261e2c9

# Use HTTPS before patching CVE-2026-109518. The amd64 mirrors serve Ubuntu-signed indexes.
RUN set -eu; \
    sed -i '/^URIs: /s|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources; \
    set -- -o Acquire::Retries=2 -o Acquire::https::Timeout=15; \
    if [ "$(dpkg --print-architecture)" = amd64 ]; then \
        mkdir /tmp/hackatlas-apt; \
        printf 'https://mirrors.edge.kernel.org/ubuntu/\tpriority:1\nhttps://archive.ubuntu.com/ubuntu/\tpriority:2\n' \
            > /tmp/hackatlas-apt/mirrors; \
        printf '%s\n' 'Types: deb' 'URIs: mirror+file:/tmp/hackatlas-apt/mirrors' \
            'Suites: noble noble-updates noble-security' 'Components: main' \
            'Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg' \
            > /tmp/hackatlas-apt/ubuntu.sources; \
        set -- "$@" -o Dir::Etc::sourcelist=/tmp/hackatlas-apt/ubuntu.sources -o Dir::Etc::sourceparts=-; \
    fi; \
    apt-get "$@" -o APT::Update::Error-Mode=any update; \
    apt-get "$@" install -y --no-install-recommends --only-upgrade \
        apt=2.8.4ubuntu0.1 libapt-pkg6.0t64=2.8.4ubuntu0.1; \
    rm -rf /var/lib/apt/lists/* /tmp/hackatlas-apt

WORKDIR /opt/hackatlas
COPY --chown=10001:10001 target/hackatlas-backend-0.1.0-SNAPSHOT.jar /opt/hackatlas/app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=20 -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/hackatlas/app.jar"]
