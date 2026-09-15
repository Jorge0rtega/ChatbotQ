package com.chatbotq.knowledge.application.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class KnowledgeCsvParsedRow {
    private final long rowNumber;
    private final List<String> values;

    public KnowledgeCsvParsedRow(long rowNumber, List<String> values) {
        this.rowNumber = rowNumber;
        this.values = Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public long getRowNumber() { return rowNumber; }
    public List<String> getValues() { return values; }
}
