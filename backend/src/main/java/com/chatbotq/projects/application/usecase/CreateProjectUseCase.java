package com.chatbotq.projects.application.usecase;

import com.chatbotq.projects.application.port.ProjectIdentityGenerator;
import com.chatbotq.projects.application.port.ProjectRepository;
import com.chatbotq.projects.domain.Project;

import java.time.Clock;

public final class CreateProjectUseCase {
    private final ProjectRepository repository;
    private final ProjectIdentityGenerator identities;
    private final Clock clock;

    public CreateProjectUseCase(ProjectRepository repository,
                                ProjectIdentityGenerator identities,
                                Clock clock) {
        this.repository = require(repository, "repository");
        this.identities = require(identities, "identities");
        this.clock = require(clock, "clock");
    }

    public Project execute(String name) {
        Project project = Project.create(
            identities.newProjectId(), name, identities.newSiteKey(), clock.instant());
        return repository.save(project);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
