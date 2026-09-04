package com.chatbotq.knowledge.web;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminKnowledgeExceptionHandlerTest {
    @Test
    void treatsDuplicateKeyWithNullSqlMessageAsGenericInternalError() {
        DuplicateKeyException duplicate = new DuplicateKeyException("duplicate",
            new SQLException(null, "23505"));

        ResponseEntity<java.util.Map<String, String>> response =
            new AdminKnowledgeExceptionHandler().duplicateExternalId(duplicate);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(java.util.Collections.singletonMap("code", "internal_server_error"), response.getBody());
    }
}