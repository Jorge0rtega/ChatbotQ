package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.AdminUserStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcAdminUserRepository implements AdminUserRepository {
    private static final String SELECT_COLUMNS = "select id, email, password_hash, status, "
        + "is_general_admin, failed_login_count, locked_until, created_at, updated_at "
        + "from admin_user ";

    private final JdbcTemplate jdbc;

    public JdbcAdminUserRepository(JdbcTemplate jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc must not be null");
        }
        this.jdbc = jdbc;
    }

    @Override
    public boolean existsByEmail(String normalizedEmail) {
        if (normalizedEmail == null || normalizedEmail.trim().isEmpty()) {
            throw new IllegalArgumentException("normalizedEmail must not be blank");
        }
        Integer count = jdbc.queryForObject(
            "select count(*) from admin_user where lower(email) = lower(?)",
            Integer.class, normalizedEmail.trim());
        return count != null && count > 0;
    }

    @Override
    public Optional<AdminUser> findById(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        List<AdminUser> users = queryUsers("where id = ?", id);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }

    private List<AdminUser> queryUsers(String where, Object value) {
        return jdbc.query(SELECT_COLUMNS + where,
            new Object[]{value}, (result, rowNumber) -> AdminUser.restore(
                result.getObject("id", UUID.class),
                result.getString("email"),
                result.getString("password_hash"),
                result.getBoolean("is_general_admin"),
                AdminUserStatus.valueOf(result.getString("status")),
                result.getInt("failed_login_count"),
                toInstant(result.getTimestamp("locked_until")),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant()));
    }

    @Override
    public Optional<AdminUser> findByEmail(String normalizedEmail) {
        if (normalizedEmail == null || normalizedEmail.trim().isEmpty()) {
            return Optional.empty();
        }
        List<AdminUser> users = queryUsers("where lower(email) = lower(?)", normalizedEmail.trim());
        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }

    @Override
    public Optional<AdminUser> findByIdForUpdate(UUID id) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        List<AdminUser> users = queryUsers("where id = ? for update", id);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }

    @Override
    public Optional<AdminUser> findByEmailForUpdate(String normalizedEmail) {
        if (normalizedEmail == null || normalizedEmail.trim().isEmpty()) return Optional.empty();
        List<AdminUser> users = queryUsers("where lower(email) = lower(?) for update", normalizedEmail.trim());
        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }

    @Override
    public void completePasswordReset(UUID id, String passwordHash, java.time.Instant now) {
        int updated = jdbc.update("update admin_user set password_hash=?,status='ACTIVE',failed_login_count=0,"
                + "locked_until=null,updated_at=? where id=? and status='PASSWORD_RESET_REQUIRED'",
            passwordHash, Timestamp.from(now), id);
        if (updated != 1) throw new IllegalStateException("password reset state changed while row was locked");
    }

    @Override
    public AdminUser save(AdminUser user) {
        if (user == null) {
            throw new IllegalArgumentException("user must not be null");
        }
        jdbc.update("insert into admin_user (id, email, password_hash, status, "
                + "is_general_admin, failed_login_count, locked_until, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            user.getId(), user.getEmail(), user.getPasswordHash(), user.getStatus().name(),
            user.isGeneralAdmin(), user.getFailedLoginCount(),
            toTimestamp(user.getLockedUntil()), Timestamp.from(user.getCreatedAt()),
            Timestamp.from(user.getUpdatedAt()));
        return user;
    }

    private static java.time.Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp toTimestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
