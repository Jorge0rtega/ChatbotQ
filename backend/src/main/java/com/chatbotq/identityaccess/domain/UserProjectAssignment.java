package com.chatbotq.identityaccess.domain;

import java.time.Instant;
import java.util.UUID;

public final class UserProjectAssignment {
    private final UUID userId;
    private final UUID projectId;
    private final ProjectRole role;
    private final Instant assignedAt;

    private UserProjectAssignment(UUID userId, UUID projectId,
                                  ProjectRole role, Instant assignedAt) {
        this.userId = require(userId, "userId");
        this.projectId = require(projectId, "projectId");
        this.role = require(role, "role");
        this.assignedAt = require(assignedAt, "assignedAt");
    }

    public static UserProjectAssignment projectAdmin(UUID userId, UUID projectId,
                                                     Instant assignedAt) {
        return new UserProjectAssignment(
            userId, projectId, ProjectRole.PROJECT_ADMIN, assignedAt);
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public ProjectRole getRole() {
        return role;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
