package com.chatbotq.identityaccess.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BCryptPasswordHasherTest {

    @Test
    void createsSaltedBcryptHashesThatVerifyTheOriginalPassword() {
        BCryptPasswordHasher hasher = new BCryptPasswordHasher(4);

        String first = hasher.hash("temporal-segura");
        String second = hasher.hash("temporal-segura");

        assertTrue(first.startsWith("$2"));
        assertTrue(hasher.matches("temporal-segura", first));
        assertFalse(hasher.matches("incorrecta", first));
        assertFalse(first.equals(second));
    }
}
