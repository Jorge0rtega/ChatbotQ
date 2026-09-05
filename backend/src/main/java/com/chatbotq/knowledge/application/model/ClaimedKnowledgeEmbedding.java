package com.chatbotq.knowledge.application.model;

import java.util.UUID;

/** Immutable snapshot of one revision safely claimed for embedding. */
public final class ClaimedKnowledgeEmbedding {
    private final UUID entryId;
    private final long embeddingRevision;
    private final String question;

    public ClaimedKnowledgeEmbedding(UUID entryId, long embeddingRevision, String question) {
        if (entryId == null) throw new IllegalArgumentException("entryId must not be null");
        if (embeddingRevision < 1) throw new IllegalArgumentException("embeddingRevision must be positive");
        if (question == null || question.trim().isEmpty()) throw new IllegalArgumentException("question must not be blank");
        this.entryId = entryId;
        this.embeddingRevision = embeddingRevision;
        this.question = question;
    }

    public UUID getEntryId() { return entryId; }
    public long getEmbeddingRevision() { return embeddingRevision; }
    public String getQuestion() { return question; }
}
