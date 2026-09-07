package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;
import com.chatbotq.knowledge.application.port.EmbeddingBudgetReservationPort;
import com.chatbotq.knowledge.application.port.KnowledgeEmbeddingProcessingPort;
import com.chatbotq.rag.application.port.EmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessOneKnowledgeEmbeddingUseCaseTest {
    @Test
    void claimsOnePendingEmbeddingGeneratesItAndMarksTheSameRevisionReady() throws Exception {
        ClaimedKnowledgeEmbedding claim = new ClaimedKnowledgeEmbedding(UUID.randomUUID(), 7L, "What are your hours?");
        RecordingProcessingPort processing = new RecordingProcessingPort(Optional.of(claim));
        float[] generated = vector();
        RecordingEmbeddingProvider provider = new RecordingEmbeddingProvider(generated);

        ProcessOneKnowledgeEmbeddingUseCase useCase = new ProcessOneKnowledgeEmbeddingUseCase(
            processing, acceptingReservations(), provider);

        assertEquals(ProcessOneKnowledgeEmbeddingUseCase.Result.READY, useCase.processOne());
        assertEquals(claim, processing.readyClaim);
        assertArrayEquals(generated, processing.readyEmbedding);
        assertEquals("What are your hours?", provider.input);
        assertEquals(0, processing.failedCalls);
    }

    @Test
    void mapsProviderIoFailureToTheFixedSafeDiagnosticAndMarksTheClaimFailed() throws Exception {
        ClaimedKnowledgeEmbedding claim = new ClaimedKnowledgeEmbedding(UUID.randomUUID(), 3L, "Where are you located?");
        RecordingProcessingPort processing = new RecordingProcessingPort(Optional.of(claim));
        EmbeddingProvider provider = input -> {
            throw new IOException("upstream included secret=not-for-storage");
        };

        ProcessOneKnowledgeEmbeddingUseCase useCase = new ProcessOneKnowledgeEmbeddingUseCase(
            processing, acceptingReservations(), provider);

        assertEquals(ProcessOneKnowledgeEmbeddingUseCase.Result.FAILED, useCase.processOne());
        assertEquals(claim, processing.failedClaim);
        assertEquals("PROVIDER_FAILURE", processing.failedCode);
        assertEquals("Embedding generation failed", processing.failedMessage);
        assertEquals(1, processing.failedCalls);
    }

    @Test
    void mapsAnInvalidProviderVectorToTheFixedInvalidResponseDiagnostic() {
        ClaimedKnowledgeEmbedding claim = new ClaimedKnowledgeEmbedding(UUID.randomUUID(), 5L, "Can I cancel?");
        RecordingProcessingPort processing = new RecordingProcessingPort(Optional.of(claim));
        RecordingEmbeddingProvider provider = new RecordingEmbeddingProvider(new float[1]);

        ProcessOneKnowledgeEmbeddingUseCase useCase = new ProcessOneKnowledgeEmbeddingUseCase(
            processing, acceptingReservations(), provider);

        assertEquals(ProcessOneKnowledgeEmbeddingUseCase.Result.FAILED, useCase.processOne());
        assertEquals(claim, processing.failedClaim);
        assertEquals("PROVIDER_INVALID_RESPONSE", processing.failedCode);
        assertEquals("Embedding provider returned an invalid response", processing.failedMessage);
    }

    @Test
    void deniedBudgetMarksStillOwnedClaimFailedWithoutCallingProvider() {
        ClaimedKnowledgeEmbedding claim = new ClaimedKnowledgeEmbedding(UUID.randomUUID(), UUID.randomUUID(), 4L,
            "Budgeted question", 17, UUID.randomUUID());
        RecordingProcessingPort processing = new RecordingProcessingPort(Optional.of(claim));
        RecordingReservationPort reservation = new RecordingReservationPort(EmbeddingBudgetReservationPort.Decision.DENIED);
        RecordingEmbeddingProvider provider = new RecordingEmbeddingProvider(vector());

        ProcessOneKnowledgeEmbeddingUseCase useCase = new ProcessOneKnowledgeEmbeddingUseCase(processing, reservation, provider);

        assertEquals(ProcessOneKnowledgeEmbeddingUseCase.Result.FAILED, useCase.processOne());
        assertEquals(1, reservation.calls);
        assertEquals(null, provider.input);
        assertEquals("EMBEDDING_BUDGET_LIMIT_REACHED", processing.failedCode);
        assertEquals("Embedding budget limit reached", processing.failedMessage);
    }

    @Test
    void rejectsAMissingBudgetReservationPort() {
        RecordingProcessingPort processing = new RecordingProcessingPort(Optional.empty());
        RecordingEmbeddingProvider provider = new RecordingEmbeddingProvider(vector());

        assertThrows(IllegalArgumentException.class,
            () -> new ProcessOneKnowledgeEmbeddingUseCase(processing, null, provider));
    }

    private static EmbeddingBudgetReservationPort acceptingReservations() {
        return claim -> EmbeddingBudgetReservationPort.Reservation.of(EmbeddingBudgetReservationPort.Decision.RESERVED);
    }

    private static float[] vector() {
        float[] vector = new float[1536];
        vector[0] = 0.25f;
        return vector;
    }

    private static final class RecordingEmbeddingProvider implements EmbeddingProvider {
        private final float[] response;
        private String input;

        private RecordingEmbeddingProvider(float[] response) {
            this.response = response;
        }

        @Override
        public float[] embed(String input) throws IOException {
            this.input = input;
            return response;
        }
    }

    private static final class RecordingReservationPort implements EmbeddingBudgetReservationPort {
        private final Decision decision;
        private int calls;

        private RecordingReservationPort(Decision decision) {
            this.decision = decision;
        }

        @Override
        public Reservation reserve(ClaimedKnowledgeEmbedding claim) {
            calls++;
            return Reservation.of(decision);
        }
    }

    private static final class RecordingProcessingPort implements KnowledgeEmbeddingProcessingPort {
        private final Optional<ClaimedKnowledgeEmbedding> claim;
        private ClaimedKnowledgeEmbedding readyClaim;
        private float[] readyEmbedding;
        private int failedCalls;
        private ClaimedKnowledgeEmbedding failedClaim;
        private String failedCode;
        private String failedMessage;

        private RecordingProcessingPort(Optional<ClaimedKnowledgeEmbedding> claim) {
            this.claim = claim;
        }

        @Override
        public Optional<ClaimedKnowledgeEmbedding> claimOnePending() {
            return claim;
        }

        @Override
        public boolean recordProviderAttempt(ClaimedKnowledgeEmbedding claim) {
            return true;
        }

        @Override
        public boolean markReady(ClaimedKnowledgeEmbedding claim, float[] embedding) {
            readyClaim = claim;
            readyEmbedding = embedding;
            return true;
        }

        @Override
        public boolean markFailed(ClaimedKnowledgeEmbedding claim, String errorCode, String errorMessage) {
            failedCalls++;
            failedClaim = claim;
            failedCode = errorCode;
            failedMessage = errorMessage;
            return true;
        }
    }
}
