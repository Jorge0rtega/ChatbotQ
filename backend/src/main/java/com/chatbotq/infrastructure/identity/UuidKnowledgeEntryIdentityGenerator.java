package com.chatbotq.infrastructure.identity;

import com.chatbotq.knowledge.application.port.KnowledgeEntryIdentityGenerator;

import java.util.UUID;

public final class UuidKnowledgeEntryIdentityGenerator implements KnowledgeEntryIdentityGenerator {
    @Override
    public UUID newKnowledgeEntryId() {
        return UUID.randomUUID();
    }
}
