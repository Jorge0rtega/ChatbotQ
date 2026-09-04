package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;

import java.time.Instant;
import java.util.UUID;

public interface KnowledgeAdministrationPort {
    ManagedKnowledgeEntry create(UUID actorId, UUID projectId, UUID entryId, String question, String answer,
                                 String externalId, boolean active, Instant now);

    ManagedKnowledgeEntry get(UUID actorId, UUID projectId, UUID entryId);
}
