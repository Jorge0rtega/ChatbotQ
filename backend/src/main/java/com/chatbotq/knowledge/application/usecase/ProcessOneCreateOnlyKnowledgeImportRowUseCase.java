package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportRow;
import com.chatbotq.knowledge.application.model.NewKnowledgeEntry;
import com.chatbotq.knowledge.application.port.KnowledgeEntryIdentityGenerator;
import com.chatbotq.knowledge.application.port.KnowledgeImportRowMutationPort;

import java.time.Clock;

public final class ProcessOneCreateOnlyKnowledgeImportRowUseCase {
    private final KnowledgeImportRowMutationPort mutations;
    private final KnowledgeEntryCreationService creation;

    public ProcessOneCreateOnlyKnowledgeImportRowUseCase(KnowledgeImportRowMutationPort mutations,
                                                          KnowledgeEntryIdentityGenerator identities,
                                                          Clock clock, int maxEmbeddingInputTokensPerEntry) {
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        if (mutations == null) throw new IllegalArgumentException("mutations must not be null");
        this.mutations = mutations;
        this.creation = new KnowledgeEntryCreationService(identities, maxEmbeddingInputTokensPerEntry);
    }

    public Result process(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row) {
        if (execution == null || row == null) throw new IllegalArgumentException("execution and row must not be null");
        if (!"CREATE_ONLY".equals(execution.getStrategy())) throw new IllegalArgumentException("execution strategy must be CREATE_ONLY");
        if (!execution.getJobId().equals(row.getJobId())) throw new IllegalArgumentException("row must belong to execution job");
        NewKnowledgeEntry entry = creation.prepare(row.getQuestion(), row.getAnswer(), row.getExternalId(), row.isActive());
        KnowledgeImportRowMutationPort.Result result;
        try {
            result = mutations.createOnly(execution, row, entry);
        } catch (StaleKnowledgeImportMutationException stale) {
            return Result.STALE;
        }
        if (result == KnowledgeImportRowMutationPort.Result.IMPORTED) return Result.IMPORTED;
        if (result == KnowledgeImportRowMutationPort.Result.EXTERNAL_ID_CONFLICT) return Result.EXTERNAL_ID_CONFLICT;
        return Result.STALE;
    }

    public enum Result { IMPORTED, EXTERNAL_ID_CONFLICT, STALE }
}
