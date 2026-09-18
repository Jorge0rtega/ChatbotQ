package com.chatbotq.knowledge.application.usecase;

public final class KnowledgeImportRetryNotAllowedException extends RuntimeException {
    public KnowledgeImportRetryNotAllowedException() { super("knowledge import retry is not allowed"); }
}
