package com.chatbotq.projects.application.usecase;

public final class StaleSiteKeyVersionException extends RuntimeException {
    public StaleSiteKeyVersionException() {
        super("site key version conflict");
    }
}
