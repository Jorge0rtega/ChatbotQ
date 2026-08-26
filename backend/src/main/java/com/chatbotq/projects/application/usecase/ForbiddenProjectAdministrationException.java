package com.chatbotq.projects.application.usecase;

public final class ForbiddenProjectAdministrationException extends RuntimeException {
    public ForbiddenProjectAdministrationException() {
        super("project administration forbidden");
    }
}
