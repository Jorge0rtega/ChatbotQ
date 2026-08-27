package com.chatbotq.identityaccess.application.port;

import com.chatbotq.identityaccess.application.model.ManagedAdminUser;
import com.chatbotq.identityaccess.application.model.ManagedAdminUserPage;

import java.time.Instant;
import java.util.UUID;

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
     * Revalidates the actor, locks active general administrators by id, then locks and mutates
     * the target credential. The caller must run this inside the shared
     * {@link ApplicationTransaction} and lock/revoke target sessions afterwards.
     */
    void resetPasswordAsGeneralAdmin(UUID actorId, UUID userId, String passwordHash, Instant now);
}
