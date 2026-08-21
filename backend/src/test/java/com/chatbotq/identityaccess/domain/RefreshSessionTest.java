package com.chatbotq.identityaccess.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshSessionTest {

    @Test
    void issuesUsableSessionAndRotatesWithinSameFamily() {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant issuedAt = Instant.parse("2026-08-20T22:40:00Z");
        Instant expiresAt = issuedAt.plus(7, ChronoUnit.DAYS);
        RefreshSession session = RefreshSession.issue(
            sessionId, userId, familyId, "old-token-hash", issuedAt, expiresAt);

        assertTrue(session.isUsableAt(issuedAt.plusSeconds(1)));
        assertNull(session.getRotatedAt());
        assertNull(session.getRevokedAt());

        UUID replacementId = UUID.randomUUID();
        Instant rotatedAt = issuedAt.plusSeconds(30);
        RefreshSession replacement = session.rotate(
            replacementId, "new-token-hash", rotatedAt,
            rotatedAt.plus(7, ChronoUnit.DAYS));

        assertFalse(session.isUsableAt(rotatedAt));
        assertEquals(rotatedAt, session.getRotatedAt());
        assertEquals(replacementId, session.getReplacedById());
        assertEquals(familyId, replacement.getFamilyId());
        assertEquals(userId, replacement.getUserId());
        assertTrue(replacement.isUsableAt(rotatedAt.plusSeconds(1)));
    }

    @Test
    void revokedOrExpiredSessionIsNotUsable() {
        Instant issuedAt = Instant.parse("2026-08-20T22:40:00Z");
        Instant expiresAt = issuedAt.plusSeconds(60);
        RefreshSession session = RefreshSession.issue(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "token-hash", issuedAt, expiresAt);

        assertFalse(session.isUsableAt(expiresAt));
        session.revoke(issuedAt.plusSeconds(30));
        assertFalse(session.isUsableAt(issuedAt.plusSeconds(31)));
        assertEquals(issuedAt.plusSeconds(30), session.getRevokedAt());
    }
}
