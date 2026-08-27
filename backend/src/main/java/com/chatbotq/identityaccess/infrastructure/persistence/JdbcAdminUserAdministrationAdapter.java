package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.model.ManagedAdminUser;
import com.chatbotq.identityaccess.application.model.ManagedAdminUserPage;
import com.chatbotq.identityaccess.application.port.AdminUserAdministrationPort;

import com.chatbotq.identityaccess.application.usecase.AdminUserConflictException;
import com.chatbotq.identityaccess.application.usecase.AdminUserNotFoundException;
import com.chatbotq.identityaccess.application.usecase.ForbiddenAdminUserAdministrationException;
import org.springframework.dao.DuplicateKeyException;
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

public class JdbcAdminUserAdministrationAdapter implements AdminUserAdministrationPort {

    private static final String COLUMNS = "u.id,u.email,u.is_general_admin,u.status,u.created_at,u.updated_at";
    private static final String ACTOR = "select exists (select 1 from admin_user a where a.id=? "
        + "and a.is_general_admin and a.status='ACTIVE' and "
        + "(a.locked_until is null or a.locked_until<=current_timestamp)) authorized";
    private static final String GUARDED_ACTOR = "guard as materialized (select g.id from admin_user g "
        + "where g.is_general_admin and g.status='ACTIVE' order by g.id for update), actor as materialized "
        + "(select exists (select 1 from guard g join admin_user a on a.id=g.id where a.id=? "
        + "and (a.locked_until is null or a.locked_until<=current_timestamp)) authorized)";
    private static final RowMapper<ManagedAdminUser> MAPPER = new RowMapper<ManagedAdminUser>() {
        @Override public ManagedAdminUser mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ManagedAdminUser((UUID) rs.getObject("id"), rs.getString("email"),
                rs.getBoolean("is_general_admin") ? "GENERAL_ADMIN" : "PROJECT_ADMIN",
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
        }
    };

    private final JdbcTemplate jdbc;
    public JdbcAdminUserAdministrationAdapter(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc must not be null");
        this.jdbc = jdbc;
    }

    @Override
    public boolean canAdminister(UUID actorId) {
        Boolean authorized = jdbc.queryForObject(ACTOR, Boolean.class, actorId);
        return Boolean.TRUE.equals(authorized);
    }

    @Override
    @Transactional
    public ManagedAdminUser createAsGeneralAdmin(UUID actorId, UUID userId, String email,
                                                  String passwordHash, boolean generalAdmin, Instant now) {
        List<UserOutcome> outcomes = jdbc.query(
            "with " + GUARDED_ACTOR + ", inserted as ("
                + "insert into admin_user(id,email,password_hash,status,is_general_admin,failed_login_count,locked_until,created_at,updated_at) "
                + "select ?,?,?,'PASSWORD_RESET_REQUIRED',?,0,null,?,? from actor a where a.authorized "
                + "on conflict do nothing returning *) select a.authorized,"
                + "exists(select 1 from admin_user x where lower(x.email)=lower(?) and a.authorized) target_exists,"
                + COLUMNS + " from actor a left join inserted u on true",
            outcomeMapper(), actorId, userId, email, passwordHash, generalAdmin,
            Timestamp.from(now), Timestamp.from(now), email);
        UserOutcome outcome = outcomes.get(0);
        if (!outcome.authorized) throw new ForbiddenAdminUserAdministrationException();
        if (outcome.user == null) throw new AdminUserConflictException("admin user email already exists");
        return outcome.user;
    }

    @Override
    public ManagedAdminUser findByIdAsGeneralAdmin(UUID actorId, UUID userId) {
        List<UserOutcome> outcomes = jdbc.query(
            "with actor as materialized (" + ACTOR + "), target as materialized (select " + COLUMNS
                + " from admin_user u where u.id=?), decision as (select a.authorized,"
                + "exists(select 1 from target) target_exists from actor a) "
                + "select d.authorized,d.target_exists," + COLUMNS
                + " from decision d left join target u on d.authorized",
            outcomeMapper(), actorId, userId);
        return requireUser(outcomes.get(0));
    }

    @Override
    public ManagedAdminUserPage listAsGeneralAdmin(UUID actorId, int page, int size, long offset) {
        final List<ManagedAdminUser> users = new ArrayList<>();
        final boolean[] authorized = {false};
        final long[] total = {0};
        jdbc.query("with actor as materialized (" + ACTOR + "), base as materialized (select " + COLUMNS
                + " from admin_user u cross join actor a where a.authorized), totals as (select count(*) total_elements from base), "
                + "paged as (select * from base order by created_at asc,id asc limit ? offset ?) "
                + "select a.authorized,paged.id,paged.email,paged.is_general_admin,paged.status,paged.created_at,paged.updated_at,"
                + "totals.total_elements from actor a cross join totals left join paged on true "
                + "order by paged.created_at asc,paged.id asc",
            rs -> {
                authorized[0] = rs.getBoolean("authorized");
                total[0] = rs.getLong("total_elements");
                if (rs.getObject("id") != null) users.add(MAPPER.mapRow(rs, users.size()));
            }, actorId, size, offset);
        if (!authorized[0]) throw new ForbiddenAdminUserAdministrationException();
        return new ManagedAdminUserPage(users, page, size, total[0]);
    }

    @Override
    @Transactional
    public ManagedAdminUser updateEmailAsGeneralAdmin(UUID actorId, UUID userId, String email, Instant now) {
        try {
            List<UserOutcome> outcomes = jdbc.query(
                "with " + GUARDED_ACTOR + ", target as materialized "
                    + "(select id from admin_user where id=?), updated as (update admin_user u set email=?,updated_at=? "
                    + "from actor a where u.id=? and a.authorized and u.email<>? returning " + COLUMNS + "), unchanged as ("
                    + "select " + COLUMNS + " from admin_user u cross join actor a where u.id=? and a.authorized and u.email=?) "
                    + "select a.authorized,exists(select 1 from target) target_exists," + COLUMNS
                    + " from actor a left join (select * from updated union all select * from unchanged) u on true",
                outcomeMapper(), actorId, userId, email, Timestamp.from(now), userId, email, userId, email);
            return requireUser(outcomes.get(0));
        } catch (DuplicateKeyException duplicate) {
            throw new AdminUserConflictException("admin user email already exists");
        }
    }

    @Override
    @Transactional
    public void setActiveAsGeneralAdmin(UUID actorId, UUID userId, boolean active, Instant now) {
        String desired = active ? "ACTIVE" : "DISABLED";
        String current = active ? "DISABLED" : "ACTIVE";
        List<WriteOutcome> outcomes = jdbc.query(
            "with " + GUARDED_ACTOR + ", target as materialized "
                + "(select id,is_general_admin,status from admin_user where id=? for update), decision as materialized ("
                + "select a.authorized,exists(select 1 from target) target_exists,"
                + "(?=false and ?=(select id from target)) self_block,"
                + "(?=true and (select status from target)='PASSWORD_RESET_REQUIRED') invalid_transition from actor a), updated as ("
                + "update admin_user u set status=?,updated_at=? from decision d where u.id=? and d.authorized "
                + "and not d.self_block and not d.invalid_transition and u.status=? returning u.id) "
                + "select authorized,target_exists,self_block,invalid_transition from decision",
            (rs, row) -> new WriteOutcome(rs.getBoolean("authorized"), rs.getBoolean("target_exists"),
                rs.getBoolean("self_block"), rs.getBoolean("invalid_transition")), actorId, userId, active, actorId,
            active, desired, Timestamp.from(now), userId, current);
        classify(outcomes.get(0));
    }

    @Override
    public void resetPasswordAsGeneralAdmin(final UUID actorId, final UUID userId,
                                            final String passwordHash, final Instant now) {
        List<UUID> authorizedActors = jdbc.query(
            "select id from admin_user where is_general_admin and status='ACTIVE' "
                + "and (locked_until is null or locked_until<=current_timestamp) order by id for update",
            (rs, row) -> rs.getObject("id", UUID.class));
        if (!authorizedActors.contains(actorId)) throw new ForbiddenAdminUserAdministrationException();
        List<UUID> targets = jdbc.query("select id from admin_user where id=? for update",
            new Object[]{userId}, (rs, row) -> rs.getObject("id", UUID.class));
        if (targets.isEmpty()) throw new AdminUserNotFoundException();
        if (actorId.equals(userId)) {
            throw new AdminUserConflictException("general admin cannot block own access");
        }
        jdbc.update("update admin_user set password_hash=?,status='PASSWORD_RESET_REQUIRED',"
                + "failed_login_count=0,locked_until=null,updated_at=? where id=?",
            passwordHash, Timestamp.from(now), userId);
        afterPasswordHashChanged();
    }

    /** Test seam proving rollback between credential mutation and session revocation. */
    protected void afterPasswordHashChanged() { }


    private static RowMapper<UserOutcome> outcomeMapper() {
        return (rs, row) -> new UserOutcome(rs.getBoolean("authorized"), rs.getBoolean("target_exists"),
            rs.getObject("id") == null ? null : MAPPER.mapRow(rs, row));
    }

    private static ManagedAdminUser requireUser(UserOutcome outcome) {
        if (!outcome.authorized) throw new ForbiddenAdminUserAdministrationException();
        if (!outcome.targetExists || outcome.user == null) throw new AdminUserNotFoundException();
        return outcome.user;
    }

    private static void classify(WriteOutcome outcome) {
        if (!outcome.authorized) throw new ForbiddenAdminUserAdministrationException();
        if (!outcome.targetExists) throw new AdminUserNotFoundException();
        if (outcome.selfBlock) throw new AdminUserConflictException("general admin cannot block own access");
        if (outcome.invalidTransition) throw new AdminUserConflictException("password reset must be completed before activation");
    }

    private static final class UserOutcome {
        final boolean authorized;
        final boolean targetExists;
        final ManagedAdminUser user;
        UserOutcome(boolean authorized, boolean targetExists, ManagedAdminUser user) {
            this.authorized = authorized; this.targetExists = targetExists; this.user = user;
        }
    }
    private static final class WriteOutcome {
        final boolean authorized;
        final boolean targetExists;
        final boolean selfBlock;
        final boolean invalidTransition;
        WriteOutcome(boolean authorized, boolean targetExists, boolean selfBlock) {
            this(authorized, targetExists, selfBlock, false);
        }
        WriteOutcome(boolean authorized, boolean targetExists, boolean selfBlock, boolean invalidTransition) {
            this.authorized = authorized; this.targetExists = targetExists; this.selfBlock = selfBlock;
            this.invalidTransition = invalidTransition;
        }
    }
}
