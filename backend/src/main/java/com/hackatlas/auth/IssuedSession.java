package com.hackatlas.auth;

import java.time.Instant;
import java.util.UUID;

/** Only the response to the issuing request receives the original token. */
public record IssuedSession(String accessToken, Instant expiresAt, UUID userId) {
    @Override
    public String toString() {
        return "IssuedSession[accessToken=REDACTED]";
    }
}
