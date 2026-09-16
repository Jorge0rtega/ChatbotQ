package com.chatbotq.knowledge.application.usecase;

/** Signals that a fenced import mutation lost its fence after changing durable state. */
public final class StaleKnowledgeImportMutationException extends RuntimeException {
    public StaleKnowledgeImportMutationException() {
        super("knowledge import mutation lost its execution fence");
    }
}
