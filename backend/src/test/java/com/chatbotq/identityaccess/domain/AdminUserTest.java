package com.chatbotq.identityaccess.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminUserTest {

    @Test
    void createsUserRequiringPasswordResetAndNormalizesEmail() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-20T22:00:00Z");

        AdminUser user = AdminUser.create(
            id, "  Jorge.Ortega@Example.COM  ", "bcrypt-hash", true, now);

        assertEquals(id, user.getId());
        assertEquals("jorge.ortega@example.com", user.getEmail());
        assertEquals(AdminUserStatus.PASSWORD_RESET_REQUIRED, user.getStatus());
        assertTrue(user.isGeneralAdmin());
        assertEquals(0, user.getFailedLoginCount());
        assertFalse(user.isLockedAt(now));
        assertEquals(now, user.getCreatedAt());
        assertEquals(now, user.getUpdatedAt());
    }

    @Test
    void activatesAndDisablesUserWithoutChangingIdentity() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-20T22:00:00Z");
        AdminUser user = AdminUser.create(id, "admin@example.com", "hash", false, createdAt);

        user.activate(createdAt.plusSeconds(10));
        assertEquals(AdminUserStatus.ACTIVE, user.getStatus());

        user.disable(createdAt.plusSeconds(20));
        assertEquals(AdminUserStatus.DISABLED, user.getStatus());
        assertEquals(id, user.getId());
        assertEquals(createdAt.plusSeconds(20), user.getUpdatedAt());
    }

    @Test
    void rejectsInvalidEmailAndBlankPasswordHash() {
        Instant now = Instant.parse("2026-08-20T22:00:00Z");

        assertThrows(IllegalArgumentException.class,
            () -> AdminUser.create(UUID.randomUUID(), "correo-invalido", "hash", false, now));
        assertThrows(IllegalArgumentException.class,
            () -> AdminUser.create(UUID.randomUUID(), "admin@example.com", "  ", false, now));
    }
}
