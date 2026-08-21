package com.chatbotq.infrastructure.identity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UuidIdentityGeneratorsTest {

    @Test
    void generatesNonNullDistinctProjectAndUserIdentifiers() {
        UuidProjectIdentityGenerator projects = new UuidProjectIdentityGenerator();
        UuidAdminUserIdentityGenerator users = new UuidAdminUserIdentityGenerator();

        UUID projectId = projects.newProjectId();
        UUID siteKey = projects.newSiteKey();
        UUID allowedOriginId = projects.newAllowedOriginId();
        UUID userId = users.newUserId();

        assertNotNull(projectId);
        assertNotNull(siteKey);
        assertNotNull(allowedOriginId);
        assertNotNull(userId);
        assertNotEquals(projectId, siteKey);
        assertNotEquals(projectId, allowedOriginId);
        assertNotEquals(projectId, userId);
    }
}
