package com.hackatlas.admin;

import org.springframework.security.core.AuthenticationException;

public final class AdminLoginException extends AuthenticationException {
    private final long retryAfter;
    private final int status;

    public AdminLoginException(long retryAfter) {
        this(429, retryAfter);
    }

    public AdminLoginException(int status, long retryAfter) {
        super("Authentication could not be completed");
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public long retryAfter() { return retryAfter; }
    public int status() { return status; }
}
