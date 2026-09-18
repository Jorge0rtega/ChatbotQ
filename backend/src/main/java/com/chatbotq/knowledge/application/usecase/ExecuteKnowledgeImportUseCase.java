package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportRow;
import com.chatbotq.knowledge.application.port.KnowledgeImportExecutionPort;
import com.chatbotq.knowledge.application.port.KnowledgeImportRowClaimPort;

import java.util.Optional;
import java.util.UUID;

/** Synchronously drains one bounded, database-only import execution without a whole-drain transaction. */
public final class ExecuteKnowledgeImportUseCase {
    private static final int MAX_PREVIEW_ROWS = 1000;
    private final KnowledgeImportExecutionPort executions;
    private final KnowledgeImportRowClaimPort rows;
    private final ProcessOneCreateOnlyKnowledgeImportRowUseCase createOnly;
    private final ProcessOneUpsertKnowledgeImportRowUseCase upsert;
    private final ApplicationTransaction transactions;

    public ExecuteKnowledgeImportUseCase(KnowledgeImportExecutionPort executions, KnowledgeImportRowClaimPort rows,
                                         ProcessOneCreateOnlyKnowledgeImportRowUseCase createOnly,
                                         ProcessOneUpsertKnowledgeImportRowUseCase upsert, ApplicationTransaction transactions) {
        if (executions == null || rows == null || createOnly == null || upsert == null || transactions == null) throw new IllegalArgumentException("execution dependencies must not be null");
        this.executions = executions; this.rows = rows; this.createOnly = createOnly; this.upsert = upsert; this.transactions = transactions;
    }

    public void execute(UUID actorId, UUID projectId, UUID jobId) {
        ClaimedKnowledgeImportExecution claim = transactions.execute(() -> executions.claimReadyForExecution(actorId, projectId, jobId));
        for (int processed = 0; processed < MAX_PREVIEW_ROWS; processed++) {
            Optional<ClaimedKnowledgeImportRow> row = transactions.execute(() -> rows.claimNextValidRow(claim));
            if (!row.isPresent()) {
                KnowledgeImportExecutionPort.Finalization finalization = transactions.execute(() -> executions.finalizeExecution(claim));
                if (finalization == KnowledgeImportExecutionPort.Finalization.NOT_CURRENT) throw new ImportExecutionNotReadyException();
                if (finalization == KnowledgeImportExecutionPort.Finalization.NOT_DRAINED) throw new ImportExecutionNotReadyException();
                return;
            }
            if ("CREATE_ONLY".equals(claim.getStrategy())) {
                if (createOnly.process(claim, row.get()) == ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.STALE) throw new ImportExecutionNotReadyException();
            } else if ("UPSERT".equals(claim.getStrategy())) {
                if (upsert.process(claim, row.get()) == ProcessOneUpsertKnowledgeImportRowUseCase.Result.STALE) throw new ImportExecutionNotReadyException();
            } else {
                throw new IllegalStateException("unsupported import strategy");
            }
        }
        KnowledgeImportExecutionPort.Finalization finalization = transactions.execute(() -> executions.finalizeExecution(claim));
        if (finalization != KnowledgeImportExecutionPort.Finalization.FINALIZED) throw new ImportExecutionNotReadyException();
    }

    public void retry(UUID actorId, UUID projectId, UUID jobId) {
        transactions.execute(() -> { executions.retryFailedExecution(actorId, projectId, jobId); return null; });
    }
}
