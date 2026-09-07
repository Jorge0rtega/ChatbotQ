package com.chatbotq.rag.application.port;

import java.io.IOException;
import com.chatbotq.rag.application.model.EmbeddingAttemptDeniedException;
import com.chatbotq.rag.application.model.EmbeddingAttemptGate;
import com.chatbotq.rag.application.model.EmbeddingRequest;

public interface EmbeddingProvider {
    float[] embed(String input) throws IOException;

    /** A single-request provider honors the gate once; retrying providers override this per retry. */
    default float[] embed(EmbeddingRequest request) throws IOException {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        java.util.Optional<EmbeddingAttemptGate.Permit> permit = request.getAttemptGate().acquire();
        if (!permit.isPresent()) throw new EmbeddingAttemptDeniedException();
        try {
            float[] result = embed(request.getInput());
            permit.get().settle(EmbeddingAttemptGate.Outcome.SUCCESS);
            return result;
        } catch (IOException failure) {
            permit.get().settle(EmbeddingAttemptGate.Outcome.FAILURE);
            throw failure;
        }
    }
}
