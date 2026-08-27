package com.chatbotq.identityaccess.application.port;

import com.chatbotq.identityaccess.domain.AdminUser;

import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository {
    boolean existsByEmail(String normalizedEmail);

    Optional<AdminUser> findById(UUID id);

    default Optional<AdminUser> findByEmail(String normalizedEmail) {
        return Optional.empty();
    }

    /**
     * Locks the credential row inside the shared {@link ApplicationTransaction}. Any session
     * locks must follow it and use ascending session-id order.
     */
    default Optional<AdminUser> findByIdForUpdate(UUID id) {
        return findById(id);
    }

    /**
     * Locks the credential row inside the shared {@link ApplicationTransaction}. Any session
     * locks must follow it and use ascending session-id order.
     */
    default Optional<AdminUser> findByEmailForUpdate(String normalizedEmail) {
        return findByEmail(normalizedEmail);
    }

    default void completePasswordReset(UUID id, String passwordHash, java.time.Instant now) {
        throw new UnsupportedOperationException("completePasswordReset not implemented");
    }

    AdminUser save(AdminUser user);
}
