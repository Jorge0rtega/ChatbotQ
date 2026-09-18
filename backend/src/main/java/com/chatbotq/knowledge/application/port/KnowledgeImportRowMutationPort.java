package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportRow;
import com.chatbotq.knowledge.application.model.NewKnowledgeEntry;

public interface KnowledgeImportRowMutationPort {
    Result createOnly(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row, NewKnowledgeEntry entry);
    default Result upsert(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row, NewKnowledgeEntry entry) {
        throw new UnsupportedOperationException("UPSERT import mutation is not supported");
    }

    enum Result { IMPORTED, EXTERNAL_ID_CONFLICT, STALE }
}
