package com.chatbotq.identityaccess.application.port;

import com.chatbotq.identityaccess.domain.RefreshSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshSessionRepository {
    void save(RefreshSession session);
    Optional<RefreshSession> findByTokenHash(String tokenHash);
    boolean replaceIfUsable(RefreshSession current, RefreshSession replacement, Instant now);
    void revokeFamily(UUID familyId, Instant now);
}
