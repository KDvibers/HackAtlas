FROM caddy:2.11.9-alpine@sha256:0bbb055bda6793c999bde78b1b5ce3e89f829fb031af2cf50056ea1246559fee

# CVE-2026-111497 and curl advisories including CVE-2026-92392.
# Install exact fixed package versions from Alpine's signed repositories.
# This does not update Caddy's embedded Go standard library or x/net.
RUN apk add --no-cache --upgrade 'brotli-libs=1.2.1-r0' 'curl=8.23.0-r0' 'libcurl=8.23.0-r0'

# Listen on 8080/8443. The official binary's privileged-port file capability
# prevents execution with cap_drop ALL; remove it at build time, never at runtime.
RUN setcap -r /usr/bin/caddy
USER 10001:10001
