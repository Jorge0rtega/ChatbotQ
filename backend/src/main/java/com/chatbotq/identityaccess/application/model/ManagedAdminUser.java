package com.chatbotq.identityaccess.application.model;

import java.time.Instant;
import java.util.UUID;

public final class ManagedAdminUser {
    private final UUID id;
    private final String email;
    private final String role;
    private final String status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ManagedAdminUser(UUID id, String email, String role, String status,
                            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.email = email;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
