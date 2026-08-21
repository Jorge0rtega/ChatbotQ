package com.chatbotq.projects.application.usecase;

import com.chatbotq.projects.application.port.ProjectIdentityGenerator;
import com.chatbotq.projects.application.port.ProjectRepository;
import com.chatbotq.projects.domain.Project;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CreateProjectUseCaseTest {

    @Test
    void createsAndPersistsProjectUsingInjectedIdentityAndClock() {
        UUID projectId = UUID.randomUUID();
        UUID siteKey = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-20T22:00:00Z");
        AtomicReference<Project> saved = new AtomicReference<>();
        ProjectRepository repository = project -> {
            saved.set(project);
            return project;
        };
        ProjectIdentityGenerator identities = new ProjectIdentityGenerator() {
            @Override
            public UUID newProjectId() {
                return projectId;
            }

            @Override
            public UUID newSiteKey() {
                return siteKey;
            }
        };
        CreateProjectUseCase useCase = new CreateProjectUseCase(
            repository, identities, Clock.fixed(now, ZoneOffset.UTC));

        Project created = useCase.execute("Proyecto piloto");

        assertSame(created, saved.get());
        assertEquals(projectId, created.getId());
        assertEquals(siteKey, created.getSiteKey());
        assertEquals("Proyecto piloto", created.getName());
        assertEquals(now, created.getCreatedAt());
    }
}
