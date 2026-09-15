package com.chatbotq.knowledge.application.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class KnowledgeCsvPreview {
    private final List<KnowledgeCsvPreviewRow> rows;
    private final List<KnowledgeCsvPreviewError> fileErrors;

    public KnowledgeCsvPreview(List<KnowledgeCsvPreviewRow> rows, List<KnowledgeCsvPreviewError> fileErrors) {
        this.rows = Collections.unmodifiableList(new ArrayList<KnowledgeCsvPreviewRow>(rows));
        this.fileErrors = Collections.unmodifiableList(new ArrayList<KnowledgeCsvPreviewError>(fileErrors));
    }
    public List<KnowledgeCsvPreviewRow> getRows() { return rows; }
    public List<KnowledgeCsvPreviewError> getFileErrors() { return fileErrors; }
    public int getValidRowCount() {
        int count = 0;
        for (KnowledgeCsvPreviewRow row : rows) if (row.isValid()) count++;
        return count;
    }
    public int getInvalidRowCount() { return rows.size() - getValidRowCount(); }
}
