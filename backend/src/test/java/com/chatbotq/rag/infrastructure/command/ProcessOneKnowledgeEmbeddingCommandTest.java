package com.chatbotq.rag.infrastructure.command;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;
import com.chatbotq.knowledge.application.port.EmbeddingBudgetReservationPort;
import com.chatbotq.knowledge.application.port.KnowledgeEmbeddingProcessingPort;
import com.chatbotq.knowledge.application.usecase.ProcessOneKnowledgeEmbeddingUseCase;
import com.chatbotq.rag.application.port.EmbeddingProvider;
import com.chatbotq.rag.infrastructure.configuration.EmbeddingProcessingLimits;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessOneKnowledgeEmbeddingCommandTest {
    @Test
    void processesExactlyOnePendingEntryPerInvocation() {
        RecordingProcessingPort processing = new RecordingProcessingPort();
        EmbeddingProvider provider = input -> vector();
        ProcessOneKnowledgeEmbeddingUseCase useCase = new ProcessOneKnowledgeEmbeddingUseCase(processing,
            claim -> EmbeddingBudgetReservationPort.Decision.RESERVED, provider);
        ProcessOneKnowledgeEmbeddingCommand command = new ProcessOneKnowledgeEmbeddingCommand(
            useCase, new EmbeddingProcessingLimits(1, 4000, 100, 250000, 10, 20));

        assertEquals(ProcessOneKnowledgeEmbeddingUseCase.Result.READY, command.runOnce());
        assertEquals(1, processing.claims);
        assertEquals(1, processing.readyCalls);
    }

    private static float[] vector() { return new float[1536]; }

    private static final class RecordingProcessingPort implements KnowledgeEmbeddingProcessingPort {
        private int claims;
        private int readyCalls;
        @Override public Optional<ClaimedKnowledgeEmbedding> claimOnePending() {
            claims++;
            return Optional.of(new ClaimedKnowledgeEmbedding(UUID.randomUUID(), 1, "question"));
        }
        @Override public boolean recordProviderAttempt(ClaimedKnowledgeEmbedding claim) { return true; }
        @Override public boolean markReady(ClaimedKnowledgeEmbedding claim, float[] embedding) { readyCalls++; return true; }
        @Override public boolean markFailed(ClaimedKnowledgeEmbedding claim, String code, String message) { return true; }
    }
}
