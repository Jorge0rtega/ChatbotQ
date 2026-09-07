package com.chatbotq.rag.application.model;

import java.io.IOException;

/** Provider egress happened, but the durable attempt outcome could not be confirmed. */
public final class EmbeddingAttemptSettlementUncertainException extends IOException {
    public EmbeddingAttemptSettlementUncertainException(Throwable providerFailure, Throwable settlementFailure) {
        super("Embedding attempt settlement is uncertain", providerFailure);
        if (settlementFailure != null) addSuppressed(settlementFailure);
    }
}
