package com.hackatlas.web;

/** Only deliberately public messages belong here, never SQL or credential details. */
public class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final long retryAfter;

    public ApiFailure(int status, String code, String safeMessage) {
        this(status, code, safeMessage, 0);
    }

    public ApiFailure(int status, String code, String safeMessage, long retryAfter) {
        super(safeMessage);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public int status() { return status; }
    public String code() { return code; }
    public long retryAfter() { return retryAfter; }
}
