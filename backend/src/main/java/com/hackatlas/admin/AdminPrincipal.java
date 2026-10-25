package com.hackatlas.admin;

import java.io.Serializable;
import java.util.UUID;

/** Browser identity contains no credentials or profile PII. */
public record AdminPrincipal(UUID userId) implements Serializable {
    private static final long serialVersionUID = 1L;
}
