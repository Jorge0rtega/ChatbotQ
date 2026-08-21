package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.ProjectAccessPort;
import com.chatbotq.identityaccess.application.port.UserProjectAssignmentRepository;
import com.chatbotq.identityaccess.domain.UserProjectAssignment;

import java.time.Clock;
import java.util.UUID;

public final class AssignProjectAdminUseCase {
    private final AdminUserRepository users;
    private final ProjectAccessPort projects;
    private final UserProjectAssignmentRepository assignments;
    private final Clock clock;

    public AssignProjectAdminUseCase(AdminUserRepository users,
                                     ProjectAccessPort projects,
                                     UserProjectAssignmentRepository assignments,
                                     Clock clock) {
        this.users = require(users, "users");
        this.projects = require(projects, "projects");
        this.assignments = require(assignments, "assignments");
        this.clock = require(clock, "clock");
    }

    public UserProjectAssignment execute(UUID userId, UUID projectId) {
        if (!users.findById(userId).isPresent()) {
            throw new IllegalStateException("admin user does not exist");
        }
        if (!projects.existsActiveProject(projectId)) {
            throw new IllegalStateException("active project does not exist");
        }
        if (assignments.exists(userId, projectId)) {
            throw new IllegalStateException("project assignment already exists");
        }
        return assignments.save(
            UserProjectAssignment.projectAdmin(userId, projectId, clock.instant()));
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
