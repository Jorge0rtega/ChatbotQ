package com.chatbotq.projects.application.usecase;

import com.chatbotq.projects.application.model.ManagedProject;
import com.chatbotq.projects.application.model.ManagedProjectPage;
import com.chatbotq.projects.application.port.ProjectAdministrationPort;
import com.chatbotq.projects.application.port.ProjectIdentityGenerator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdministerProjectsUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

    @Test
    void normalizesNamesAndUsesServerControlledIdentitiesAndTime() {
        UUID actor = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID siteKey = UUID.randomUUID();
        CapturingPort port = new CapturingPort();
        AdministerProjectsUseCase useCase = new AdministerProjectsUseCase(port, identities(project, siteKey),
            Clock.fixed(NOW, ZoneOffset.UTC));

        ManagedProject created = useCase.create(actor, "  Project  ");

        assertEquals(actor, port.actor);
        assertEquals(project, port.project);
        assertEquals(siteKey, port.siteKey);
        assertEquals("Project", port.name);
        assertEquals(NOW, port.now);
        assertEquals(project, created.getId());
    }

    @Test
    void rejectsInvalidNamesAndPaginationBeforeCallingPort() {
        CapturingPort port = new CapturingPort();
        AdministerProjectsUseCase useCase = new AdministerProjectsUseCase(port,
            identities(UUID.randomUUID(), UUID.randomUUID()), Clock.fixed(NOW, ZoneOffset.UTC));
        UUID actor = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> useCase.create(actor, "   "));
        assertThrows(IllegalArgumentException.class, () -> useCase.create(actor, "\u2003\u2009"));
        assertThrows(IllegalArgumentException.class, () -> useCase.create(actor, "valid\u0000name"));
        assertThrows(IllegalArgumentException.class, () -> useCase.create(actor, null));
        assertThrows(IllegalArgumentException.class, () -> useCase.updateName(actor, UUID.randomUUID(), repeat('x', 161)));
        assertThrows(IllegalArgumentException.class, () -> useCase.list(actor, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> useCase.list(actor, 0, 101));
        assertThrows(IllegalArgumentException.class, () -> useCase.list(actor, Integer.MAX_VALUE, 100));
        assertEquals(0, port.calls);
    }

    @Test
    void acceptsBoundaryNamesAndExactMaximumOffset() {
        CapturingPort port = new CapturingPort();
        AdministerProjectsUseCase useCase = new AdministerProjectsUseCase(port,
            identities(UUID.randomUUID(), UUID.randomUUID()), Clock.fixed(NOW, ZoneOffset.UTC));
        UUID actor = UUID.randomUUID();

        assertEquals("x", useCase.create(actor, "x").getName());
        assertEquals(repeat('x', 160), useCase.create(actor, repeat('x', 160)).getName());
        int pageAtLimit = (int) (AdministerProjectsUseCase.MAX_OFFSET / 100L);
        useCase.list(actor, pageAtLimit, 100);

        assertEquals(AdministerProjectsUseCase.MAX_OFFSET, port.offset);
    }

    private static ProjectIdentityGenerator identities(final UUID project, final UUID siteKey) {
        return new ProjectIdentityGenerator() {
            public UUID newProjectId() { return project; }
            public UUID newSiteKey() { return siteKey; }
        };
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static final class CapturingPort implements ProjectAdministrationPort {
        UUID actor;
        UUID project;
        UUID siteKey;
        String name;
        Instant now;
        long offset;
        int calls;

        public ManagedProject createAsGeneralAdmin(UUID actorId, UUID projectId, String projectName,
                                                    UUID generatedSiteKey, Instant createdAt) {
            calls++;
            actor = actorId; project = projectId; name = projectName; siteKey = generatedSiteKey; now = createdAt;
            return new ManagedProject(projectId, projectName, true, createdAt, createdAt);
        }
        public ManagedProject findVisibleById(UUID actorId, UUID projectId) { throw new UnsupportedOperationException(); }
        public ManagedProjectPage listAsGeneralAdmin(UUID actorId, int page, int size, long requestedOffset) {
            calls++; offset = requestedOffset;
            return new ManagedProjectPage(Collections.<ManagedProject>emptyList(), page, size, 0);
        }
        public ManagedProject updateName(UUID actorId, UUID projectId, String projectName, Instant changedAt) {
            calls++; return new ManagedProject(projectId, projectName, true, changedAt, changedAt);
        }
        public void setActiveAsGeneralAdmin(UUID actorId, UUID projectId, boolean active, Instant changedAt) { calls++; }
    }
}
