package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.port.ProjectAdministrationDecisionPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

public final class JdbcProjectAdministrationDecisionAdapter
    implements ProjectAdministrationDecisionPort {

    private static final String DECISION_SQL =
        "select exists ("
            + "select 1 "
            + "from admin_user u "
            + "join project p on p.id = ? and p.status = 'ACTIVE' "
            + "where u.id = ? "
            + "and u.status = 'ACTIVE' "
            + "and (u.locked_until is null or u.locked_until <= current_timestamp) "
            + "and (u.is_general_admin or exists ("
                + "select 1 from user_project_role upr "
                + "where upr.user_id = u.id "
                + "and upr.project_id = p.id "
                + "and upr.role = 'PROJECT_ADMIN'"
            + "))"
        + ")";

    private final JdbcTemplate jdbc;

    public JdbcProjectAdministrationDecisionAdapter(JdbcTemplate jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc must not be null");
        }
        this.jdbc = jdbc;
    }

    @Override
    public boolean canAdminister(UUID userId, UUID projectId) {
        if (userId == null || projectId == null) {
            throw new IllegalArgumentException("userId and projectId must not be null");
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
            DECISION_SQL, Boolean.class, projectId, userId));
    }
}
