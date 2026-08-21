package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.ProjectAccessPort;
import com.chatbotq.identityaccess.application.port.UserProjectAssignmentRepository;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.UserProjectAssignment;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignProjectAdminUseCaseTest {

    @Test
    void assignsExistingUserToActiveProject() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-20T22:00:00Z");
        AdminUser user = AdminUser.create(
            userId, "admin@example.com", "hash", false, now);
        InMemoryUsers users = new InMemoryUsers(user);
        InMemoryAssignments assignments = new InMemoryAssignments();
        ProjectAccessPort projects = id -> projectId.equals(id);
        AssignProjectAdminUseCase useCase = new AssignProjectAdminUseCase(
            users, projects, assignments, Clock.fixed(now, ZoneOffset.UTC));

        UserProjectAssignment created = useCase.execute(userId, projectId);

        assertTrue(assignments.saved == created);
        assertEquals(userId, created.getUserId());
        assertEquals(projectId, created.getProjectId());
        assertEquals(now, created.getAssignedAt());
    }

    @Test
    void rejectsMissingUserInactiveProjectAndDuplicateAssignment() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        InMemoryAssignments assignments = new InMemoryAssignments();
        AssignProjectAdminUseCase missingUser = new AssignProjectAdminUseCase(
            new InMemoryUsers(null), id -> true, assignments, Clock.systemUTC());
        assertThrows(IllegalStateException.class,
            () -> missingUser.execute(userId, projectId));

        AdminUser user = AdminUser.create(
            userId, "admin@example.com", "hash", false, Instant.EPOCH);
        AssignProjectAdminUseCase inactiveProject = new AssignProjectAdminUseCase(
            new InMemoryUsers(user), id -> false, assignments, Clock.systemUTC());
        assertThrows(IllegalStateException.class,
            () -> inactiveProject.execute(userId, projectId));

        assignments.duplicate = true;
        AssignProjectAdminUseCase duplicate = new AssignProjectAdminUseCase(
            new InMemoryUsers(user), id -> true, assignments, Clock.systemUTC());
        assertThrows(IllegalStateException.class,
            () -> duplicate.execute(userId, projectId));
    }

    private static final class InMemoryUsers implements AdminUserRepository {
        private final AdminUser user;

        private InMemoryUsers(AdminUser user) {
            this.user = user;
        }

        @Override
        public boolean existsByEmail(String normalizedEmail) {
            return false;
        }

        @Override
        public Optional<AdminUser> findById(UUID id) {
            return user != null && user.getId().equals(id) ? Optional.of(user) : Optional.empty();
        }

        @Override
        public AdminUser save(AdminUser value) {
            return value;
        }
    }

    private static final class InMemoryAssignments implements UserProjectAssignmentRepository {
        private boolean duplicate;
        private UserProjectAssignment saved;

        @Override
        public boolean exists(UUID userId, UUID projectId) {
            return duplicate;
        }

        @Override
        public UserProjectAssignment save(UserProjectAssignment assignment) {
            saved = assignment;
            return assignment;
        }
    }
}
