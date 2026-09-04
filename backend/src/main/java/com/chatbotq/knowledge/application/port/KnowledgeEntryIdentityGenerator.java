package com.chatbotq.knowledge.application.port;

import java.util.UUID;

public interface KnowledgeEntryIdentityGenerator {
    UUID newKnowledgeEntryId();
}
