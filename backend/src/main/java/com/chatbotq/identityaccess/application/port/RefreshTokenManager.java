package com.chatbotq.identityaccess.application.port;

public interface RefreshTokenManager {
    String generate();
    String hash(String token);
}
