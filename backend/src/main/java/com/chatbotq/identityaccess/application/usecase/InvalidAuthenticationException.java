package com.chatbotq.identityaccess.application.usecase;

public final class InvalidAuthenticationException extends RuntimeException {
    public InvalidAuthenticationException(String message) {
        super(message);
    }

    public static InvalidAuthenticationException credentials() {
        return new InvalidAuthenticationException("invalid credentials");
    }

    public static InvalidAuthenticationException refreshToken() {
        return new InvalidAuthenticationException("invalid refresh token");
    }
}
