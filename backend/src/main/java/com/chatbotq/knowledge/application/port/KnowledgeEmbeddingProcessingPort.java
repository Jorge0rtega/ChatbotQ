package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;

import java.util.Optional;

/** Short persistence operations for the asynchronous embedding lifecycle. */
public interface KnowledgeEmbeddingProcessingPort {
    Optional<ClaimedKnowledgeEmbedding> claimOnePending();
}
