package com.chatbotq.identityaccess.application.port;

import com.chatbotq.identityaccess.application.model.ManagedAdminUser;
import com.chatbotq.identityaccess.application.model.ManagedAdminUserPage;

import java.time.Instant;
import java.util.UUID;

/**
 * Administrative writes share one global lock protocol with project assignments: every existing
 * {@code admin_user} row relevant to a mutation (actor, target and any general-admin guard rows)
 * is selected together in ascending UUID order with {@code FOR UPDATE}. Project rows, when any,
 * are locked only afterwards in ascending UUID order. Implementations must not pre-lock a subset.
 */
public interface AdminUserAdministrationPort {
    /** Fast DB-backed denial before expensive password hashing; every write revalidates under lock. */
    boolean canAdminister(UUID actorId);
    ManagedAdminUser createAsGeneralAdmin(UUID actorId, UUID userId, String email,
                                          String passwordHash, boolean generalAdmin, Instant now);
    ManagedAdminUser findByIdAsGeneralAdmin(UUID actorId, UUID userId);
    ManagedAdminUserPage listAsGeneralAdmin(UUID actorId, int page, int size, long offset);
    ManagedAdminUser updateEmailAsGeneralAdmin(UUID actorId, UUID userId, String email, Instant now);
    void setActiveAsGeneralAdmin(UUID actorId, UUID userId, boolean active, Instant now);
    /**
     * Revalidates actor and locks actor plus target together by ascending id before mutating the
     * target credential. The caller must run this inside the shared {@link ApplicationTransaction}
     * and lock/revoke target sessions afterwards.
     */
    void resetPasswordAsGeneralAdmin(UUID actorId, UUID userId, String passwordHash, Instant now);
}
