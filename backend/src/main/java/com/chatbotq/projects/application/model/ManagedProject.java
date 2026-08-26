package com.chatbotq.projects.application.model;

import java.time.Instant;
import java.util.UUID;

public final class ManagedProject {
    private final UUID id;
    private final String name;
    private final boolean active;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ManagedProject(UUID id, String name, boolean active, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public String getStatus() { return active ? "ACTIVE" : "DISABLED"; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
