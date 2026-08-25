package com.chatbotq.identityaccess.infrastructure.security;

import com.chatbotq.identityaccess.application.port.RefreshTokenManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class SecureRefreshTokenManager implements RefreshTokenManager {
    private final SecureRandom random;

    public SecureRefreshTokenManager() {
        this(new SecureRandom());
    }

    SecureRefreshTokenManager(SecureRandom random) {
        this.random = random;
    }

    @Override
    public String generate() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    @Override
    public String hash(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("token must not be empty");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
