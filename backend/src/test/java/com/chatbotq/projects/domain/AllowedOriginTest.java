package com.chatbotq.projects.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllowedOriginTest {

    @Test
    void canonicalizesSchemeHostAndDefaultPortForExactMatching() {
        AllowedOrigin origin = AllowedOrigin.create(
            UUID.randomUUID(), UUID.randomUUID(), "HTTPS://Example.COM:443", Instant.EPOCH);

        assertEquals("https://example.com", origin.getOrigin());
        assertTrue(origin.matches("https://example.com"));
        assertTrue(origin.matches("https://EXAMPLE.com:443"));
        assertFalse(origin.matches("http://example.com"));
        assertFalse(origin.matches("https://sub.example.com"));
    }

    @Test
    void rejectsPathsCredentialsQueriesFragmentsAndUnsupportedSchemes() {
        assertInvalid("https://example.com/path");
        assertInvalid("https://user:pass@example.com");
        assertInvalid("https://example.com?query=1");
        assertInvalid("https://example.com#fragment");
        assertInvalid("ftp://example.com");
        assertInvalid("https://*.example.com");
    }

    @Test
    void inactiveOriginNeverMatches() {
        AllowedOrigin origin = AllowedOrigin.create(
            UUID.randomUUID(), UUID.randomUUID(), "https://example.com", Instant.EPOCH);

        origin.disable();

        assertFalse(origin.matches("https://example.com"));
    }

    private static void assertInvalid(String value) {
        assertThrows(IllegalArgumentException.class, () -> AllowedOrigin.create(
            UUID.randomUUID(), UUID.randomUUID(), value, Instant.EPOCH));
    }
}
