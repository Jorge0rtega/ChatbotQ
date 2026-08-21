package com.chatbotq.identityaccess.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserProjectAssignmentTest {

    @Test
    void assignsProjectAdminToExactlyOneUserAndProject() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Instant assignedAt = Instant.parse("2026-08-20T22:00:00Z");

        UserProjectAssignment assignment = UserProjectAssignment.projectAdmin(
            userId, projectId, assignedAt);

        assertEquals(userId, assignment.getUserId());
        assertEquals(projectId, assignment.getProjectId());
        assertEquals(ProjectRole.PROJECT_ADMIN, assignment.getRole());
        assertEquals(assignedAt, assignment.getAssignedAt());
    }

    @Test
    void rejectsMissingIdentityOrProject() {
        assertThrows(IllegalArgumentException.class,
            () -> UserProjectAssignment.projectAdmin(null, UUID.randomUUID(), Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
            () -> UserProjectAssignment.projectAdmin(UUID.randomUUID(), null, Instant.EPOCH));
    }
}
