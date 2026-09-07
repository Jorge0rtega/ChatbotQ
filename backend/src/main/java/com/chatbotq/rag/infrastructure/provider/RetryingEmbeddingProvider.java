package com.chatbotq.rag.infrastructure.provider;

import com.chatbotq.rag.application.model.EmbeddingAttemptDeniedException;
import com.chatbotq.rag.application.model.EmbeddingAttemptGate;
import com.chatbotq.rag.application.model.EmbeddingAttemptStaleException;
import com.chatbotq.rag.application.model.EmbeddingAttemptSettlementUncertainException;
import com.chatbotq.rag.application.port.EmbeddingProvider;
import com.chatbotq.rag.application.model.EmbeddingRequest;
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
        return embed(input, null);
    }

    /** Executes each potential HTTP request only after its gate grants a permit. */
    public float[] embed(EmbeddingRequest request) throws IOException {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        return embed(request.getInput(), request.getAttemptGate());
    }

    private float[] embed(String input, EmbeddingAttemptGate gate) throws IOException {
        long startedAt = System.nanoTime();
        long delayMs = initialBackoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            EmbeddingAttemptGate.Permit permit = acquire(gate);
            final float[] embedding;
            try {
                embedding = delegate.embed(input);
            } catch (IOException failure) {
                settleFailure(permit, failure);
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
                continue;
            } catch (RuntimeException failure) {
                settleFailure(permit, failure);
                throw failure;
            } catch (Error failure) {
                settleAfterError(permit, failure);
                throw failure;
            }
            settleSuccess(permit);
            recordRequest("success", startedAt);
            return embedding;
        }
        throw new AssertionError("unreachable");
    }

    private static EmbeddingAttemptGate.Permit acquire(EmbeddingAttemptGate gate) throws IOException {
        if (gate == null) return null;
        java.util.Optional<EmbeddingAttemptGate.Permit> permit = gate.acquire();
        if (!permit.isPresent()) throw new EmbeddingAttemptDeniedException();
        return permit.get();
    }

    private static void settle(EmbeddingAttemptGate.Permit permit, EmbeddingAttemptGate.Outcome outcome) throws IOException {
        if (permit != null) permit.settle(outcome);
    }

    private static void settleAfterError(EmbeddingAttemptGate.Permit permit, Error failure) throws IOException {
        try {
            settle(permit, EmbeddingAttemptGate.Outcome.FAILURE);
        } catch (IOException | RuntimeException | Error settlementFailure) {
            throw new EmbeddingAttemptSettlementUncertainException(failure, settlementFailure);
        }
    }

    private static void settleFailure(EmbeddingAttemptGate.Permit permit, Throwable providerFailure) throws IOException {
        try {
            settle(permit, EmbeddingAttemptGate.Outcome.FAILURE);
        } catch (IOException | RuntimeException | Error settlementFailure) {
            throw new EmbeddingAttemptSettlementUncertainException(providerFailure, settlementFailure);
        }
    }

    private static void settleSuccess(EmbeddingAttemptGate.Permit permit) throws IOException {
        try {
            settle(permit, EmbeddingAttemptGate.Outcome.SUCCESS);
        } catch (IOException | RuntimeException | Error settlementFailure) {
            throw new EmbeddingAttemptSettlementUncertainException(null, settlementFailure);
        }
    }

    private static boolean isTransient(IOException failure) {
        if (failure instanceof EmbeddingAttemptDeniedException || failure instanceof EmbeddingAttemptStaleException
            || failure instanceof EmbeddingAttemptSettlementUncertainException) return false;
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
