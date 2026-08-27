package com.chatbotq.identityaccess.application.port;

import com.chatbotq.identityaccess.domain.RefreshSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshSessionRepository {
    void save(RefreshSession session);
    Optional<RefreshSession> findByTokenHash(String tokenHash);
    default Optional<RefreshSession> findByTokenHashForUpdate(String tokenHash) {
        return findByTokenHash(tokenHash);
    }

    /**
     * Re-resolves the token and locks every row in its family in ascending session-id order.
     * Callers must first lock the owning {@code admin_user} row inside the shared
     * {@link ApplicationTransaction}. All credential/session writes use the global order
     * {@code admin_user -> admin_refresh_session ORDER BY id}.
     */
    default Optional<RefreshSession> findByTokenHashAndLockFamily(String tokenHash) {
        return findByTokenHashForUpdate(tokenHash);
    }

    default Optional<UUID> findUserIdByTokenHash(String tokenHash) {
        Optional<RefreshSession> session = findByTokenHash(tokenHash);
        return session.isPresent() ? Optional.of(session.get().getUserId()) : Optional.empty();
    }

    /**
     * Inserts the replacement and conditionally consumes the current session without leaving
     * an orphan on CAS loss. Call only after user and family locks inside the shared
     * {@link ApplicationTransaction}.
     */
    boolean replaceIfUsable(RefreshSession current, RefreshSession replacement, Instant now);

    /**
     * Locks every family row by ascending id before revoking it. The owning user row must
     * already be locked in the shared {@link ApplicationTransaction}.
     */
    void revokeFamilyOrdered(UUID familyId, Instant now);

    /**
     * Locks every session row for the user by ascending id before revoking it. The user row
     * must already be locked in the shared {@link ApplicationTransaction}.
     */
    default void revokeAllByUserOrdered(UUID userId, Instant now) {
        throw new UnsupportedOperationException("revokeAllByUserOrdered not implemented");
    }
}
