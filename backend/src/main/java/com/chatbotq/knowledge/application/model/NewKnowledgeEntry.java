package com.chatbotq.knowledge.application.model;

import java.util.UUID;

public final class NewKnowledgeEntry {
    private final UUID id;
    private final String question;
    private final String answer;
    private final String externalId;
    private final boolean active;
    private final int embeddingInputTokenUpperBound;

    public NewKnowledgeEntry(UUID id, String question, String answer, String externalId, boolean active,
                             int embeddingInputTokenUpperBound) {
        if (id == null || question == null || answer == null || embeddingInputTokenUpperBound < 1) {
            throw new IllegalArgumentException("new knowledge entry fields must be valid");
        }
        this.id = id;
        this.question = question;
        this.answer = answer;
        this.externalId = externalId;
        this.active = active;
        this.embeddingInputTokenUpperBound = embeddingInputTokenUpperBound;
    }

    public UUID getId() { return id; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getExternalId() { return externalId; }
    public boolean isActive() { return active; }
    public int getEmbeddingInputTokenUpperBound() { return embeddingInputTokenUpperBound; }
}
