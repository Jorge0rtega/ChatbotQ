package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;

import java.util.UUID;

/** Atomically reserves and settles global budget for one outbound provider attempt. */
public interface EmbeddingBudgetReservationPort {
    Reservation reserve(ClaimedKnowledgeEmbedding claim);

    default Reservation reserve(ClaimedKnowledgeEmbedding claim, int attemptNumber) {
        if (attemptNumber != 1) throw new IllegalArgumentException("attempt-aware reservation is required");
        return reserve(claim);
    }

    default void settle(Reservation reservation, Settlement settlement) {
        // Simple in-memory test doubles may not keep a durable ledger.
    }

    enum Settlement { SUCCESS, FAILURE, CANCELLED }

    final class Reservation {
        private final Decision decision;
        private final UUID id;

        private Reservation(Decision decision, UUID id) {
            this.decision = decision;
            this.id = id;
        }

        public static Reservation reserved(UUID id) {
            if (id == null) throw new IllegalArgumentException("id must not be null");
            return new Reservation(Decision.RESERVED, id);
        }

        public static Reservation denied() { return new Reservation(Decision.DENIED, null); }
        public static Reservation stale() { return new Reservation(Decision.STALE, null); }
        public static Reservation of(Decision decision) {
            if (decision == Decision.RESERVED) return reserved(UUID.randomUUID());
            return decision == Decision.DENIED ? denied() : stale();
        }
        public Decision getDecision() { return decision; }
        public UUID getId() { return id; }
    }

    enum Decision { RESERVED, DENIED, STALE }
}
