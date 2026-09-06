package com.chatbotq.knowledge.application.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingInputTokenUpperBoundTest {
    @Test
    void countsUtf8BytesAsAConservativeDeterministicTokenUpperBound() {
        assertEquals(5, EmbeddingInputTokenUpperBound.forQuestion("hours"));
        assertEquals(2, EmbeddingInputTokenUpperBound.forQuestion("ñ"));
        assertEquals(4, EmbeddingInputTokenUpperBound.forQuestion("🙂"));
    }

    @Test
    void rejectsNullQuestion() {
        assertThrows(IllegalArgumentException.class, () -> EmbeddingInputTokenUpperBound.forQuestion(null));
    }
}
