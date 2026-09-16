package com.chatbotq.knowledge.application.usecase;

public final class KnowledgeImportJobNotFoundException extends RuntimeException {
    public KnowledgeImportJobNotFoundException() { super("knowledge import job not found"); }
}
