package com.chatbotq.knowledge.application.model;

import java.util.UUID;

/** Immutable snapshot of one revision safely claimed for embedding. */
public final class ClaimedKnowledgeEmbedding {
    private final UUID entryId;
    private final long embeddingRevision;
    private final String question;
    private final UUID claimToken;

    public ClaimedKnowledgeEmbedding(UUID entryId, long embeddingRevision, String question) {
        this(entryId, embeddingRevision, question, UUID.randomUUID());
    }

    public ClaimedKnowledgeEmbedding(UUID entryId, long embeddingRevision, String question, UUID claimToken) {
        if (entryId == null) throw new IllegalArgumentException("entryId must not be null");
        if (embeddingRevision < 1) throw new IllegalArgumentException("embeddingRevision must be positive");
        if (question == null || question.trim().isEmpty()) throw new IllegalArgumentException("question must not be blank");
        if (claimToken == null) throw new IllegalArgumentException("claimToken must not be null");
        this.entryId = entryId;
        this.embeddingRevision = embeddingRevision;
        this.question = question;
        this.claimToken = claimToken;
    }

    public UUID getEntryId() { return entryId; }
    public long getEmbeddingRevision() { return embeddingRevision; }
    public String getQuestion() { return question; }
    public UUID getClaimToken() { return claimToken; }
}
