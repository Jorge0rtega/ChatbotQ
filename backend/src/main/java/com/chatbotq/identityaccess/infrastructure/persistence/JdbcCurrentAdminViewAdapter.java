package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.model.CurrentAdminView;
import com.chatbotq.identityaccess.application.port.CurrentAdminViewPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcCurrentAdminViewAdapter implements CurrentAdminViewPort {
    private static final String SQL =
        "select u.id,u.email,u.is_general_admin,(u.status='ACTIVE' and " +
        "(u.locked_until is null or u.locked_until<=current_timestamp)) available," +
        "case when u.is_general_admin then array[]::uuid[] else " +
        "coalesce(array_agg(p.id order by p.id) filter (where p.id is not null),array[]::uuid[]) end project_ids " +
        "from admin_user u left join user_project_role upr on upr.user_id=u.id and not u.is_general_admin " +
        "left join project p on p.id=upr.project_id and p.status='ACTIVE' where u.id=? " +
        "group by u.id,u.email,u.is_general_admin,u.status,u.locked_until";

    private final JdbcTemplate jdbc;

    public JdbcCurrentAdminViewAdapter(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc must not be null");
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CurrentAdminView> findAvailable(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
        return jdbc.query(SQL, rs -> {
            if (!rs.next()) return Optional.empty();
            if (!rs.getBoolean("available")) return Optional.empty();
            boolean general = rs.getBoolean("is_general_admin");
            List<UUID> projects = general ? Collections.<UUID>emptyList() : uuidList(rs.getArray("project_ids"));
            return Optional.of(new CurrentAdminView((UUID) rs.getObject("id"), rs.getString("email"),
                general, projects));
        }, userId);
    }

    private static List<UUID> uuidList(Array sqlArray) throws java.sql.SQLException {
        Object[] values = (Object[]) sqlArray.getArray();
        List<UUID> result = new ArrayList<>(values.length);
        for (Object value : values) result.add(value instanceof UUID ? (UUID) value : UUID.fromString(value.toString()));
        return result;
    }
}
