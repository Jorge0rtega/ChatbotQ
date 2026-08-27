package com.chatbotq.identityaccess.application.usecase;

public final class AdminUserNotFoundException extends RuntimeException {
    public AdminUserNotFoundException() { super("admin user not found"); }
}
