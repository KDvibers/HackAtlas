FROM caddy:2.11.7-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f

# CVE-2026-111497 and curl advisories including CVE-2026-92392.
# Install exact fixed package versions from Alpine's signed repositories.
# This does not update Caddy's embedded Go standard library or x/net.
RUN apk add --no-cache --upgrade 'brotli-libs=1.2.1-r0' 'curl=8.23.0-r0' 'libcurl=8.23.0-r0'

# Listen on 8080/8443. The official binary's privileged-port file capability
# prevents execution with cap_drop ALL; remove it at build time, never at runtime.
RUN setcap -r /usr/bin/caddy
USER 10001:10001
