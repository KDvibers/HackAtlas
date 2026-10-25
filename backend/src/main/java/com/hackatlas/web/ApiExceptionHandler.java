package com.hackatlas.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CatalogController.class)
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CatalogValidationException.class)
    ResponseEntity<ApiProblem> invalid(CatalogValidationException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Некорректные параметры",
                "Проверьте параметры запроса.", exception.fieldErrors(), request);
    }

    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<ApiProblem> malformedParameters(HttpServletRequest request) {
        // The servlet container can reject malformed query names before our parser runs.
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Некорректные параметры",
                "Проверьте параметры запроса.",
                List.of(new ApiProblem.FieldError("parameters", "Некорректная строка параметров запроса.")), request);
    }

    @ExceptionHandler(CatalogNotFoundException.class)
    ResponseEntity<ApiProblem> notFound(HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Событие не найдено",
                "Событие не найдено или недоступно.", List.of(), request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiProblem> unavailable(Exception exception, HttpServletRequest request) {
        logFailure(exception, request);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Сервис временно недоступен",
                "Попробуйте повторить запрос позже.", List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblem> unexpected(Exception exception, HttpServletRequest request) {
        logFailure(exception, request);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Ошибка сервера",
                "Не удалось обработать запрос.", List.of(), request);
    }

    private static void logFailure(Exception exception, HttpServletRequest request) {
        // Do not log request parameters, exception messages, credentials, or SQL values.
        LOG.error("Catalogue request failed: requestId={}, exceptionType={}",
                RequestIdFilter.requestId(request), exception.getClass().getSimpleName());
    }

    private static ResponseEntity<ApiProblem> problem(
            HttpStatus status, String code, String title, String detail,
            List<ApiProblem.FieldError> fieldErrors, HttpServletRequest request
    ) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(new ApiProblem(
                "about:blank", title, status.value(), detail, request.getRequestURI(),
                code, RequestIdFilter.requestId(request), fieldErrors
        ));
    }
}
