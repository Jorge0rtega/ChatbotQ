package com.chatbotq.rag.infrastructure.provider;

import com.chatbotq.rag.application.port.EmbeddingProvider;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Test-only provider with stable 1536-dimensional vectors and no network access.
 */
public final class DeterministicEmbeddingProvider implements EmbeddingProvider {
    private static final int DIMENSIONS = 1536;

    @Override
    public float[] embed(String input) throws IOException {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        float[] vector = new float[DIMENSIONS];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
            for (int index = 0; index < DIMENSIONS; index++) {
                digest.update(inputBytes);
                digest.update(ByteBuffer.allocate(4).putInt(index).array());
                byte[] hash = digest.digest();
                int unsigned = ((hash[0] & 0xff) << 8) | (hash[1] & 0xff);
                vector[index] = (unsigned / 32767.5f) - 1.0f;
            }
            return vector;
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 is unavailable", unavailable);
        }
    }
}
