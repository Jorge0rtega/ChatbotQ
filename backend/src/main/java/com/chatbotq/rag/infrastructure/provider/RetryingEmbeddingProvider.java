package com.chatbotq.rag.infrastructure.provider;

import com.chatbotq.rag.application.port.EmbeddingProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Bounded retry policy with optional, low-cardinality operational metrics. */
public final class RetryingEmbeddingProvider implements EmbeddingProvider {
    private final EmbeddingProvider delegate;
    private final Sleeper sleeper;
    private final MeterRegistry metrics;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public RetryingEmbeddingProvider(EmbeddingProvider delegate, Sleeper sleeper, int maxAttempts,
                                     long initialBackoffMs, long maxBackoffMs) {
        this(delegate, sleeper, null, maxAttempts, initialBackoffMs, maxBackoffMs);
    }

    public RetryingEmbeddingProvider(EmbeddingProvider delegate, Sleeper sleeper, MeterRegistry metrics,
                                     int maxAttempts, long initialBackoffMs, long maxBackoffMs) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        if (sleeper == null) throw new IllegalArgumentException("sleeper must not be null");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (initialBackoffMs < 1) throw new IllegalArgumentException("initialBackoffMs must be positive");
        if (maxBackoffMs < initialBackoffMs) throw new IllegalArgumentException("maxBackoffMs must not be smaller than initialBackoffMs");
        this.delegate = delegate;
        this.sleeper = sleeper;
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    @Override
    public float[] embed(String input) throws IOException {
        long startedAt = System.nanoTime();
        long delayMs = initialBackoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                float[] embedding = delegate.embed(input);
                recordRequest("success", startedAt);
                return embedding;
            } catch (IOException failure) {
                String category = category(failure);
                if (attempt == maxAttempts || !isTransient(failure)) {
                    recordRequest("failure", startedAt);
                    throw failure;
                }
                recordRetry(category);
                try {
                    sleep(delayMs);
                } catch (IOException interrupted) {
                    recordRequest("failure", startedAt);
                    throw interrupted;
                }
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

    private static String category(IOException failure) {
        if (!(failure instanceof OpenAiEmbeddingHttpException)) return "io";
        int status = ((OpenAiEmbeddingHttpException) failure).getStatusCode();
        if (status == 429) return "rate_limited";
        if (status >= 500) return "server_error";
        if (status >= 300) return "redirect";
        return "client_error";
    }

    private void recordRetry(String category) {
        if (metrics != null) {
            Counter.builder("chatbotq.embedding.retry.attempts").tag("category", category).register(metrics).increment();
        }
    }

    private void recordRequest(String outcome, long startedAt) {
        if (metrics != null) {
            Counter.builder("chatbotq.embedding.requests").tag("outcome", outcome).register(metrics).increment();
            Timer.builder("chatbotq.embedding.duration").tag("outcome", outcome).register(metrics)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
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
