package com.chatbotq.knowledge.application.usecase;

public final class KnowledgeEntryNotFoundException extends RuntimeException {
    public KnowledgeEntryNotFoundException() {
        super("knowledge entry not found");
    }
}