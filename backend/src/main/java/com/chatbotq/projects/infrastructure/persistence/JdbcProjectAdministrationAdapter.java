package com.chatbotq.projects.infrastructure.persistence;

import com.chatbotq.projects.application.model.ManagedProject;
import com.chatbotq.projects.application.model.ManagedProjectPage;
import com.chatbotq.projects.application.model.ManagedSiteKey;
import com.chatbotq.projects.application.port.ProjectAdministrationPort;
import com.chatbotq.projects.application.port.ProjectSiteKeyAdministrationPort;
import com.chatbotq.projects.application.usecase.ForbiddenProjectAdministrationException;
import com.chatbotq.projects.application.usecase.ProjectNotFoundException;
import com.chatbotq.projects.application.usecase.SiteKeyRotationFailedException;
import com.chatbotq.projects.application.usecase.StaleSiteKeyVersionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class JdbcProjectAdministrationAdapter implements ProjectAdministrationPort, ProjectSiteKeyAdministrationPort {
    private static final String COLUMNS = "p.id, p.name, p.status, p.created_at, p.updated_at";
    private static final RowMapper<ManagedProject> MAPPER = new RowMapper<ManagedProject>() {
        @Override public ManagedProject mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ManagedProject((UUID) rs.getObject("id"), rs.getString("name"),
                "ACTIVE".equals(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
        }
    };

    private final JdbcTemplate jdbc;

    public JdbcProjectAdministrationAdapter(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc must not be null");
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ManagedProject createAsGeneralAdmin(UUID actorId, UUID projectId, String name,
                                                UUID siteKey, Instant now) {
        List<ManagedProject> rows = jdbc.query(
            "insert into project (id,name,status,site_key,created_at,updated_at,site_key_rotated_at) "
                + "select ?,?,'ACTIVE',?,?,?,? where exists (select 1 from admin_user u "
                + "where u.id=? and u.status='ACTIVE' and (u.locked_until is null "
                + "or u.locked_until <= current_timestamp) and u.is_general_admin) "
                + "returning id,name,status,created_at,updated_at",
            MAPPER, projectId, name, siteKey, Timestamp.from(now), Timestamp.from(now),
            Timestamp.from(now), actorId);
        if (rows.isEmpty()) throw new ForbiddenProjectAdministrationException();
        return rows.get(0);
    }

    @Override
    public ManagedProject findVisibleById(UUID actorId, UUID projectId) {
        List<ProjectOutcome> outcomes = jdbc.query(
            "with actor as materialized (select u.id,u.is_general_admin from admin_user u where u.id=? "
                + "and u.status='ACTIVE' and (u.locked_until is null or u.locked_until <= current_timestamp)), "
                + "target as materialized (select " + COLUMNS + " from project p where p.id=?), "
                + "decision as (select exists (select 1 from actor a where a.is_general_admin or exists "
                + "(select 1 from target p join user_project_role upr on upr.user_id=a.id "
                + "and upr.project_id=p.id and upr.role='PROJECT_ADMIN' where p.status='ACTIVE')) authorized, "
                + "exists (select 1 from target) project_exists) select d.authorized,d.project_exists," + COLUMNS
                + " from decision d left join target p on d.authorized",
            (rs, rowNum) -> new ProjectOutcome(rs.getBoolean("authorized"),
                rs.getBoolean("project_exists"), rs.getObject("id") == null ? null : MAPPER.mapRow(rs, rowNum)),
            actorId, projectId);
        ProjectOutcome outcome = outcomes.get(0);
        if (!outcome.authorized) throw new ForbiddenProjectAdministrationException();
        if (!outcome.projectExists) throw new ProjectNotFoundException();
        return outcome.project;
    }

    @Override
    public ManagedProjectPage listAsGeneralAdmin(UUID actorId, int page, int size, long offset) {
        final List<ManagedProject> projects = new ArrayList<>();
        final long[] total = {0L};
        final boolean[] authorized = {false};
        jdbc.query("with actor as (select exists (select 1 from admin_user u where u.id=? "
                + "and u.status='ACTIVE' and (u.locked_until is null or u.locked_until <= current_timestamp) "
                + "and u.is_general_admin) authorized), base as materialized (select " + COLUMNS
                + " from project p cross join actor a where a.authorized), totals as (select count(*) "
                + "total_elements from base), paged as (select * from base order by created_at asc, id asc "
                + "limit ? offset ?) select a.authorized, paged.id, paged.name, paged.status, "
                + "paged.created_at, paged.updated_at, totals.total_elements from actor a cross join totals "
                + "left join paged on true order by paged.created_at asc, paged.id asc",
            rs -> {
                authorized[0] = rs.getBoolean("authorized");
                total[0] = rs.getLong("total_elements");
                if (rs.getObject("id") != null) projects.add(MAPPER.mapRow(rs, projects.size()));
            }, actorId, size, offset);
        if (!authorized[0]) throw new ForbiddenProjectAdministrationException();
        return new ManagedProjectPage(projects, page, size, total[0]);
    }

    @Override
    @Transactional
    public ManagedProject updateName(UUID actorId, UUID projectId, String name, Instant now) {
        List<ProjectOutcome> outcomes = jdbc.query(
            "with actor as materialized (select u.id,u.is_general_admin from admin_user u where u.id=? "
                + "and u.status='ACTIVE' and (u.locked_until is null or u.locked_until <= current_timestamp)), "
                + "target as materialized (select p.id,p.status from project p where p.id=?), "
                + "decision as materialized (select exists (select 1 from actor a where a.is_general_admin "
                + "or exists (select 1 from target p join user_project_role upr on upr.user_id=a.id "
                + "and upr.project_id=p.id and upr.role='PROJECT_ADMIN' where p.status='ACTIVE')) authorized, "
                + "exists (select 1 from target) project_exists), updated as (update project p set name=?, "
                + "updated_at=? from decision d where p.id=? and d.authorized returning " + COLUMNS + ") "
                + "select d.authorized,d.project_exists," + COLUMNS
                + " from decision d left join updated p on true",
            (rs, rowNum) -> new ProjectOutcome(rs.getBoolean("authorized"),
                rs.getBoolean("project_exists"), rs.getObject("id") == null ? null : MAPPER.mapRow(rs, rowNum)),
            actorId, projectId, name, Timestamp.from(now), projectId);
        ProjectOutcome outcome = outcomes.get(0);
        if (!outcome.authorized) throw new ForbiddenProjectAdministrationException();
        if (!outcome.projectExists) throw new ProjectNotFoundException();
        return outcome.project;
    }

    @Override
    @Transactional
    public void setActiveAsGeneralAdmin(UUID actorId, UUID projectId, boolean active, Instant now) {
        String status = active ? "ACTIVE" : "DISABLED";
        List<WriteOutcome> outcomes = jdbc.query(
            "with actor as (select exists (select 1 from admin_user u where u.id=? and u.status='ACTIVE' "
                + "and (u.locked_until is null or u.locked_until <= current_timestamp) and u.is_general_admin) "
                + "authorized), updated as (update project p set status=?, updated_at=? from actor a "
                + "where p.id=? and a.authorized and p.status<>? returning p.id) select a.authorized, "
                + "exists (select 1 from project p where p.id=? and a.authorized) project_exists, "
                + "exists (select 1 from updated) changed from actor a",
            (rs, rowNum) -> new WriteOutcome(rs.getBoolean("authorized"), rs.getBoolean("project_exists")),
            actorId, status, Timestamp.from(now), projectId, status, projectId);
        WriteOutcome outcome = outcomes.get(0);
        if (!outcome.authorized) throw new ForbiddenProjectAdministrationException();
        if (!outcome.projectExists) throw new ProjectNotFoundException();
    }

    @Override
    public ManagedSiteKey read(UUID actorId, UUID projectId) {
        List<SiteKeyOutcome> outcomes = jdbc.query(
            "with actor as materialized (select u.id,u.is_general_admin from admin_user u where u.id=? "
                + "and u.status='ACTIVE' and (u.locked_until is null or u.locked_until<=current_timestamp)), "
                + "target as materialized (select p.id,p.status,p.site_key,p.site_key_version,"
                + "p.site_key_rotated_at from project p where p.id=?), decision as (select exists "
                + "(select 1 from actor a where a.is_general_admin or exists (select 1 from target p "
                + "join user_project_role upr on upr.user_id=a.id and upr.project_id=p.id "
                + "and upr.role='PROJECT_ADMIN' where p.status='ACTIVE')) authorized, exists "
                + "(select 1 from target) project_exists) select d.authorized,d.project_exists,"
                + "p.site_key,p.site_key_version,p.site_key_rotated_at from decision d "
                + "left join target p on d.authorized",
            (rs, rowNum) -> new SiteKeyOutcome(rs.getBoolean("authorized"),
                rs.getBoolean("project_exists"), rs.getObject("site_key") == null ? null
                    : new ManagedSiteKey((UUID) rs.getObject("site_key"), rs.getLong("site_key_version"),
                        rs.getTimestamp("site_key_rotated_at").toInstant())), actorId, projectId);
        SiteKeyOutcome outcome = outcomes.get(0);
        if (!outcome.authorized) throw new ForbiddenProjectAdministrationException();
        if (!outcome.projectExists) throw new ProjectNotFoundException();
        return outcome.siteKey;
    }

    @Override
    @Transactional
    public ManagedSiteKey rotateAsGeneralAdmin(UUID actorId, UUID projectId, long expectedVersion,
                                               UUID generatedSiteKey, Instant rotatedAt) {
        List<Boolean> actors = jdbc.query("select u.status='ACTIVE' and u.is_general_admin and "
                + "(u.locked_until is null or u.locked_until<=current_timestamp) authorized "
                + "from admin_user u where u.id=? for update",
            (rs, rowNum) -> rs.getBoolean("authorized"), actorId);
        if (actors.isEmpty() || !actors.get(0)) throw new ForbiddenProjectAdministrationException();

        List<Long> versions = jdbc.query("select p.site_key_version from project p where p.id=? for update",
            (rs, rowNum) -> rs.getLong("site_key_version"), projectId);
        if (versions.isEmpty()) throw new ProjectNotFoundException();
        long currentVersion = versions.get(0);
        if (currentVersion != expectedVersion || currentVersion == Long.MAX_VALUE) {
            throw new StaleSiteKeyVersionException();
        }
        try {
            return jdbc.queryForObject("update project set site_key=?,site_key_version=site_key_version+1,"
                    + "site_key_rotated_at=?,site_key_rotated_by=?,updated_at=? where id=? returning "
                    + "site_key,site_key_version,site_key_rotated_at",
                (rs, rowNum) -> new ManagedSiteKey((UUID) rs.getObject("site_key"),
                    rs.getLong("site_key_version"), rs.getTimestamp("site_key_rotated_at").toInstant()),
                generatedSiteKey, Timestamp.from(rotatedAt), actorId, Timestamp.from(rotatedAt), projectId);
        } catch (DataIntegrityViolationException collisionOrConstraintFailure) {
            throw new SiteKeyRotationFailedException(collisionOrConstraintFailure);
        }
    }

    private static final class WriteOutcome {
        private final boolean authorized;
        private final boolean projectExists;

        private WriteOutcome(boolean authorized, boolean projectExists) {
            this.authorized = authorized;
            this.projectExists = projectExists;
        }
    }

    private static final class ProjectOutcome {
        private final boolean authorized;
        private final boolean projectExists;
        private final ManagedProject project;

        private ProjectOutcome(boolean authorized, boolean projectExists, ManagedProject project) {
            this.authorized = authorized;
            this.projectExists = projectExists;
            this.project = project;
        }
    }

    private static final class SiteKeyOutcome {
        private final boolean authorized;
        private final boolean projectExists;
        private final ManagedSiteKey siteKey;

        private SiteKeyOutcome(boolean authorized, boolean projectExists, ManagedSiteKey siteKey) {
            this.authorized = authorized;
            this.projectExists = projectExists;
            this.siteKey = siteKey;
        }
    }
}
