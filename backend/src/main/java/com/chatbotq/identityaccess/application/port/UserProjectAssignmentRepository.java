package com.chatbotq.identityaccess.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Assignment administration boundary. Writes first lock actor and target together by ascending
 * admin_user UUID, then project rows by ascending UUID; see {@link AdminUserAdministrationPort}.
 */
public interface UserProjectAssignmentRepository {
    List<UUID> listAsGeneralAdmin(UUID actorId, UUID userId);
    List<UUID> replaceAsGeneralAdmin(UUID actorId, UUID userId, List<UUID> projectIds, Instant now);
    List<UUID> listActiveForCurrentUser(UUID userId);
}
