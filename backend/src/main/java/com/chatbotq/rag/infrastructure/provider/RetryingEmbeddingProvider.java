package com.chatbotq.rag.infrastructure.provider;

import com.chatbotq.rag.application.port.EmbeddingProvider;

import java.io.IOException;

/** Bounded, deterministic retry policy for outbound embedding requests. */
public final class RetryingEmbeddingProvider implements EmbeddingProvider {
    private final EmbeddingProvider delegate;
    private final Sleeper sleeper;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public RetryingEmbeddingProvider(EmbeddingProvider delegate, Sleeper sleeper, int maxAttempts,
                                     long initialBackoffMs, long maxBackoffMs) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        if (sleeper == null) throw new IllegalArgumentException("sleeper must not be null");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (initialBackoffMs < 1) throw new IllegalArgumentException("initialBackoffMs must be positive");
        if (maxBackoffMs < initialBackoffMs) throw new IllegalArgumentException("maxBackoffMs must not be smaller than initialBackoffMs");
        this.delegate = delegate;
        this.sleeper = sleeper;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    @Override
    public float[] embed(String input) throws IOException {
        long delayMs = initialBackoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return delegate.embed(input);
            } catch (IOException failure) {
                if (attempt == maxAttempts || !isTransient(failure)) throw failure;
                sleep(delayMs);
                delayMs = nextDelay(delayMs);
            }
        }
        throw new AssertionError("unreachable");
    }

    private static boolean isTransient(IOException failure) {
        if (!(failure instanceof OpenAiEmbeddingHttpException)) return true;
        int status = ((OpenAiEmbeddingHttpException) failure).getStatusCode();
        return status == 429 || status >= 500;
    }

    private void sleep(long delayMs) throws IOException {
        try {
            sleeper.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Embedding retry interrupted", interrupted);
        }
    }

    private long nextDelay(long delayMs) {
        if (delayMs >= maxBackoffMs) return maxBackoffMs;
        return Math.min(maxBackoffMs, delayMs > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : delayMs * 2);
    }

    public interface Sleeper {
        void sleep(long delayMs) throws InterruptedException;
    }
}
