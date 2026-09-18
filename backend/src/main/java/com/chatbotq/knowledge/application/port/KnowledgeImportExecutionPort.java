package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;

import java.util.UUID;

public interface KnowledgeImportExecutionPort {
    ClaimedKnowledgeImportExecution claimReadyForExecution(UUID actorId, UUID projectId, UUID jobId);
    Finalization finalizeExecution(ClaimedKnowledgeImportExecution claim);
    void retryFailedExecution(UUID actorId, UUID projectId, UUID jobId);

    enum Finalization { FINALIZED, NOT_CURRENT, NOT_DRAINED }
}
