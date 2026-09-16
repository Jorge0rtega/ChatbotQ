package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportRow;

import java.util.Optional;

public interface KnowledgeImportRowClaimPort {
    Optional<ClaimedKnowledgeImportRow> claimNextValidRow(ClaimedKnowledgeImportExecution jobClaim);
}
