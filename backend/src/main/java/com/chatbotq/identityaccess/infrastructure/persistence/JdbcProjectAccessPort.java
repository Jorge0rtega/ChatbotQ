package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.port.ProjectAccessPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

public final class JdbcProjectAccessPort implements ProjectAccessPort {
    private final JdbcTemplate jdbc;

    public JdbcProjectAccessPort(JdbcTemplate jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc must not be null");
        }
        this.jdbc = jdbc;
    }

    @Override
    public boolean existsActiveProject(UUID projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        Integer count = jdbc.queryForObject(
            "select count(*) from project where id = ? and status = 'ACTIVE'",
            Integer.class, projectId);
        return count != null && count > 0;
    }
}
