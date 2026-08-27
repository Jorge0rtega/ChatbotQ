package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.model.ManagedAdminUser;
import com.chatbotq.identityaccess.application.port.AdminUserAdministrationPort;
import com.chatbotq.identityaccess.application.port.AdminUserIdentityGenerator;
import com.chatbotq.identityaccess.application.port.PasswordHasher;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.domain.RefreshSession;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdministerAdminUsersUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
    private final CapturingPort port = new CapturingPort();
    private final java.util.concurrent.atomic.AtomicInteger hashCalls = new java.util.concurrent.atomic.AtomicInteger();
    private final UUID createdId = UUID.randomUUID();
    private final AdministerAdminUsersUseCase useCase = new AdministerAdminUsersUseCase(port,
        raw -> { hashCalls.incrementAndGet(); return "hashed:" + raw; },
        () -> createdId, port, immediateTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsWithNormalizedEmailHashedTemporaryPasswordAndExplicitRole() {
        UUID actor = UUID.randomUUID();
        ManagedAdminUser result = useCase.create(actor, "  New.Admin@Example.COM ",
            "Temporary123", "GENERAL_ADMIN");

        assertEquals(createdId, result.getId());
        assertEquals(actor, port.actor);
        assertEquals("new.admin@example.com", port.email);
        assertEquals("hashed:Temporary123", port.passwordHash);
        assertEquals(true, port.generalAdmin);
        assertEquals(NOW, port.now);
    }

    @Test
    void validatesPasswordEmailRoleAndBoundedPaginationBeforeCallingPort() {
        UUID actor = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> useCase.create(actor, "invalid", "Temporary123", "PROJECT_ADMIN"));
        assertThrows(IllegalArgumentException.class,
            () -> useCase.create(actor, "ok@example.com", "short", "PROJECT_ADMIN"));
        assertThrows(IllegalArgumentException.class,
            () -> useCase.create(actor, "ok@example.com", "Temporary123", "OWNER"));
        assertThrows(IllegalArgumentException.class, () -> useCase.list(actor, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> useCase.list(actor, 0, 101));
        assertThrows(IllegalArgumentException.class,
            () -> useCase.list(actor, Integer.MAX_VALUE, 100));
        assertEquals(0, port.calls);
    }

    @Test
    void unauthorizedActorIsRejectedBeforeExpensiveHashForCreateAndReset() {
        port.authorized = false;
        UUID actor = UUID.randomUUID();
        int before = hashCalls.get();
        assertThrows(ForbiddenAdminUserAdministrationException.class,
            () -> useCase.create(actor, "new@example.com", "Temporary123", "PROJECT_ADMIN"));
        assertThrows(ForbiddenAdminUserAdministrationException.class,
            () -> useCase.resetPassword(actor, UUID.randomUUID(), "Temporary123"));
        assertEquals(before, hashCalls.get());
        assertEquals(0, port.calls);
    }

    @Test
    void delegatesGetUpdateStateAndResetWithNormalizedInputs() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        useCase.get(actor, target);
        useCase.list(actor, 2, 25);
        useCase.updateEmail(actor, target, " Changed@Example.COM ");
        useCase.activate(actor, target);
        useCase.deactivate(actor, target);
        useCase.resetPassword(actor, target, "Replacement123");

        assertEquals("changed@example.com", port.email);
        assertEquals("hashed:Replacement123", port.passwordHash);
        assertEquals(target, port.revokedUser);
        assertEquals(50L, port.offset);
        assertEquals(6, port.calls);
    }

    private static com.chatbotq.identityaccess.application.port.ApplicationTransaction immediateTransaction() {
        return new com.chatbotq.identityaccess.application.port.ApplicationTransaction() {
            public <T> T execute(java.util.function.Supplier<T> work) { return work.get(); }
        };
    }

    private static final class CapturingPort implements AdminUserAdministrationPort, RefreshSessionRepository {
        UUID actor;
        String email;
        String passwordHash;
        boolean generalAdmin;
        Instant now;
        long offset;
        UUID revokedUser;
        int calls;
        boolean authorized = true;

        public boolean canAdminister(UUID actorId) { actor = actorId; return authorized; }
        public ManagedAdminUser createAsGeneralAdmin(UUID actorId, UUID userId, String email,
                String hash, boolean isGeneral, Instant changedAt) {
            calls++; actor = actorId; this.email = email; passwordHash = hash;
            generalAdmin = isGeneral; now = changedAt;
            return user(userId, email, isGeneral);
        }
        public ManagedAdminUser findByIdAsGeneralAdmin(UUID actorId, UUID userId) {
            calls++; return user(userId, "user@example.com", false);
        }
        public com.chatbotq.identityaccess.application.model.ManagedAdminUserPage listAsGeneralAdmin(
                UUID actorId, int page, int size, long requestedOffset) {
            calls++; offset = requestedOffset;
            return new com.chatbotq.identityaccess.application.model.ManagedAdminUserPage(
                java.util.Collections.<ManagedAdminUser>emptyList(), page, size, 0);
        }
        public ManagedAdminUser updateEmailAsGeneralAdmin(UUID actorId, UUID userId,
                String normalizedEmail, Instant changedAt) {
            calls++; email = normalizedEmail; return user(userId, normalizedEmail, false);
        }
        public void setActiveAsGeneralAdmin(UUID actorId, UUID userId, boolean active, Instant changedAt) { calls++; }
        public void resetPasswordAsGeneralAdmin(UUID actorId, UUID userId, String hash, Instant changedAt) {
            calls++; passwordHash = hash;
        }
        public void save(RefreshSession session) { }
        public Optional<RefreshSession> findByTokenHash(String hash) { return Optional.empty(); }
        public boolean replaceIfUsable(RefreshSession current, RefreshSession replacement, Instant now) {
            return false;
        }
        public void revokeFamilyOrdered(UUID familyId, Instant now) { }
        public void revokeAllByUserOrdered(UUID userId, Instant now) { revokedUser = userId; }
        private ManagedAdminUser user(UUID id, String email, boolean general) {
            return new ManagedAdminUser(id, email, general ? "GENERAL_ADMIN" : "PROJECT_ADMIN",
                "PASSWORD_RESET_REQUIRED", NOW, NOW);
        }
    }
}
