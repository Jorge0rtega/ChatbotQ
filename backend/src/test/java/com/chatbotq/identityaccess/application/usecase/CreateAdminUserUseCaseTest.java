package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.AdminUserIdentityGenerator;
import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.PasswordHasher;
import com.chatbotq.identityaccess.domain.AdminUser;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateAdminUserUseCaseTest {

    @Test
    void hashesTemporaryPasswordAndPersistsUser() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-20T22:00:00Z");
        InMemoryUsers users = new InMemoryUsers();
        PasswordHasher hasher = raw -> "hashed:" + raw;
        AdminUserIdentityGenerator identities = () -> userId;
        CreateAdminUserUseCase useCase = new CreateAdminUserUseCase(
            users, hasher, identities, Clock.fixed(now, ZoneOffset.UTC));

        AdminUser created = useCase.execute(
            "Admin@Example.com", "temporal-segura", false);

        assertEquals(userId, created.getId());
        assertEquals("admin@example.com", created.getEmail());
        assertEquals("hashed:temporal-segura", created.getPasswordHash());
        assertTrue(users.saved == created);
    }

    @Test
    void rejectsDuplicateEmailBeforeHashingOrSaving() {
        InMemoryUsers users = new InMemoryUsers();
        users.duplicate = true;
        int[] hashCalls = {0};
        PasswordHasher hasher = raw -> {
            hashCalls[0]++;
            return "hash";
        };
        CreateAdminUserUseCase useCase = new CreateAdminUserUseCase(
            users, hasher, UUID::randomUUID, Clock.systemUTC());

        assertThrows(IllegalStateException.class,
            () -> useCase.execute("admin@example.com", "temporal", false));
        assertEquals(0, hashCalls[0]);
    }

    private static final class InMemoryUsers implements AdminUserRepository {
        private boolean duplicate;
        private AdminUser saved;

        @Override
        public boolean existsByEmail(String normalizedEmail) {
            return duplicate;
        }

        @Override
        public Optional<AdminUser> findById(UUID id) {
            return Optional.empty();
        }

        @Override
        public AdminUser save(AdminUser user) {
            saved = user;
            return user;
        }
    }
}
