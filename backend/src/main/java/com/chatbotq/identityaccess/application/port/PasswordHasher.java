package com.chatbotq.identityaccess.application.port;

public interface PasswordHasher {
    String hash(String rawPassword);
}
