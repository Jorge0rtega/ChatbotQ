package com.chatbotq.projects.application.usecase;

import com.chatbotq.projects.application.model.ManagedSiteKey;
import com.chatbotq.projects.application.port.ProjectIdentityGenerator;
import com.chatbotq.projects.application.port.ProjectSiteKeyAdministrationPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManageProjectSiteKeyUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");

    @Test
    void readsThroughNarrowPortAndRotatesWithGeneratedIdentityAndClock() {
        UUID actor = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID oldKey = UUID.randomUUID();
        UUID newKey = UUID.randomUUID();
        CapturingPort port = new CapturingPort(oldKey);
        ManageProjectSiteKeyUseCase useCase = new ManageProjectSiteKeyUseCase(port,
            identities(newKey), Clock.fixed(NOW, ZoneOffset.UTC));

        ManagedSiteKey read = useCase.read(actor, project);
        ManagedSiteKey rotated = useCase.rotate(actor, project, 1L);

        assertEquals(oldKey, read.getSiteKey());
        assertEquals(1L, read.getVersion());
        assertEquals(newKey, rotated.getSiteKey());
        assertEquals(2L, rotated.getVersion());
        assertEquals(actor, port.actor);
        assertEquals(project, port.project);
        assertEquals(1L, port.expectedVersion);
        assertEquals(newKey, port.generatedKey);
        assertEquals(NOW, port.rotatedAt);
    }

    @Test
    void rejectsInvalidArgumentsBeforeCallingPortOrGeneratingIdentity() {
        CapturingPort port = new CapturingPort(UUID.randomUUID());
        final int[] generated = {0};
        ProjectIdentityGenerator identities = new ProjectIdentityGenerator() {
            public UUID newProjectId() { return UUID.randomUUID(); }
            public UUID newSiteKey() { generated[0]++; return UUID.randomUUID(); }
        };
        ManageProjectSiteKeyUseCase useCase = new ManageProjectSiteKeyUseCase(port, identities,
            Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(IllegalArgumentException.class, () -> useCase.read(null, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> useCase.read(UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class,
            () -> useCase.rotate(UUID.randomUUID(), UUID.randomUUID(), 0L));
        assertThrows(IllegalArgumentException.class,
            () -> useCase.rotate(UUID.randomUUID(), UUID.randomUUID(), -1L));
        assertEquals(0, port.rotateCalls);
        assertEquals(0, generated[0]);
    }

    private static ProjectIdentityGenerator identities(final UUID key) {
        return new ProjectIdentityGenerator() {
            public UUID newProjectId() { return UUID.randomUUID(); }
            public UUID newSiteKey() { return key; }
        };
    }

    private static final class CapturingPort implements ProjectSiteKeyAdministrationPort {
        private final UUID oldKey;
        private UUID actor;
        private UUID project;
        private long expectedVersion;
        private UUID generatedKey;
        private Instant rotatedAt;
        private int rotateCalls;

        private CapturingPort(UUID oldKey) { this.oldKey = oldKey; }

        public ManagedSiteKey read(UUID actorId, UUID projectId) {
            return new ManagedSiteKey(oldKey, 1L, NOW.minusSeconds(60));
        }

        public ManagedSiteKey rotateAsGeneralAdmin(UUID actorId, UUID projectId, long expected,
                                                   UUID key, Instant now) {
            rotateCalls++;
            actor = actorId;
            project = projectId;
            expectedVersion = expected;
            generatedKey = key;
            rotatedAt = now;
            return new ManagedSiteKey(key, 2L, now);
        }
    }
}
