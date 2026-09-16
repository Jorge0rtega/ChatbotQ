package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.PersistedKnowledgeImportJob;

import java.util.UUID;

public interface KnowledgeImportAdministrationPort {
    PersistedKnowledgeImportJob create(UUID actorId, PersistedKnowledgeImportJob job);
    PersistedKnowledgeImportJob get(UUID actorId, UUID projectId, UUID jobId, int page, int size);
}
