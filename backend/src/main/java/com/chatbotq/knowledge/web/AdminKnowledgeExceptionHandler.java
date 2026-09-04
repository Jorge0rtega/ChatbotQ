package com.chatbotq.knowledge.web;

import com.chatbotq.knowledge.application.usecase.ForbiddenKnowledgeAdministrationException;
import com.chatbotq.projects.application.usecase.ProjectNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Collections;
import java.util.Map;
import java.sql.SQLException;

@RestControllerAdvice(assignableTypes = AdminKnowledgeController.class)
public final class AdminKnowledgeExceptionHandler {
    @ExceptionHandler(ForbiddenKnowledgeAdministrationException.class)
    ResponseEntity<Map<String, String>> forbidden() { return error(HttpStatus.FORBIDDEN, "forbidden"); }

    @ExceptionHandler(ProjectNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound() { return error(HttpStatus.NOT_FOUND, "project_not_found"); }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Map<String, String>> duplicateExternalId(DuplicateKeyException duplicate) {
        if (isKnowledgeExternalIdConflict(duplicate)) {
            return error(HttpStatus.CONFLICT, "knowledge_external_id_conflict");
        }
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error");
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

    private static boolean isKnowledgeExternalIdConflict(DuplicateKeyException duplicate) {
        Throwable current = duplicate;
        while (current != null) {
            if (current instanceof SQLException) {
                SQLException sqlException = (SQLException) current;
                String message = sqlException.getMessage();
                return "23505".equals(sqlException.getSQLState())
                    && message != null
                    && message.contains("constraint \"uq_knowledge_external_id\"");
            }
            current = current.getCause();
        }
        return false;
    }
}
