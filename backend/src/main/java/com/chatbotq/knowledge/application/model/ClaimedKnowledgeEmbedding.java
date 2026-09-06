package com.chatbotq.knowledge.application.model;

import java.util.UUID;

/** Immutable snapshot of one revision safely claimed for embedding. */
public final class ClaimedKnowledgeEmbedding {
    private final UUID entryId;
    private final UUID projectId;
    private final long embeddingRevision;
    private final String question;
    private final int inputTokenUpperBound;
    private final UUID claimToken;

    public ClaimedKnowledgeEmbedding(UUID entryId, long embeddingRevision, String question) {
        this(entryId, UUID.randomUUID(), embeddingRevision, question, 1, UUID.randomUUID());
    }

    public ClaimedKnowledgeEmbedding(UUID entryId, long embeddingRevision, String question, UUID claimToken) {
        this(entryId, UUID.randomUUID(), embeddingRevision, question, 1, claimToken);
    }

    public ClaimedKnowledgeEmbedding(UUID entryId, UUID projectId, long embeddingRevision, String question,
                                     int inputTokenUpperBound, UUID claimToken) {
        if (entryId == null) throw new IllegalArgumentException("entryId must not be null");
        if (projectId == null) throw new IllegalArgumentException("projectId must not be null");
        if (embeddingRevision < 1) throw new IllegalArgumentException("embeddingRevision must be positive");
        if (question == null || question.trim().isEmpty()) throw new IllegalArgumentException("question must not be blank");
        if (inputTokenUpperBound < 1) throw new IllegalArgumentException("inputTokenUpperBound must be positive");
        if (claimToken == null) throw new IllegalArgumentException("claimToken must not be null");
        this.entryId = entryId;
        this.projectId = projectId;
        this.embeddingRevision = embeddingRevision;
        this.question = question;
        this.inputTokenUpperBound = inputTokenUpperBound;
        this.claimToken = claimToken;
    }

    public UUID getEntryId() { return entryId; }
    public UUID getProjectId() { return projectId; }
    public long getEmbeddingRevision() { return embeddingRevision; }
    public String getQuestion() { return question; }
    public int getInputTokenUpperBound() { return inputTokenUpperBound; }
    public UUID getClaimToken() { return claimToken; }
}
