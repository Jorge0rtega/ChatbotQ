package com.chatbotq.projects.infrastructure.persistence;

import com.chatbotq.projects.application.port.AllowedOriginRepository;
import com.chatbotq.projects.domain.AllowedOrigin;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.UUID;

public final class JdbcAllowedOriginRepository implements AllowedOriginRepository {
    private final JdbcTemplate jdbc;

    public JdbcAllowedOriginRepository(JdbcTemplate jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc must not be null");
        }
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(UUID projectId, String canonicalOrigin) {
        if (projectId == null || canonicalOrigin == null || canonicalOrigin.trim().isEmpty()) {
            throw new IllegalArgumentException("projectId and canonicalOrigin are required");
        }
        Integer count = jdbc.queryForObject(
            "select count(*) from project_allowed_origin where project_id = ? and origin = ?",
            Integer.class, projectId, canonicalOrigin);
        return count != null && count > 0;
    }

    @Override
    public AllowedOrigin save(AllowedOrigin origin) {
        if (origin == null) {
            throw new IllegalArgumentException("origin must not be null");
        }
        jdbc.update("insert into project_allowed_origin "
                + "(id, project_id, origin, active, created_at) values (?, ?, ?, ?, ?)",
            origin.getId(), origin.getProjectId(), origin.getOrigin(), origin.isActive(),
            Timestamp.from(origin.getCreatedAt()));
        return origin;
    }
}
