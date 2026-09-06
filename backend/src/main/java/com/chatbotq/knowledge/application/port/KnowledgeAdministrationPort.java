package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntryPage;

import java.time.Instant;
import java.util.UUID;

public interface KnowledgeAdministrationPort {
    ManagedKnowledgeEntry create(UUID actorId, UUID projectId, UUID entryId, String question, String answer,
                                 String externalId, boolean active, int embeddingInputTokenUpperBound, Instant now);

    ManagedKnowledgeEntry get(UUID actorId, UUID projectId, UUID entryId);

    ManagedKnowledgeEntry update(UUID actorId, UUID projectId, UUID entryId, String question, String answer,
                                 String externalId, boolean active, long version, int embeddingInputTokenUpperBound,
                                 Instant now);

    ManagedKnowledgeEntry retryEmbedding(UUID actorId, UUID projectId, UUID entryId, long version, Instant now);

    ManagedKnowledgeEntryPage list(UUID actorId, UUID projectId, String query, int page, int size, long offset);
}
