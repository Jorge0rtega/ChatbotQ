package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.EmbeddingInputTokenUpperBound;
import com.chatbotq.knowledge.application.model.KnowledgeCsvImportStrategy;
import com.chatbotq.knowledge.application.model.KnowledgeCsvParsedFile;
import com.chatbotq.knowledge.application.model.KnowledgeCsvParsedRow;
import com.chatbotq.knowledge.application.model.KnowledgeCsvPreview;
import com.chatbotq.knowledge.application.model.KnowledgeCsvPreviewError;
import com.chatbotq.knowledge.application.model.KnowledgeCsvPreviewRow;
import com.chatbotq.knowledge.application.model.KnowledgeTextNormalizer;
import com.chatbotq.knowledge.application.port.KnowledgeCsvParser;
import com.chatbotq.knowledge.application.port.KnowledgeCsvPreviewPort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CsvKnowledgePreviewUseCase implements KnowledgeCsvPreviewPort {
    private static final byte[] TEMPLATE = new byte[] {
        'q', 'u', 'e', 's', 't', 'i', 'o', 'n', ',', 'a', 'n', 's', 'w', 'e', 'r', ',',
        'e', 'x', 't', 'e', 'r', 'n', 'a', 'l', '_', 'i', 'd', ',', 'a', 'c', 't', 'i', 'v', 'e', '\r', '\n'
    };
    private static final List<List<String>> ACCEPTED_HEADERS = Arrays.asList(
        Arrays.asList("question", "answer"), Arrays.asList("question", "answer", "external_id"),
        Arrays.asList("question", "answer", "active"), Arrays.asList("question", "answer", "external_id", "active"));
    private final KnowledgeCsvParser parser;
    private final int maxEmbeddingInputTokensPerEntry;

    public CsvKnowledgePreviewUseCase(KnowledgeCsvParser parser, int maxEmbeddingInputTokensPerEntry) {
        if (parser == null) throw new IllegalArgumentException("parser must not be null");
        if (maxEmbeddingInputTokensPerEntry < 1) throw new IllegalArgumentException("maxEmbeddingInputTokensPerEntry must be positive");
        this.parser = parser;
        this.maxEmbeddingInputTokensPerEntry = maxEmbeddingInputTokensPerEntry;
    }

    @Override public byte[] template() { return TEMPLATE.clone(); }

    @Override
    public KnowledgeCsvPreview preview(byte[] csvBytes, KnowledgeCsvImportStrategy strategy) {
        if (csvBytes == null || strategy == null) throw new IllegalArgumentException("csvBytes and strategy must not be null");
        KnowledgeCsvParsedFile parsed = parser.parse(csvBytes);
        if (!parsed.getFileErrors().isEmpty()) return new KnowledgeCsvPreview(new ArrayList<KnowledgeCsvPreviewRow>(), parsed.getFileErrors());
        if (!ACCEPTED_HEADERS.contains(parsed.getHeaders())) return fileError("invalid_headers");
        List<MutableRow> rows = new ArrayList<MutableRow>();
        for (KnowledgeCsvParsedRow record : parsed.getRows()) rows.add(validate(record, parsed.getHeaders(), strategy));
        invalidateDuplicates(rows);
        return completed(rows);
    }

    private MutableRow validate(KnowledgeCsvParsedRow record, List<String> headers, KnowledgeCsvImportStrategy strategy) {
        MutableRow row = new MutableRow(record.getRowNumber());
        List<String> values = record.getValues();
        if (values.size() != headers.size()) { row.error("invalid_column_count"); return row; }
        String question = values.get(0);
        String answer = values.get(1);
        row.question = normalize(question, "question", 2000, false, row);
        row.answer = normalize(answer, "answer", 8000, false, row);
        int externalIdIndex = headers.indexOf("external_id");
        row.externalId = externalIdIndex < 0 ? null : normalize(questionOrEmpty(values.get(externalIdIndex)), "externalId", 255, true, row);
        if (strategy == KnowledgeCsvImportStrategy.UPSERT && row.externalId == null) row.error("external_id_required_for_upsert");
        int activeIndex = headers.indexOf("active");
        if (activeIndex >= 0) {
            String active = values.get(activeIndex);
            if (active.length() == 0) row.active = true;
            else if ("true".equals(active)) row.active = true;
            else if ("false".equals(active)) row.active = false;
            else row.error("invalid_active");
        }
        if (row.question != null && EmbeddingInputTokenUpperBound.forQuestion(row.question) > maxEmbeddingInputTokensPerEntry) row.error("embedding_token_limit");
        return row;
    }

    private static String questionOrEmpty(String value) { return value == null ? "" : value; }
    private static String normalize(String value, String name, int maximum, boolean optional, MutableRow row) {
        if (optional && isUnicodeBlank(value)) return null;
        if (!optional && isUnicodeBlank(value)) { row.error("required"); return null; }
        try { return KnowledgeTextNormalizer.normalize(value, name, maximum, optional); }
        catch (IllegalArgumentException failure) {
            if (failure.getMessage().contains("control characters")) row.error("control_character");
            else row.error("length");
            return null;
        }
    }
    private static boolean isUnicodeBlank(String value) {
        if (value == null || value.length() == 0) return true;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (!(Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))) return false;
            index += Character.charCount(codePoint);
        }
        return true;
    }
    private static void invalidateDuplicates(List<MutableRow> rows) {
        Map<String, List<MutableRow>> matches = new HashMap<String, List<MutableRow>>();
        for (MutableRow row : rows) if (row.externalId != null) {
            List<MutableRow> same = matches.get(row.externalId);
            if (same == null) { same = new ArrayList<MutableRow>(); matches.put(row.externalId, same); }
            same.add(row);
        }
        for (List<MutableRow> same : matches.values()) if (same.size() > 1) for (MutableRow row : same) row.error("duplicate_external_id");
    }
    private static KnowledgeCsvPreview completed(List<MutableRow> rows) {
        List<KnowledgeCsvPreviewRow> completed = new ArrayList<KnowledgeCsvPreviewRow>();
        for (MutableRow row : rows) completed.add(new KnowledgeCsvPreviewRow(row.number, row.question, row.answer, row.externalId, row.active, row.errors));
        return new KnowledgeCsvPreview(completed, new ArrayList<KnowledgeCsvPreviewError>());
    }
    private static KnowledgeCsvPreview fileError(String code) {
        return new KnowledgeCsvPreview(new ArrayList<KnowledgeCsvPreviewRow>(), Arrays.asList(new KnowledgeCsvPreviewError(code)));
    }
    private static final class MutableRow {
        private final long number; private String question; private String answer; private String externalId; private boolean active = true;
        private final List<KnowledgeCsvPreviewError> errors = new ArrayList<KnowledgeCsvPreviewError>();
        private MutableRow(long number) { this.number = number; }
        private void error(String code) { errors.add(new KnowledgeCsvPreviewError(code)); }
    }
}
