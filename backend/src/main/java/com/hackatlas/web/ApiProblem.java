package com.hackatlas.web;

import java.util.List;

public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String requestId,
        List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {
    }
}
