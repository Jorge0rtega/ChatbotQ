package com.chatbotq.knowledge.application.model;

import java.time.Instant;
import java.util.UUID;

public final class ManagedKnowledgeEntry {
    private final UUID id;
    private final UUID projectId;
    private final String question;
    private final String answer;
    private final String externalId;
    private final boolean active;
    private final String embeddingStatus;
    private final long embeddingRevision;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    public ManagedKnowledgeEntry(UUID id, UUID projectId, String question, String answer, String externalId,
                                 boolean active, String embeddingStatus, long embeddingRevision, long version,
                                 Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.question = question;
        this.answer = answer;
        this.externalId = externalId;
        this.active = active;
        this.embeddingStatus = embeddingStatus;
        this.embeddingRevision = embeddingRevision;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getExternalId() { return externalId; }
    public boolean isActive() { return active; }
    public String getEmbeddingStatus() { return embeddingStatus; }
    public long getEmbeddingRevision() { return embeddingRevision; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
