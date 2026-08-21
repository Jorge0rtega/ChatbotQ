package com.chatbotq.projects.application.usecase;

import com.chatbotq.projects.application.port.AllowedOriginIdentityGenerator;
import com.chatbotq.projects.application.port.AllowedOriginRepository;
import com.chatbotq.projects.application.port.ProjectStatusPort;
import com.chatbotq.projects.domain.AllowedOrigin;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddAllowedOriginUseCaseTest {

    @Test
    void canonicalizesAndPersistsOriginForActiveProject() {
        UUID projectId = UUID.randomUUID();
        UUID originId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-20T22:30:00Z");
        InMemoryOrigins origins = new InMemoryOrigins();
        ProjectStatusPort projects = id -> projectId.equals(id);
        AllowedOriginIdentityGenerator identities = () -> originId;
        AddAllowedOriginUseCase useCase = new AddAllowedOriginUseCase(
            projects, origins, identities, Clock.fixed(now, ZoneOffset.UTC));

        AllowedOrigin created = useCase.execute(
            projectId, "HTTPS://Example.COM:443");

        assertTrue(origins.saved == created);
        assertEquals(originId, created.getId());
        assertEquals(projectId, created.getProjectId());
        assertEquals("https://example.com", created.getOrigin());
        assertEquals(now, created.getCreatedAt());
    }

    @Test
    void rejectsInactiveProjectAndCanonicalDuplicate() {
        UUID projectId = UUID.randomUUID();
        InMemoryOrigins origins = new InMemoryOrigins();
        AddAllowedOriginUseCase inactive = new AddAllowedOriginUseCase(
            id -> false, origins, UUID::randomUUID, Clock.systemUTC());

        assertThrows(IllegalStateException.class,
            () -> inactive.execute(projectId, "https://example.com"));

        origins.duplicate = true;
        AddAllowedOriginUseCase duplicate = new AddAllowedOriginUseCase(
            id -> true, origins, UUID::randomUUID, Clock.systemUTC());
        assertThrows(IllegalStateException.class,
            () -> duplicate.execute(projectId, "HTTPS://EXAMPLE.COM:443"));
        assertEquals("https://example.com", origins.lastCheckedOrigin);
    }

    private static final class InMemoryOrigins implements AllowedOriginRepository {
        private boolean duplicate;
        private String lastCheckedOrigin;
        private AllowedOrigin saved;

        @Override
        public boolean exists(UUID projectId, String canonicalOrigin) {
            lastCheckedOrigin = canonicalOrigin;
            return duplicate;
        }

        @Override
        public AllowedOrigin save(AllowedOrigin origin) {
            saved = origin;
            return origin;
        }
    }
}
