package com.chatbotq.knowledge.application.model;

import java.util.UUID;

public final class ClaimedKnowledgeImportRow {
    private final UUID jobId;
    private final int rowNumber;
    private final String question;
    private final String answer;
    private final String externalId;
    private final boolean active;

    public ClaimedKnowledgeImportRow(UUID jobId, int rowNumber, String question, String answer, String externalId, boolean active) {
        if (jobId == null || rowNumber < 1 || question == null || answer == null) {
            throw new IllegalArgumentException("claim fields must be valid");
        }
        this.jobId = jobId;
        this.rowNumber = rowNumber;
        this.question = question;
        this.answer = answer;
        this.externalId = externalId;
        this.active = active;
    }

    public UUID getJobId() { return jobId; }
    public int getRowNumber() { return rowNumber; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getExternalId() { return externalId; }
    public boolean isActive() { return active; }
}
