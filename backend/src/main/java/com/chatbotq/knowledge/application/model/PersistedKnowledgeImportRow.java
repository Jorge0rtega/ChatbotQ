package com.chatbotq.knowledge.application.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PersistedKnowledgeImportRow {
    private final int rowNumber;
    private final String question;
    private final String answer;
    private final String externalId;
    private final boolean active;
    private final String status;
    private final List<String> errors;

    public PersistedKnowledgeImportRow(int rowNumber, String question, String answer, String externalId, boolean active,
                                       String status, List<String> errors) {
        this.rowNumber = rowNumber;
        this.question = question;
        this.answer = answer;
        this.externalId = externalId;
        this.active = active;
        this.status = status;
        this.errors = Collections.unmodifiableList(new ArrayList<String>(errors));
    }
    public int getRowNumber() { return rowNumber; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getExternalId() { return externalId; }
    public boolean isActive() { return active; }
    public String getStatus() { return status; }
    public List<String> getErrors() { return errors; }
}
