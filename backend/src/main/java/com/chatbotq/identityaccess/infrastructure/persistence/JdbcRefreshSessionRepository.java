package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.domain.RefreshSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactions;

    public JdbcRefreshSessionRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactions.getTransactionManager());
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
    public boolean replaceIfUsable(RefreshSession current, RefreshSession replacement, Instant now) {
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                insert(replacement);
                int updated = jdbc.update(
                    "update admin_refresh_session set rotated_at=?, replaced_by_id=? "
                        + "where id=? and rotated_at is null and revoked_at is null and expires_at>?",
                    Timestamp.from(now), replacement.getId(), current.getId(), Timestamp.from(now));
                if (updated != 1) {
                    status.setRollbackOnly();
                }
                return updated == 1;
            }));
        } catch (DataIntegrityViolationException rejected) {
            return false;
        }
    }

    @Override
    public void revokeFamily(UUID familyId, Instant now) {
        transactions.execute(status -> {
            jdbc.update("update admin_refresh_session set revoked_at=coalesce(revoked_at, ?) where family_id=?",
                Timestamp.from(now), familyId);
            return null;
        });
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
