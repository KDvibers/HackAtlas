package com.hackatlas.admin;

import com.hackatlas.web.ApiFailure;
import com.hackatlas.web.DataFailures;
import com.hackatlas.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.ui.Model;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@ControllerAdvice(assignableTypes=AdminController.class)
public class AdminExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AdminExceptionHandler.class);

    @ExceptionHandler(ApiFailure.class)
    String known(ApiFailure failure, HttpServletResponse response, Model model) {
        response.setStatus(failure.status());
        model.addAttribute("message",failure.getMessage());
        return "admin/error";
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,MethodArgumentTypeMismatchException.class})
    String invalid(HttpServletResponse response, Model model) {
        response.setStatus(400);
        model.addAttribute("message","Проверьте поля формы.");
        return "admin/error";
    }

    @ExceptionHandler(Exception.class)
    String unexpected(Exception exception,HttpServletRequest request,HttpServletResponse response,Model model) {
        LOG.error("Admin request failed: requestId={}, exceptionType={}",RequestIdFilter.requestId(request),exception.getClass().getSimpleName());
        response.setStatus(DataFailures.unavailable(exception) ? 503 : 500);
        model.addAttribute("message","Не удалось выполнить действие. Повторите позже.");
        return "admin/error";
    }
}
