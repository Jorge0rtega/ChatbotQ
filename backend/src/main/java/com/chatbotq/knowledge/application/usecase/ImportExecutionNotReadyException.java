package com.chatbotq.knowledge.application.usecase;

public final class ImportExecutionNotReadyException extends RuntimeException {
    public ImportExecutionNotReadyException() {
        super("knowledge import is not ready for execution");
    }
}
