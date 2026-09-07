package com.chatbotq.rag.infrastructure.provider;

import com.chatbotq.rag.application.model.EmbeddingAttemptGate;
import com.chatbotq.rag.application.port.EmbeddingProvider;
import com.chatbotq.rag.application.model.EmbeddingRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryingEmbeddingProviderTest {
    @Test
    void reservesAndSettlesEachOutboundAttemptIncludingRetry() throws Exception {
        RecordingProvider delegate = new RecordingProvider(new OpenAiEmbeddingHttpException(429), vector());
        RecordingAttemptGate gate = new RecordingAttemptGate();
        RetryingEmbeddingProvider provider = new RetryingEmbeddingProvider(delegate, new RecordingSleeper(), 2, 1L, 1L);

        assertArrayEquals(vector(), provider.embed(new EmbeddingRequest("hours", gate)));
        assertEquals(2, gate.acquireCalls);
        assertEquals(Arrays.asList(EmbeddingAttemptGate.Outcome.FAILURE, EmbeddingAttemptGate.Outcome.SUCCESS), gate.outcomes);
        assertEquals(2, delegate.calls);
    }

    @Test
    void retriesRateLimitedFailureWithCappedExponentialBackoff() throws Exception {
        RecordingProvider delegate = new RecordingProvider(
            new OpenAiEmbeddingHttpException(429), vector());
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryingEmbeddingProvider provider = new RetryingEmbeddingProvider(delegate, sleeper, 3, 10L, 25L);

        assertArrayEquals(vector(), provider.embed("hours"));
        assertEquals(2, delegate.calls);
        assertEquals(Arrays.asList(10L), sleeper.delays);
    }

    @Test
    void retriesServerFailures() throws Exception {
        RecordingProvider delegate = new RecordingProvider(
            new OpenAiEmbeddingHttpException(503), vector());
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryingEmbeddingProvider provider = new RetryingEmbeddingProvider(delegate, sleeper, 2, 10L, 25L);

        assertArrayEquals(vector(), provider.embed("hours"));
        assertEquals(2, delegate.calls);
        assertEquals(Arrays.asList(10L), sleeper.delays);
    }

    @Test
    void recordsOnlyBoundedRetryDiagnosticsAndLatency() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RecordingProvider delegate = new RecordingProvider(new OpenAiEmbeddingHttpException(429), vector());
        RetryingEmbeddingProvider provider = new RetryingEmbeddingProvider(
            delegate, new RecordingSleeper(), metrics, 2, 1L, 1L);

        assertArrayEquals(vector(), provider.embed("hours"));
        assertEquals(1.0, metrics.counter("chatbotq.embedding.retry.attempts", "category", "rate_limited").count());
        assertEquals(1.0, metrics.counter("chatbotq.embedding.requests", "outcome", "success").count());
        assertTrue(metrics.find("chatbotq.embedding.duration").timer() != null);
    }

    @Test
    void doesNotRetryRedirectFailure() {
        RecordingProvider delegate = new RecordingProvider(new OpenAiEmbeddingHttpException(302));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryingEmbeddingProvider provider = new RetryingEmbeddingProvider(delegate, sleeper, 3, 10L, 25L);

        assertThrows(OpenAiEmbeddingHttpException.class, () -> provider.embed("hours"));
        assertEquals(1, delegate.calls);
        assertEquals(0, sleeper.delays.size());
    }

    @Test
    void doesNotRetryPermanentClientFailure() {
        RecordingProvider delegate = new RecordingProvider(new OpenAiEmbeddingHttpException(400));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryingEmbeddingProvider provider = new RetryingEmbeddingProvider(delegate, sleeper, 3, 10L, 25L);

        assertThrows(OpenAiEmbeddingHttpException.class, () -> provider.embed("hours"));
        assertEquals(1, delegate.calls);
        assertEquals(0, sleeper.delays.size());
    }

    @Test
    void retriesTransientIoOnlyUntilTheExplicitAttemptLimit() {
        RecordingProvider delegate = new RecordingProvider(
            new IOException("network unavailable"), new IOException("network unavailable"), new IOException("network unavailable"));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryingEmbeddingProvider provider = new RetryingEmbeddingProvider(delegate, sleeper, 3, 10L, 25L);

        assertThrows(IOException.class, () -> provider.embed("hours"));
        assertEquals(3, delegate.calls);
        assertEquals(Arrays.asList(10L, 20L), sleeper.delays);
    }

    @Test
    void capsBackoffAndAvoidsOverflowNearLongMaximum() {
        long initialDelay = Long.MAX_VALUE / 2 + 1;
        RecordingProvider delegate = new RecordingProvider(
            new IOException("network unavailable"), new IOException("network unavailable"),
            new IOException("network unavailable"), new IOException("network unavailable"));
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryingEmbeddingProvider provider = new RetryingEmbeddingProvider(
            delegate, sleeper, 4, initialDelay, Long.MAX_VALUE);

        assertThrows(IOException.class, () -> provider.embed("hours"));
        assertEquals(Arrays.asList(initialDelay, Long.MAX_VALUE, Long.MAX_VALUE), sleeper.delays);
    }

    @Test
    void preservesInterruptionAndStopsRetriesWhenSleeperIsInterrupted() {
        RecordingProvider delegate = new RecordingProvider(new IOException("network unavailable"));
        RetryingEmbeddingProvider.Sleeper interruptedSleeper = delayMs -> {
            throw new InterruptedException("stop");
        };
        RetryingEmbeddingProvider provider = new RetryingEmbeddingProvider(
            delegate, interruptedSleeper, 3, 10L, 25L);

        try {
            IOException failure = assertThrows(IOException.class, () -> provider.embed("hours"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertEquals(1, delegate.calls);
        } finally {
            Thread.interrupted();
        }
    }

    private static float[] vector() {
        float[] values = new float[1536];
        values[0] = 0.5f;
        return values;
    }

    private static final class RecordingAttemptGate implements EmbeddingAttemptGate {
        private int acquireCalls;
        private final List<Outcome> outcomes = new ArrayList<>();

        @Override
        public Optional<Permit> acquire() {
            acquireCalls++;
            return Optional.of(outcomes::add);
        }
    }

    private static final class RecordingProvider implements EmbeddingProvider {
        private final List<Object> outcomes;
        private int calls;

        private RecordingProvider(Object... outcomes) {
            this.outcomes = new ArrayList<>(Arrays.asList(outcomes));
        }

        @Override
        public float[] embed(String input) throws IOException {
            calls++;
            Object outcome = outcomes.remove(0);
            if (outcome instanceof IOException) throw (IOException) outcome;
            return (float[]) outcome;
        }
    }

    private static final class RecordingSleeper implements RetryingEmbeddingProvider.Sleeper {
        private final List<Long> delays = new ArrayList<>();

        @Override
        public void sleep(long delayMs) {
            delays.add(delayMs);
        }
    }
}
