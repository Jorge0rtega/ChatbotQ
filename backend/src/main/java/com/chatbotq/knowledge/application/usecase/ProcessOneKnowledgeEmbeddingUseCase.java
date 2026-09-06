package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;
import com.chatbotq.knowledge.application.port.EmbeddingBudgetReservationPort;
import com.chatbotq.knowledge.application.port.KnowledgeEmbeddingProcessingPort;
import com.chatbotq.rag.application.port.EmbeddingProvider;

import java.util.Optional;

/** Processes at most one claimed embedding without holding a database transaction during provider I/O. */
public final class ProcessOneKnowledgeEmbeddingUseCase {
    private final KnowledgeEmbeddingProcessingPort processing;
    private final EmbeddingBudgetReservationPort reservations;
    private final EmbeddingProvider provider;

    public ProcessOneKnowledgeEmbeddingUseCase(KnowledgeEmbeddingProcessingPort processing,
                                               EmbeddingBudgetReservationPort reservations,
                                               EmbeddingProvider provider) {
        if (processing == null) throw new IllegalArgumentException("processing must not be null");
        if (reservations == null) throw new IllegalArgumentException("reservations must not be null");
        if (provider == null) throw new IllegalArgumentException("provider must not be null");
        this.processing = processing;
        this.reservations = reservations;
        this.provider = provider;
    }

    public Result processOne() {
        Optional<ClaimedKnowledgeEmbedding> claim = processing.claimOnePending();
        if (!claim.isPresent()) return Result.NO_PENDING;
        EmbeddingBudgetReservationPort.Decision decision = reservations.reserve(claim.get());
        if (decision == EmbeddingBudgetReservationPort.Decision.DENIED) {
            return markFailed(claim.get(), "EMBEDDING_BUDGET_LIMIT_REACHED", "Embedding budget limit reached");
        }
        if (decision != EmbeddingBudgetReservationPort.Decision.RESERVED) return Result.STALE;
        if (!processing.recordProviderAttempt(claim.get())) return Result.STALE;
        final float[] embedding;
        try {
            embedding = provider.embed(claim.get().getQuestion());
        } catch (Exception providerFailure) {
            return markFailed(claim.get(), "PROVIDER_FAILURE", "Embedding generation failed");
        }
        if (!isValidEmbedding(embedding)) {
            return markFailed(claim.get(), "PROVIDER_INVALID_RESPONSE", "Embedding provider returned an invalid response");
        }
        return processing.markReady(claim.get(), embedding) ? Result.READY : Result.STALE;
    }

    private Result markFailed(ClaimedKnowledgeEmbedding claim, String errorCode, String errorMessage) {
        return processing.markFailed(claim, errorCode, errorMessage) ? Result.FAILED : Result.STALE;
    }

    private static boolean isValidEmbedding(float[] embedding) {
        if (embedding == null || embedding.length != 1536) return false;
        for (float value : embedding) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return false;
        }
        return true;
    }

    public enum Result {
        NO_PENDING,
        READY,
        FAILED,
        STALE
    }
}
