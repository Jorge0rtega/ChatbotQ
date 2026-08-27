package com.chatbotq.projects.application.usecase;

import com.chatbotq.projects.application.model.ManagedSiteKey;
import com.chatbotq.projects.application.port.ProjectIdentityGenerator;
import com.chatbotq.projects.application.port.ProjectSiteKeyAdministrationPort;

import java.time.Clock;
import java.util.UUID;

public final class ManageProjectSiteKeyUseCase {
    private final ProjectSiteKeyAdministrationPort siteKeys;
    private final ProjectIdentityGenerator identities;
    private final Clock clock;

    public ManageProjectSiteKeyUseCase(ProjectSiteKeyAdministrationPort siteKeys,
                                       ProjectIdentityGenerator identities, Clock clock) {
        this.siteKeys = require(siteKeys, "siteKeys");
        this.identities = require(identities, "identities");
        this.clock = require(clock, "clock");
    }

    public ManagedSiteKey read(UUID actorId, UUID projectId) {
        return siteKeys.read(require(actorId, "actorId"), require(projectId, "projectId"));
    }

    public ManagedSiteKey rotate(UUID actorId, UUID projectId, long expectedVersion) {
        require(actorId, "actorId");
        require(projectId, "projectId");
        if (expectedVersion < 1L) throw new IllegalArgumentException("expectedVersion must be positive");
        return siteKeys.rotateAsGeneralAdmin(actorId, projectId, expectedVersion,
            require(identities.newSiteKey(), "generatedSiteKey"), clock.instant());
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
