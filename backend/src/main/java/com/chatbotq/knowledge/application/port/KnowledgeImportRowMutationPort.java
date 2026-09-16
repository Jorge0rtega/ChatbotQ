package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportRow;
import com.chatbotq.knowledge.application.model.NewKnowledgeEntry;

public interface KnowledgeImportRowMutationPort {
    Result createOnly(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row, NewKnowledgeEntry entry);

    enum Result { IMPORTED, EXTERNAL_ID_CONFLICT, STALE }
}
