package com.chatbotq.identityaccess.web;

import com.chatbotq.identityaccess.application.usecase.InvalidAuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Collections;
import java.util.Map;

@RestControllerAdvice(assignableTypes = AdminAuthController.class)
public class AdminAuthExceptionHandler {
    @ExceptionHandler(InvalidAuthenticationException.class)
    ResponseEntity<Map<String, String>> invalidAuthentication(InvalidAuthenticationException failure) {
        String code = "invalid credentials".equals(failure.getMessage())
            ? "invalid_credentials" : "invalid_refresh_token";
        return error(HttpStatus.UNAUTHORIZED, code);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalidRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request");
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status)
            .header("Cache-Control", "no-store")
            .body(Collections.singletonMap("code", code));
    }
}
