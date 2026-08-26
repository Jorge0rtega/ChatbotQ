package com.chatbotq.projects.application.usecase;

public final class ProjectNotFoundException extends RuntimeException {
    public ProjectNotFoundException() {
        super("project not found");
    }
}
