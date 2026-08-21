package com.chatbotq.rag.application.model;

import java.util.UUID;

public final class RetrievalCandidate {
    private final UUID knowledgeEntryId;
    private final String question;
    private final String answer;
    private final double similarityScore;

    public RetrievalCandidate(UUID knowledgeEntryId, String question, String answer, double similarityScore) {
        this.knowledgeEntryId = knowledgeEntryId;
        this.question = question;
        this.answer = answer;
        this.similarityScore = similarityScore;
    }

    public UUID getKnowledgeEntryId() {
        return knowledgeEntryId;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public double getSimilarityScore() {
        return similarityScore;
    }
}
