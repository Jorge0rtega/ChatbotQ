package com.chatbotq.rag.infrastructure.provider;

import java.io.IOException;

/** HTTP failure metadata for retry classification; intentionally excludes response bodies. */
final class OpenAiEmbeddingHttpException extends IOException {
    private final int statusCode;

    OpenAiEmbeddingHttpException(int statusCode) {
        super("OpenAI embeddings request failed with HTTP " + statusCode);
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be a valid HTTP status");
        }
        this.statusCode = statusCode;
    }

    int getStatusCode() {
        return statusCode;
    }
}
