package com.chatbotq.projects.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTest {

    @Test
    void createsProjectWithSafeDefaultsMatchingDatabaseBaseline() {
        UUID id = UUID.randomUUID();
        UUID siteKey = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-20T22:00:00Z");

        Project project = Project.create(id, "  Proyecto Uno  ", siteKey, now);

        assertEquals(id, project.getId());
        assertEquals("Proyecto Uno", project.getName());
        assertEquals(siteKey, project.getSiteKey());
        assertTrue(project.isActive());
        assertEquals(600, project.getConversationInactivitySeconds());
        assertEquals(90, project.getRetentionDays());
        assertEquals(6, project.getHistoryMessageLimit());
        assertEquals(4000, project.getHistoryTokenLimit());
        assertEquals(600, project.getResponseTokenLimit());
        assertEquals(0.700d, project.getSimilarityThreshold(), 0.0001d);
        assertEquals(5, project.getRetrievalTopK());
        assertFalse(project.isHandoffEnabled());
        assertEquals(3, project.getHandoffAfterQuestions());
        assertEquals(now, project.getCreatedAt());
        assertEquals(now, project.getUpdatedAt());
    }

    @Test
    void rotatesSiteKeyWithoutChangingInternalIdentity() {
        UUID id = UUID.randomUUID();
        UUID originalSiteKey = UUID.randomUUID();
        UUID replacementSiteKey = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-20T22:00:00Z");
        Instant rotatedAt = createdAt.plusSeconds(60);
        Project project = Project.create(id, "Proyecto", originalSiteKey, createdAt);

        project.rotateSiteKey(replacementSiteKey, rotatedAt);

        assertEquals(id, project.getId());
        assertEquals(replacementSiteKey, project.getSiteKey());
        assertNotEquals(originalSiteKey, project.getSiteKey());
        assertEquals(rotatedAt, project.getUpdatedAt());
    }

    @Test
    void disablesProjectWithoutDestroyingItsConfiguration() {
        Instant createdAt = Instant.parse("2026-08-20T22:00:00Z");
        Project project = Project.create(UUID.randomUUID(), "Proyecto", UUID.randomUUID(), createdAt);

        project.disable(createdAt.plusSeconds(30));

        assertFalse(project.isActive());
        assertEquals(createdAt.plusSeconds(30), project.getUpdatedAt());
    }

    @Test
    void rejectsBlankOrOversizedNames() {
        Instant now = Instant.parse("2026-08-20T22:00:00Z");

        assertThrows(IllegalArgumentException.class,
            () -> Project.create(UUID.randomUUID(), "   ", UUID.randomUUID(), now));
        assertThrows(IllegalArgumentException.class,
            () -> Project.create(UUID.randomUUID(), repeat('a', 161), UUID.randomUUID(), now));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
