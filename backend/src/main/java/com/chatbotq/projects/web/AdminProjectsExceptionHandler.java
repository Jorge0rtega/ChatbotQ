package com.chatbotq.projects.web;

import com.chatbotq.projects.application.usecase.ForbiddenProjectAdministrationException;
import com.chatbotq.projects.application.usecase.ProjectNotFoundException;
import com.chatbotq.projects.application.usecase.SiteKeyRotationFailedException;
import com.chatbotq.projects.application.usecase.StaleSiteKeyVersionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Collections;
import java.util.Map;

@RestControllerAdvice(assignableTypes = AdminProjectsController.class)
public class AdminProjectsExceptionHandler {
    @ExceptionHandler(ForbiddenProjectAdministrationException.class)
    ResponseEntity<Map<String, String>> forbidden() {
        return error(HttpStatus.FORBIDDEN, "forbidden");
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound() {
        return error(HttpStatus.NOT_FOUND, "project_not_found");
    }

    @ExceptionHandler(StaleSiteKeyVersionException.class)
    ResponseEntity<Map<String, String>> staleSiteKeyVersion() {
        return error(HttpStatus.CONFLICT, "site_key_version_conflict");
    }

    @ExceptionHandler(SiteKeyRotationFailedException.class)
    ResponseEntity<Map<String, String>> siteKeyRotationFailed() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalidRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.PRAGMA, "no-cache").body(Collections.singletonMap("code", code));
    }
}
