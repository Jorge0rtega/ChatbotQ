package com.chatbotq.identityaccess.application.model;

public final class AuthenticationTokens {
    private final String accessToken;
    private final String refreshToken;
    private final long accessExpiresInSeconds;

    public AuthenticationTokens(String accessToken, String refreshToken, long accessExpiresInSeconds) {
        this.accessToken = requireText(accessToken, "accessToken");
        this.refreshToken = requireText(refreshToken, "refreshToken");
        if (accessExpiresInSeconds <= 0) {
            throw new IllegalArgumentException("accessExpiresInSeconds must be positive");
        }
        this.accessExpiresInSeconds = accessExpiresInSeconds;
    }

    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public long getAccessExpiresInSeconds() { return accessExpiresInSeconds; }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
