package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.port.UserProjectAssignmentRepository;
import com.chatbotq.identityaccess.application.usecase.AdminUserConflictException;
import com.chatbotq.identityaccess.application.usecase.AdminUserNotFoundException;
import com.chatbotq.identityaccess.application.usecase.AssignedProjectNotFoundException;
import com.chatbotq.identityaccess.application.usecase.ForbiddenAdminUserAdministrationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class JdbcUserProjectAssignmentRepository implements UserProjectAssignmentRepository {
    private final JdbcTemplate jdbc;

    public JdbcUserProjectAssignmentRepository(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc must not be null");
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> listAsGeneralAdmin(UUID actorId, UUID userId) {
        final boolean[] authorized = {false};
        final boolean[] targetExists = {false};
        final boolean[] targetGeneral = {false};
        final List<UUID> ids = new ArrayList<>();
        jdbc.query("with actor as materialized (select exists(select 1 from admin_user where id=? "
                + "and is_general_admin and status='ACTIVE' and (locked_until is null or locked_until<=current_timestamp)) authorized), "
                + "target as materialized (select id,is_general_admin from admin_user where id=?), decision as (select a.authorized,"
                + "exists(select 1 from target) target_exists,coalesce((select is_general_admin from target),false) target_general from actor a) "
                + "select d.authorized,d.target_exists,d.target_general,upr.project_id from decision d left join user_project_role upr "
                + "on d.authorized and d.target_exists and not d.target_general and upr.user_id=? order by upr.project_id",
            rs -> {
                authorized[0] = rs.getBoolean("authorized");
                targetExists[0] = rs.getBoolean("target_exists");
                targetGeneral[0] = rs.getBoolean("target_general");
                if (rs.getObject("project_id") != null) ids.add((UUID) rs.getObject("project_id"));
            }, actorId, userId, userId);
        classify(authorized[0], targetExists[0], targetGeneral[0]);
        return immutableSorted(ids);
    }

    @Override
    public List<UUID> replaceAsGeneralAdmin(UUID actorId, UUID userId,
                                            List<UUID> projectIds, Instant now) {
        if (actorId == null || userId == null || projectIds == null || now == null) {
            throw new IllegalArgumentException("arguments required");
        }
        List<LockedUser> rows = jdbc.query("select id,is_general_admin,(status='ACTIVE' and "
                + "(locked_until is null or locked_until<=current_timestamp)) available from admin_user "
                + "where id in (?,?) order by id for update",
            (rs, row) -> new LockedUser((UUID) rs.getObject("id"),
                rs.getBoolean("is_general_admin"), rs.getBoolean("available")), actorId, userId);
        Map<UUID, LockedUser> byId = new HashMap<>();
        for (LockedUser row : rows) byId.put(row.id, row);
        LockedUser actor = byId.get(actorId);
        LockedUser target = byId.get(userId);
        classify(actor != null && actor.general && actor.available,
            target != null, target != null && target.general);
        afterUsersLocked();

        List<UUID> sorted = immutableSorted(projectIds);
        lockAndRequireProjects(sorted);
        jdbc.update("delete from user_project_role where user_id=?", userId);
        afterAssignmentsDeleted();
        List<Object[]> batch = new ArrayList<>(sorted.size());
        for (UUID projectId : sorted) {
            batch.add(new Object[]{userId, projectId, "PROJECT_ADMIN", Timestamp.from(now)});
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("insert into user_project_role(user_id,project_id,role,assigned_at) values (?,?,?,?)",
                batch);
            for (UUID projectId : sorted) afterAssignmentInserted(projectId);
        }
        return sorted;
    }

    @Override
    public List<UUID> listActiveForCurrentUser(UUID userId) {
        final boolean[] exists = {false};
        final boolean[] available = {false};
        final boolean[] general = {false};
        final List<UUID> ids = new ArrayList<>();
        jdbc.query("select u.id,(u.status='ACTIVE' and (u.locked_until is null or u.locked_until<=current_timestamp)) available,"
                + "u.is_general_admin,p.id project_id from admin_user u left join user_project_role upr on upr.user_id=u.id "
                + "and not u.is_general_admin left join project p on p.id=upr.project_id and p.status='ACTIVE' where u.id=? order by p.id",
            rs -> {
                exists[0] = true;
                available[0] = rs.getBoolean("available");
                general[0] = rs.getBoolean("is_general_admin");
                if (rs.getObject("project_id") != null) ids.add((UUID) rs.getObject("project_id"));
            }, userId);
        if (!exists[0] || !available[0]) throw new ForbiddenAdminUserAdministrationException();
        return general[0] ? Collections.<UUID>emptyList() : immutableSorted(ids);
    }

    private void lockAndRequireProjects(List<UUID> projectIds) {
        if (projectIds.isEmpty()) return;
        StringBuilder sql = new StringBuilder("select id from project where id in (");
        Object[] args = new Object[projectIds.size()];
        for (int i = 0; i < projectIds.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
            args[i] = projectIds.get(i);
        }
        sql.append(") order by id for update");
        List<UUID> existing = jdbc.query(sql.toString(), args,
            (rs, row) -> (UUID) rs.getObject("id"));
        if (existing.size() != projectIds.size()) throw new AssignedProjectNotFoundException();
    }

    /** Test seam used to prove the enclosing application transaction rolls back replacement. */
    protected void afterAssignmentsDeleted() { }

    /** Test seam invoked after the ordered insert batch has executed, before commit. */
    protected void afterAssignmentInserted(UUID projectId) { }

    /** Test seam used to prove competing writes overlap after the ordered user locks. */
    protected void afterUsersLocked() { }

    private static void classify(boolean authorized, boolean targetExists, boolean targetGeneral) {
        if (!authorized) throw new ForbiddenAdminUserAdministrationException();
        if (!targetExists) throw new AdminUserNotFoundException();
        if (targetGeneral) throw new AdminUserConflictException(
            "general admin cannot have project assignments");
    }

    private static List<UUID> immutableSorted(List<UUID> values) {
        List<UUID> copy = new ArrayList<>(values);
        copy.sort((left, right) -> left.toString().compareTo(right.toString()));
        return Collections.unmodifiableList(copy);
    }

    private static final class LockedUser {
        final UUID id;
        final boolean general;
        final boolean available;
        LockedUser(UUID id, boolean general, boolean available) {
            this.id = id; this.general = general; this.available = available;
        }
    }
}
