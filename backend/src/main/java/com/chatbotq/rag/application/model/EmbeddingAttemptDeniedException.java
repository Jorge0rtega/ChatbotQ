package com.chatbotq.rag.application.model;

import java.io.IOException;

/** The budget gate denied an outbound request before any provider I/O. */
public final class EmbeddingAttemptDeniedException extends IOException {
    public EmbeddingAttemptDeniedException() {
        super("Embedding attempt budget was denied");
    }
}
