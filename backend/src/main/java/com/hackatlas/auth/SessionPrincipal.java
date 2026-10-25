package com.hackatlas.auth;

import java.util.UUID;

/** The authenticated identity, derived exclusively from the server-side session. */
public record SessionPrincipal(UUID userId, UUID sessionId) {
}
