package com.chatbotq.identityaccess.application.usecase;

public final class AssignedProjectNotFoundException extends RuntimeException {
    public AssignedProjectNotFoundException() { super("assigned project not found"); }
}
