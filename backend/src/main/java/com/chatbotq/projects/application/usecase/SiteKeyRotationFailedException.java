package com.chatbotq.projects.application.usecase;

public final class SiteKeyRotationFailedException extends RuntimeException {
    public SiteKeyRotationFailedException(Throwable cause) {
        super("site key rotation failed", cause);
    }
}
