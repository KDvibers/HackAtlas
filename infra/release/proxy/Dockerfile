FROM golang:1.27.4-alpine3.24@sha256:0a5f7fe796599ff487605987432e7619ae45beeb2fba4c2ce0b907879932aebf AS builder

ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOWORK=off \
    GOPROXY=https://proxy.golang.org GOSUMDB=sum.golang.org
WORKDIR /src
COPY caddy-build/go.mod caddy-build/go.sum ./
RUN go mod download && go mod verify
COPY caddy-build/main.go ./
RUN go build -mod=readonly -trimpath -buildvcs=false -ldflags="-w -s" -o /out/caddy .

FROM caddy:2.11.9-alpine@sha256:0bbb055bda6793c999bde78b1b5ce3e89f829fb031af2cf50056ea1246559fee

# Fixed OS packages from Alpine's signed repositories.
RUN apk add --no-cache --upgrade 'brotli-libs=1.2.1-r0' 'curl=8.23.0-r0' 'libcurl=8.23.0-r0'

# Use the compiled binary with no privileged-port capability on 8080/8443.
COPY --from=builder /out/caddy /usr/bin/caddy
RUN test -z "$(getcap /usr/bin/caddy)"
USER 10001:10001
