package com.chatbotq.identityaccess.application.usecase;

public final class ForbiddenAdminUserAdministrationException extends RuntimeException {
    public ForbiddenAdminUserAdministrationException() { super("forbidden"); }
}
