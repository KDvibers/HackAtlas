package com.hackatlas.web;

import java.util.List;

final class CatalogValidationException extends RuntimeException {
    private final List<ApiProblem.FieldError> fieldErrors;

    CatalogValidationException(List<ApiProblem.FieldError> fieldErrors) {
        super("Invalid catalogue parameters");
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    List<ApiProblem.FieldError> fieldErrors() {
        return fieldErrors;
    }
}
