package com.chatbotq.identityaccess.web;

import com.chatbotq.identityaccess.application.usecase.AdminUserConflictException;
import com.chatbotq.identityaccess.application.usecase.AdminUserNotFoundException;
import com.chatbotq.identityaccess.application.usecase.ForbiddenAdminUserAdministrationException;
import com.chatbotq.identityaccess.application.usecase.AssignedProjectNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Collections;
import java.util.Map;

@RestControllerAdvice(assignableTypes = AdminUsersController.class)
public class AdminUsersExceptionHandler {
    @ExceptionHandler(ForbiddenAdminUserAdministrationException.class)
    ResponseEntity<Map<String, String>> forbidden() { return error(HttpStatus.FORBIDDEN, "forbidden"); }

    @ExceptionHandler(AdminUserNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "admin_user_not_found"); }

    @ExceptionHandler(AssignedProjectNotFoundException.class)
    ResponseEntity<Map<String, String>> projectNotFound() { return error(HttpStatus.NOT_FOUND, "project_not_found"); }

    @ExceptionHandler(AdminUserConflictException.class)
    ResponseEntity<Map<String, String>> conflict() { return error(HttpStatus.CONFLICT, "admin_user_conflict"); }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception ignored) { return error(HttpStatus.BAD_REQUEST, "invalid_request"); }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Collections.singletonMap("code", code));
    }
}
