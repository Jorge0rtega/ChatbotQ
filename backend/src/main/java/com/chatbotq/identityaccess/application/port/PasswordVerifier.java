package com.chatbotq.identityaccess.application.port;

public interface PasswordVerifier {
    boolean matches(String rawPassword, String encodedPassword);
}
