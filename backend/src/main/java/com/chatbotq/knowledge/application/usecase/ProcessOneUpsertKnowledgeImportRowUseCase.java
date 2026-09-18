package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportRow;
import com.chatbotq.knowledge.application.model.NewKnowledgeEntry;
import com.chatbotq.knowledge.application.port.KnowledgeEntryIdentityGenerator;
import com.chatbotq.knowledge.application.port.KnowledgeImportRowMutationPort;

import java.time.Clock;

/** Mutates exactly one already-claimed UPSERT import row; it performs no provider I/O. */
public final class ProcessOneUpsertKnowledgeImportRowUseCase {
    private final KnowledgeImportRowMutationPort mutations;
    private final KnowledgeEntryCreationService creation;

    public ProcessOneUpsertKnowledgeImportRowUseCase(KnowledgeImportRowMutationPort mutations,
                                                      KnowledgeEntryIdentityGenerator identities,
                                                      Clock clock, int maxEmbeddingInputTokensPerEntry) {
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        if (mutations == null) throw new IllegalArgumentException("mutations must not be null");
        this.mutations = mutations;
        this.creation = new KnowledgeEntryCreationService(identities, maxEmbeddingInputTokensPerEntry);
    }

    public Result process(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row) {
        if (execution == null || row == null) throw new IllegalArgumentException("execution and row must not be null");
        if (!"UPSERT".equals(execution.getStrategy())) throw new IllegalArgumentException("execution strategy must be UPSERT");
        if (!execution.getJobId().equals(row.getJobId())) throw new IllegalArgumentException("row must belong to execution job");
        NewKnowledgeEntry entry = creation.prepare(row.getQuestion(), row.getAnswer(), row.getExternalId(), row.isActive());
        try {
            return mutations.upsert(execution, row, entry) == KnowledgeImportRowMutationPort.Result.IMPORTED
                ? Result.IMPORTED : Result.STALE;
        } catch (StaleKnowledgeImportMutationException stale) {
            return Result.STALE;
        }
    }

    public enum Result { IMPORTED, STALE }
}
