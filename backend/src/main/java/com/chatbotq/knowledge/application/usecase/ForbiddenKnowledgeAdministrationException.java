package com.chatbotq.knowledge.application.usecase;

public final class ForbiddenKnowledgeAdministrationException extends RuntimeException {
    public ForbiddenKnowledgeAdministrationException() {
        super("knowledge administration is forbidden");
    }
}
