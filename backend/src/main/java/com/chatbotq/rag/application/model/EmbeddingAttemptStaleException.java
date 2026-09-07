package com.chatbotq.rag.application.model;

import java.io.IOException;

/** The embedding claim lost its fenced ownership before outbound provider I/O. */
public final class EmbeddingAttemptStaleException extends IOException {
    public EmbeddingAttemptStaleException() {
        super("Embedding attempt claim is stale");
    }
}
