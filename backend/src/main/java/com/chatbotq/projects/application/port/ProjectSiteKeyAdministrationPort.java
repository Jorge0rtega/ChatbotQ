package com.chatbotq.projects.application.port;

import com.chatbotq.projects.application.model.ManagedSiteKey;

import java.time.Instant;
import java.util.UUID;

public interface ProjectSiteKeyAdministrationPort {
    ManagedSiteKey read(UUID actorId, UUID projectId);

    ManagedSiteKey rotateAsGeneralAdmin(UUID actorId, UUID projectId, long expectedVersion,
                                        UUID generatedSiteKey, Instant rotatedAt);
}
