package com.chatbotq.identityaccess.application.port;

import com.chatbotq.identityaccess.application.model.CurrentAdminView;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads current identity, role, availability and ACTIVE project ids from one statement snapshot.
 * Implementations return empty for a missing, non-ACTIVE or currently locked administrator.
 */
public interface CurrentAdminViewPort {
    Optional<CurrentAdminView> findAvailable(UUID userId);
}
