package com.chatbotq.knowledge.application.model;

public final class KnowledgeCsvPreviewError {
    private final String code;
    private final String message;

    public KnowledgeCsvPreviewError(String code) {
        this.code = code;
        this.message = messageFor(code);
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }

    private static String messageFor(String code) {
        if ("file_too_large".equals(code)) return "The CSV file exceeds the maximum allowed size.";
        if ("csv_limits_exceeded".equals(code)) return "The CSV file exceeds configured safety limits.";
        if ("invalid_utf8".equals(code)) return "The CSV must be valid UTF-8.";
        if ("csv_syntax".equals(code)) return "The CSV syntax is invalid.";
        if ("invalid_headers".equals(code)) return "The CSV headers are invalid.";
        if ("invalid_column_count".equals(code)) return "The row has an invalid column count.";
        if ("required".equals(code)) return "A required value is missing.";
        if ("invalid_active".equals(code)) return "Active must be true or false.";
        if ("control_character".equals(code)) return "Values must not contain control characters.";
        if ("length".equals(code)) return "A value exceeds its maximum length.";
        if ("embedding_token_limit".equals(code)) return "The question exceeds the embedding token limit.";
        if ("duplicate_external_id".equals(code)) return "The external ID is duplicated in this CSV.";
        if ("external_id_required_for_upsert".equals(code)) return "An external ID is required for upsert.";
        throw new IllegalArgumentException("unknown CSV preview error code");
    }
}
