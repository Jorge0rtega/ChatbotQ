package com.chatbotq.identityaccess.infrastructure.security;

import com.chatbotq.identityaccess.domain.AdminUser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityTokenAdaptersTest {
    private static final String TEST_SECRET = "test-only-signing-key-at-least-thirty-two-bytes-long";

    @Test
    void opaqueTokensAreRandomAndOnlyDeterministicSha256HashesArePersistable() {
        SecureRefreshTokenManager manager = new SecureRefreshTokenManager();

        String first = manager.generate();
        String second = manager.generate();

        assertNotEquals(first, second);
        assertFalse(first.contains("="));
        assertTrue(first.length() >= 43);
        assertEquals(64, manager.hash(first).length());
        assertEquals(manager.hash(first), manager.hash(first));
        assertNotEquals(first, manager.hash(first));
    }

    @Test
    void jwtCarriesShortLivedAdminIdentityAndRejectsTamperingAndWrongIssuer() {
        JwtAccessTokenService service = new JwtAccessTokenService(
            TEST_SECRET, "chatbotq-test", Duration.ofMinutes(5));
        AdminUser user = AdminUser.create(UUID.randomUUID(), "admin@example.com",
            "hash", true, Instant.parse("2026-08-25T09:00:00Z"));
        user.activate(Instant.parse("2026-08-25T09:01:00Z"));
        Instant issuedAt = Instant.parse("2026-08-25T10:00:00Z");

        String token = service.issue(user, issuedAt);
        AdminAccessPrincipal principal = service.validate(token, issuedAt.plusSeconds(1));

        assertEquals(user.getId(), principal.getUserId());
        assertEquals("admin@example.com", principal.getEmail());
        assertTrue(principal.isGeneralAdmin());
        assertEquals(300, service.getExpiresInSeconds());
        assertThrows(InvalidAccessTokenException.class,
            () -> service.validate(token + "tampered", issuedAt.plusSeconds(1)));
        assertThrows(InvalidAccessTokenException.class,
            () -> service.validate(token, issuedAt.plusSeconds(301)));
        JwtAccessTokenService wrongIssuer = new JwtAccessTokenService(
            TEST_SECRET, "other-issuer", Duration.ofMinutes(5));
        assertThrows(InvalidAccessTokenException.class,
            () -> wrongIssuer.validate(token, issuedAt.plusSeconds(1)));
    }

    @Test
    void jwtConfigurationRejectsShortSecretAndUnboundedDurations() {
        assertThrows(IllegalArgumentException.class,
            () -> new JwtAccessTokenService("short", "issuer", Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class,
            () -> new JwtAccessTokenService(repeat(' ', 32), "issuer", Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class,
            () -> new JwtAccessTokenService(TEST_SECRET, "issuer", Duration.ofMinutes(31)));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
