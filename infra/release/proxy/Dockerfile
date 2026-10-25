FROM caddy:2.11.7-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f

# Listen on 8080/8443. The official binary's privileged-port file capability
# prevents execution with cap_drop ALL; remove it at build time, never at runtime.
RUN setcap -r /usr/bin/caddy
USER 10001:10001
