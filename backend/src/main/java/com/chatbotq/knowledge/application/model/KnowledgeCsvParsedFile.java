package com.chatbotq.knowledge.application.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class KnowledgeCsvParsedFile {
    private final List<String> headers;
    private final List<KnowledgeCsvParsedRow> rows;
    private final List<KnowledgeCsvPreviewError> fileErrors;

    public KnowledgeCsvParsedFile(List<String> headers, List<KnowledgeCsvParsedRow> rows,
                                  List<KnowledgeCsvPreviewError> fileErrors) {
        this.headers = Collections.unmodifiableList(new ArrayList<String>(headers));
        this.rows = Collections.unmodifiableList(new ArrayList<KnowledgeCsvParsedRow>(rows));
        this.fileErrors = Collections.unmodifiableList(new ArrayList<KnowledgeCsvPreviewError>(fileErrors));
    }

    public List<String> getHeaders() { return headers; }
    public List<KnowledgeCsvParsedRow> getRows() { return rows; }
    public List<KnowledgeCsvPreviewError> getFileErrors() { return fileErrors; }
}
