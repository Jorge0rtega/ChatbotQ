package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;

import java.util.Optional;

/** Short persistence operations for the asynchronous embedding lifecycle. */
public interface KnowledgeEmbeddingProcessingPort {
    Optional<ClaimedKnowledgeEmbedding> claimOnePending();

    /** Records a provider attempt while the claim is still owned. */
    boolean recordProviderAttempt(ClaimedKnowledgeEmbedding claim);

    boolean markReady(ClaimedKnowledgeEmbedding claim, float[] embedding);

    boolean markFailed(ClaimedKnowledgeEmbedding claim, String errorCode, String errorMessage);
}
