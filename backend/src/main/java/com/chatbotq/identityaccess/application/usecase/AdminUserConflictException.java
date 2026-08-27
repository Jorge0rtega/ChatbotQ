package com.chatbotq.identityaccess.application.usecase;

public final class AdminUserConflictException extends RuntimeException {
    public AdminUserConflictException(String message) { super(message); }
}
