package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.RefreshSession;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompleteAdminPasswordResetUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

    @Test
    void completesRequiredResetUnderTransactionAndRevokesSessions() {
        Store store = new Store();
        AdminUser user = AdminUser.create(UUID.randomUUID(), "reset@example.com", "hash:Temporary123",
            false, NOW.minusSeconds(30));
        store.user = user;
        CompleteAdminPasswordResetUseCase useCase = new CompleteAdminPasswordResetUseCase(store,
            (raw, encoded) -> ("hash:" + raw).equals(encoded), raw -> "hash:" + raw,
            store, new ImmediateTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));

        useCase.execute(" RESET@example.com ", "Temporary123", "Permanent456");

        assertEquals("hash:Permanent456", store.updatedHash);
        assertTrue(store.revoked);
        assertEquals(1, store.lockCalls);
    }

    @Test
    void rejectsUnknownWrongCredentialWrongStateAndInvalidNewPasswordUniformly() {
        Store store = new Store();
        CompleteAdminPasswordResetUseCase useCase = new CompleteAdminPasswordResetUseCase(store,
            (raw, encoded) -> ("hash:" + raw).equals(encoded), raw -> "hash:" + raw,
            store, new ImmediateTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertInvalid(() -> useCase.execute("missing@example.com", "Temporary123", "Permanent456"));
        store.user = AdminUser.create(UUID.randomUUID(), "reset@example.com", "hash:Temporary123", false, NOW);
        assertInvalid(() -> useCase.execute("reset@example.com", "wrong", "Permanent456"));
        store.user.activate(NOW);
        assertInvalid(() -> useCase.execute("reset@example.com", "Temporary123", "Permanent456"));
        assertThrows(IllegalArgumentException.class,
            () -> useCase.execute("reset@example.com", "Temporary123", "short"));
        assertThrows(IllegalArgumentException.class,
            () -> useCase.execute("reset@example.com", "Temporary123", "Temporary123"));
    }

    private static void assertInvalid(Runnable action) {
        InvalidAuthenticationException failure = assertThrows(InvalidAuthenticationException.class, action::run);
        assertEquals("invalid credentials", failure.getMessage());
    }

    private static final class ImmediateTransaction implements com.chatbotq.identityaccess.application.port.ApplicationTransaction {
        public <T> T execute(Supplier<T> work) { return work.get(); }
    }

    private static final class Store implements AdminUserRepository, RefreshSessionRepository {
        AdminUser user;
        String updatedHash;
        boolean revoked;
        int lockCalls;
        public boolean existsByEmail(String email) { return false; }
        public Optional<AdminUser> findById(UUID id) { return Optional.ofNullable(user); }
        public Optional<AdminUser> findByEmail(String email) { return Optional.ofNullable(user); }
        public Optional<AdminUser> findByIdForUpdate(UUID id) { lockCalls++; return Optional.ofNullable(user); }
        public Optional<AdminUser> findByEmailForUpdate(String email) { lockCalls++; return Optional.ofNullable(user); }
        public AdminUser save(AdminUser value) { user = value; return value; }
        public void completePasswordReset(UUID id, String hash, Instant now) { updatedHash = hash; }
        public void save(RefreshSession session) { }
        public Optional<RefreshSession> findByTokenHash(String hash) { return Optional.empty(); }
        public boolean replaceIfUsable(RefreshSession current, RefreshSession replacement, Instant now) { return false; }
        public void revokeFamilyOrdered(UUID familyId, Instant now) { }
        public void revokeAllByUserOrdered(UUID userId, Instant now) { revoked = true; }
    }
}
