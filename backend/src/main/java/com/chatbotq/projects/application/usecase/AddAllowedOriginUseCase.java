package com.chatbotq.projects.application.usecase;

import com.chatbotq.projects.application.port.AllowedOriginIdentityGenerator;
import com.chatbotq.projects.application.port.AllowedOriginRepository;
import com.chatbotq.projects.application.port.ProjectStatusPort;
import com.chatbotq.projects.domain.AllowedOrigin;

import java.time.Clock;
import java.util.UUID;

public final class AddAllowedOriginUseCase {
    private final ProjectStatusPort projects;
    private final AllowedOriginRepository origins;
    private final AllowedOriginIdentityGenerator identities;
    private final Clock clock;

    public AddAllowedOriginUseCase(ProjectStatusPort projects,
                                   AllowedOriginRepository origins,
                                   AllowedOriginIdentityGenerator identities,
                                   Clock clock) {
        this.projects = require(projects, "projects");
        this.origins = require(origins, "origins");
        this.identities = require(identities, "identities");
        this.clock = require(clock, "clock");
    }

    public AllowedOrigin execute(UUID projectId, String origin) {
        AllowedOrigin candidate = AllowedOrigin.create(
            identities.newAllowedOriginId(), projectId, origin, clock.instant());
        if (!projects.existsActiveProject(projectId)) {
            throw new IllegalStateException("active project does not exist");
        }
        if (origins.exists(projectId, candidate.getOrigin())) {
            throw new IllegalStateException("allowed origin already exists");
        }
        return origins.save(candidate);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
