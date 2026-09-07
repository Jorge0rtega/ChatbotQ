package com.chatbotq.rag.application.port;

import java.io.IOException;
import com.chatbotq.rag.application.model.EmbeddingAttemptDeniedException;
import com.chatbotq.rag.application.model.EmbeddingAttemptGate;
import com.chatbotq.rag.application.model.EmbeddingRequest;
import com.chatbotq.rag.application.model.EmbeddingAttemptSettlementUncertainException;

public interface EmbeddingProvider {
    float[] embed(String input) throws IOException;

    /** A single-request provider honors the gate once; retrying providers override this per retry. */
    default float[] embed(EmbeddingRequest request) throws IOException {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        java.util.Optional<EmbeddingAttemptGate.Permit> permit = request.getAttemptGate().acquire();
        if (!permit.isPresent()) throw new EmbeddingAttemptDeniedException();
        final float[] result;
        try {
            result = embed(request.getInput());
        } catch (IOException failure) {
            settleFailure(permit.get(), failure);
            throw failure;
        } catch (RuntimeException failure) {
            settleFailure(permit.get(), failure);
            throw failure;
        } catch (Error failure) {
            try {
                permit.get().settle(EmbeddingAttemptGate.Outcome.FAILURE);
            } catch (IOException | RuntimeException | Error settlementFailure) {
                throw new EmbeddingAttemptSettlementUncertainException(failure, settlementFailure);
            }
            throw failure;
        }
        settleSuccess(permit.get());
        return result;
    }

    static void settleFailure(EmbeddingAttemptGate.Permit permit, Throwable providerFailure) throws IOException {
        try {
            permit.settle(EmbeddingAttemptGate.Outcome.FAILURE);
        } catch (IOException | RuntimeException | Error settlementFailure) {
            throw new EmbeddingAttemptSettlementUncertainException(providerFailure, settlementFailure);
        }
    }

    static void settleSuccess(EmbeddingAttemptGate.Permit permit) throws IOException {
        try {
            permit.settle(EmbeddingAttemptGate.Outcome.SUCCESS);
        } catch (IOException | RuntimeException | Error settlementFailure) {
            throw new EmbeddingAttemptSettlementUncertainException(null, settlementFailure);
        }
    }
}
