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
        UUID userId = users.newUserId();

        assertNotNull(projectId);
        assertNotNull(siteKey);
        assertNotNull(userId);
        assertNotEquals(projectId, siteKey);
        assertNotEquals(projectId, userId);
    }
}
