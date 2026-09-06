package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;

/** Atomically reserves global embedding budget before outbound provider I/O. */
public interface EmbeddingBudgetReservationPort {
    Decision reserve(ClaimedKnowledgeEmbedding claim);

    enum Decision {
        RESERVED,
        DENIED,
        STALE
    }
}
