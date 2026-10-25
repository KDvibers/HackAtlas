package com.hackatlas.security;

import java.io.IOException;
import java.util.List;

import com.hackatlas.web.ApiFailure;
import com.hackatlas.web.DataFailures;
import com.hackatlas.web.ApiProblem;
import com.hackatlas.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

@Component
final class SecurityProblems {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityProblems.class);
    private final JsonMapper json;

    SecurityProblems(JsonMapper json) {
        this.json = json;
    }

    void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, 401, "AUTH_REQUIRED", "Требуется действующая сессия.", 0);
    }

    void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, 403, "FORBIDDEN", "Доступ запрещён.", 0);
    }

    void badRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, 400, "VALIDATION_ERROR", "Проверьте параметры запроса.", 0);
    }

    void failure(HttpServletRequest request, HttpServletResponse response, RuntimeException exception)
            throws IOException {
        if (exception instanceof ApiFailure failure) {
            write(request, response, failure.status(), failure.code(), failure.getMessage(), failure.retryAfter());
            return;
        }
        // Only the failure class and request ID are safe to log, never SQL/parameters/messages.
        LOG.error("Authentication request failed: requestId={}, exceptionType={}",
                RequestIdFilter.requestId(request), exception.getClass().getSimpleName());
        if (DataFailures.unavailable(exception)) {
            write(request, response, 503, "SERVICE_UNAVAILABLE", "Сервис временно недоступен.", 0);
        } else {
            write(request, response, 500, "INTERNAL_ERROR", "Не удалось обработать запрос.", 0);
        }
    }

    private void write(HttpServletRequest request, HttpServletResponse response, int status,
                       String code, String detail, long retryAfter) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        if (status == 429) {
            response.setHeader("Retry-After", Long.toString(Math.max(1, retryAfter)));
        }
        String title = switch (status) {
            case 400 -> "Некорректный запрос";
            case 401 -> "Требуется авторизация";
            case 403 -> "Доступ запрещён";
            case 429 -> "Превышен лимит запросов";
            case 503 -> "Сервис временно недоступен";
            default -> "Ошибка сервера";
        };
        response.getWriter().write(json.writeValueAsString(new ApiProblem(
                "about:blank", title, status, detail, request.getRequestURI(), code,
                RequestIdFilter.requestId(request), List.of())));
    }
}
