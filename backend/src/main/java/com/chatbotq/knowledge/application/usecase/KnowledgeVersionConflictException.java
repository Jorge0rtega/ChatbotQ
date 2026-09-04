package com.chatbotq.knowledge.application.usecase;

public final class KnowledgeVersionConflictException extends RuntimeException {
    public KnowledgeVersionConflictException() {
        super("knowledge entry version conflict");
    }
}
