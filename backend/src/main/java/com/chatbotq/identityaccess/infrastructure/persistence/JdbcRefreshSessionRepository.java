package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.domain.RefreshSession;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcRefreshSessionRepository implements RefreshSessionRepository {
    private static final String INSERT_SQL = "insert into admin_refresh_session "
        + "(id,user_id,family_id,token_hash,issued_at,expires_at,rotated_at,revoked_at,replaced_by_id) "
        + "values (?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;

    public JdbcRefreshSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(RefreshSession session) {
        insert(session);
    }

    @Override
    public Optional<RefreshSession> findByTokenHash(String tokenHash) {
        List<RefreshSession> found = jdbc.query(
            "select id,user_id,family_id,token_hash,issued_at,expires_at,rotated_at,revoked_at,replaced_by_id "
                + "from admin_refresh_session where token_hash=?",
            new Object[]{tokenHash}, (rs, row) -> RefreshSession.restore(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("family_id", UUID.class), rs.getString("token_hash"),
                rs.getTimestamp("issued_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                toInstant(rs.getTimestamp("rotated_at")), toInstant(rs.getTimestamp("revoked_at")),
                rs.getObject("replaced_by_id", UUID.class)));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Optional<RefreshSession> findByTokenHashForUpdate(String tokenHash) {
        List<RefreshSession> found = jdbc.query(
            "select id,user_id,family_id,token_hash,issued_at,expires_at,rotated_at,revoked_at,replaced_by_id "
                + "from admin_refresh_session where token_hash=? for update",
            new Object[]{tokenHash}, (rs, row) -> RefreshSession.restore(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("family_id", UUID.class), rs.getString("token_hash"),
                rs.getTimestamp("issued_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                toInstant(rs.getTimestamp("rotated_at")), toInstant(rs.getTimestamp("revoked_at")),
                rs.getObject("replaced_by_id", UUID.class)));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Optional<RefreshSession> findByTokenHashAndLockFamily(String tokenHash) {
        List<UUID> families = jdbc.query("select family_id from admin_refresh_session where token_hash=?",
            new Object[]{tokenHash}, (rs, row) -> rs.getObject("family_id", UUID.class));
        if (families.isEmpty()) return Optional.empty();
        List<RefreshSession> family = jdbc.query(
            "select id,user_id,family_id,token_hash,issued_at,expires_at,rotated_at,revoked_at,replaced_by_id "
                + "from admin_refresh_session where family_id=? order by id for update",
            new Object[]{families.get(0)}, (rs, row) -> RefreshSession.restore(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("family_id", UUID.class), rs.getString("token_hash"),
                rs.getTimestamp("issued_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                toInstant(rs.getTimestamp("rotated_at")), toInstant(rs.getTimestamp("revoked_at")),
                rs.getObject("replaced_by_id", UUID.class)));
        for (RefreshSession session : family) {
            if (tokenHash.equals(session.getTokenHash())) return Optional.of(session);
        }
        return Optional.empty();
    }

    @Override
    public Optional<UUID> findUserIdByTokenHash(String tokenHash) {
        List<UUID> found = jdbc.query("select user_id from admin_refresh_session where token_hash=?",
            new Object[]{tokenHash}, (rs, row) -> rs.getObject("user_id", UUID.class));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public boolean replaceIfUsable(RefreshSession current, RefreshSession replacement, Instant now) {
        insert(replacement);
        int updated = jdbc.update(
            "update admin_refresh_session set rotated_at=?, replaced_by_id=? "
                + "where id=? and rotated_at is null and revoked_at is null and expires_at>?",
            Timestamp.from(now), replacement.getId(), current.getId(), Timestamp.from(now));
        if (updated == 1) return true;
        jdbc.update("delete from admin_refresh_session where id=?", replacement.getId());
        return false;
    }

    @Override
    public void revokeFamilyOrdered(UUID familyId, Instant now) {
        lockIds("family_id", familyId);
        jdbc.update("update admin_refresh_session set revoked_at=coalesce(revoked_at, ?) where family_id=?",
            Timestamp.from(now), familyId);
    }

    @Override
    public void revokeAllByUserOrdered(UUID userId, Instant now) {
        lockIds("user_id", userId);
        jdbc.update("update admin_refresh_session set revoked_at=coalesce(revoked_at, ?) where user_id=?",
            Timestamp.from(now), userId);
    }

    private void lockIds(String column, UUID value) {
        jdbc.query("select id from admin_refresh_session where " + column + "=? order by id for update",
            new Object[]{value}, (rs, row) -> rs.getObject("id", UUID.class));
    }

    private void insert(RefreshSession session) {
        jdbc.update(INSERT_SQL,
            session.getId(), session.getUserId(), session.getFamilyId(), session.getTokenHash(),
            Timestamp.from(session.getIssuedAt()), Timestamp.from(session.getExpiresAt()),
            toTimestamp(session.getRotatedAt()), toTimestamp(session.getRevokedAt()), session.getReplacedById());
    }
    private static Instant toInstant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp toTimestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
