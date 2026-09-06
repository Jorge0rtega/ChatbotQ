package com.chatbotq.knowledge.application.model;

import java.nio.charset.StandardCharsets;

/**
 * Conservative deterministic upper bound for input tokens: any UTF-8 token has
 * at least one byte, so charging bytes can never under-reserve token usage.
 */
public final class EmbeddingInputTokenUpperBound {
    private EmbeddingInputTokenUpperBound() {
    }

    public static int forQuestion(String question) {
        if (question == null) throw new IllegalArgumentException("question must not be null");
        return question.getBytes(StandardCharsets.UTF_8).length;
    }
}
