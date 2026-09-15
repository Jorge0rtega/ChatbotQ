package com.chatbotq.knowledge.application.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class KnowledgeCsvPreviewRow {
    private final long rowNumber;
    private final String question;
    private final String answer;
    private final String externalId;
    private final boolean active;
    private final List<KnowledgeCsvPreviewError> errors;

    public KnowledgeCsvPreviewRow(long rowNumber, String question, String answer, String externalId, boolean active,
                                  List<KnowledgeCsvPreviewError> errors) {
        this.rowNumber = rowNumber;
        this.question = question;
        this.answer = answer;
        this.externalId = externalId;
        this.active = active;
        this.errors = Collections.unmodifiableList(new ArrayList<KnowledgeCsvPreviewError>(errors));
    }
    public long getRowNumber() { return rowNumber; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getExternalId() { return externalId; }
    public boolean isActive() { return active; }
    public List<KnowledgeCsvPreviewError> getErrors() { return errors; }
    public boolean isValid() { return errors.isEmpty(); }
}
