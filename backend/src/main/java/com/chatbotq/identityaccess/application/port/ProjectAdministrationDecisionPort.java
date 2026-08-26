package com.chatbotq.identityaccess.application.port;

import java.util.UUID;

/**
 * Decides project administration from the current persisted state in one atomic read.
 * Future write use cases must revalidate this decision inside their transaction, or use a
 * conditional write, rather than relying only on the earlier method-security check.
 */
public interface ProjectAdministrationDecisionPort {
    boolean canAdminister(UUID userId, UUID projectId);
}
