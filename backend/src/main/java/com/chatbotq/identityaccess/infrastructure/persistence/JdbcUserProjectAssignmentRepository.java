package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.port.UserProjectAssignmentRepository;
import com.chatbotq.identityaccess.domain.UserProjectAssignment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.UUID;

public final class JdbcUserProjectAssignmentRepository
    implements UserProjectAssignmentRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserProjectAssignmentRepository(JdbcTemplate jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc must not be null");
        }
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(UUID userId, UUID projectId) {
        if (userId == null || projectId == null) {
            throw new IllegalArgumentException("userId and projectId must not be null");
        }
        Integer count = jdbc.queryForObject(
            "select count(*) from user_project_role where user_id = ? and project_id = ?",
            Integer.class, userId, projectId);
        return count != null && count > 0;
    }

    @Override
    public UserProjectAssignment save(UserProjectAssignment assignment) {
        if (assignment == null) {
            throw new IllegalArgumentException("assignment must not be null");
        }
        jdbc.update("insert into user_project_role (user_id, project_id, role, assigned_at) "
                + "values (?, ?, ?, ?)",
            assignment.getUserId(), assignment.getProjectId(), assignment.getRole().name(),
            Timestamp.from(assignment.getAssignedAt()));
        return assignment;
    }
}
