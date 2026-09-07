package com.chatbotq.rag.application.model;

import java.io.IOException;
import java.util.Optional;

/** Authorizes and settles every outbound embedding request attempt. */
public interface EmbeddingAttemptGate {
    Optional<Permit> acquire() throws IOException;

    interface Permit {
        void settle(Outcome outcome) throws IOException;
    }

    enum Outcome {
        SUCCESS,
        FAILURE,
        CANCELLED
    }
}
