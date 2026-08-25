package com.chatbotq.identityaccess.infrastructure.security;

public final class InvalidAccessTokenException extends RuntimeException {
    public InvalidAccessTokenException() {
        super("invalid access token");
    }
}
