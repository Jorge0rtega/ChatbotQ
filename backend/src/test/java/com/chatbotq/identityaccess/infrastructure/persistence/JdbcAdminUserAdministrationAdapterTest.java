package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.model.ManagedAdminUser;
import com.chatbotq.identityaccess.application.model.ManagedAdminUserPage;
import com.chatbotq.identityaccess.application.usecase.AdminUserConflictException;
import com.chatbotq.identityaccess.application.usecase.AdminUserNotFoundException;
import com.chatbotq.identityaccess.application.usecase.ForbiddenAdminUserAdministrationException;
import com.chatbotq.identityaccess.application.usecase.AdministerAdminUsersUseCase;
import com.chatbotq.identityaccess.application.usecase.CompleteAdminPasswordResetUseCase;
import com.chatbotq.identityaccess.application.usecase.LoginAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.LogoutAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.RefreshAdminSessionUseCase;
import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.infrastructure.transaction.SpringApplicationTransaction;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAdminUserAdministrationAdapterTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static ApplicationTransaction transactions;
    private JdbcAdminUserAdministrationAdapter adapter;

    @BeforeAll static void database() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new SpringApplicationTransaction(
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }
    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }

    @BeforeEach void clean() {
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from user_project_role");
        jdbc.update("delete from admin_user");
        adapter = new JdbcAdminUserAdministrationAdapter(jdbc);
    }

    @Test
    void transactionParticipantsHaveNoFallbackConstructorAndAdaptersOwnNoTransaction() {
        assertEquals(1, JdbcAdminUserAdministrationAdapter.class.getConstructors().length);
        assertEquals(1, JdbcRefreshSessionRepository.class.getConstructors().length);
        assertEquals(1, LoginAdminUseCase.class.getConstructors().length);
        assertEquals(1, RefreshAdminSessionUseCase.class.getConstructors().length);
        Stream.of(JdbcAdminUserAdministrationAdapter.class, JdbcRefreshSessionRepository.class)
            .flatMap(type -> Stream.of(type.getConstructors()))
            .flatMap(constructor -> Stream.of(constructor.getParameterTypes()))
            .forEach(parameter -> assertTrue(
                !parameter.getName().contains("Transaction"),
                "adapter constructor must not accept a transaction primitive: " + parameter.getName()));
        Stream.of(LoginAdminUseCase.class, RefreshAdminSessionUseCase.class, LogoutAdminUseCase.class,
                CompleteAdminPasswordResetUseCase.class, AdministerAdminUsersUseCase.class)
            .flatMap(type -> Stream.of(type.getConstructors()))
            .forEach(constructor -> assertTrue(
                Stream.of(constructor.getParameterTypes()).anyMatch(ApplicationTransaction.class::equals),
                "use case constructor must require ApplicationTransaction: " + constructor));
    }

    @Test
    void createRequiresCurrentAvailableGeneralAndClassifiesDuplicateOnlyForAuthorizedActor() {
        UUID general = user("general@example.com", true, "ACTIVE");
        UUID projectAdmin = user("project@example.com", false, "ACTIVE");
        Instant now = Instant.parse("2026-08-26T12:00:00Z");
        UUID createdId = UUID.randomUUID();

        ManagedAdminUser created = adapter.createAsGeneralAdmin(general, createdId,
            "new@example.com", "secret-hash", false, now);
        assertEquals("PASSWORD_RESET_REQUIRED", created.getStatus());
        assertEquals("PROJECT_ADMIN", created.getRole());
        assertEquals("secret-hash", jdbc.queryForObject(
            "select password_hash from admin_user where id=?", String.class, createdId));
        assertThrows(AdminUserConflictException.class, () -> adapter.createAsGeneralAdmin(general,
            UUID.randomUUID(), "NEW@example.com", "other", false, now));
        assertThrows(ForbiddenAdminUserAdministrationException.class, () -> adapter.createAsGeneralAdmin(
            projectAdmin, UUID.randomUUID(), "new@example.com", "other", false, now));
        jdbc.update("update admin_user set status='DISABLED' where id=?", general);
        assertThrows(ForbiddenAdminUserAdministrationException.class, () -> adapter.createAsGeneralAdmin(
            general, UUID.randomUUID(), "another@example.com", "other", false, now));
    }

    @Test
    void getAndListAreGeneralOnlySingleSnapshotAndStable() {
        Instant same = Instant.parse("2026-08-26T10:00:00Z");
        UUID general = user("general@example.com", true, "ACTIVE", same);
        UUID projectAdmin = user("z@example.com", false, "ACTIVE", same);
        UUID first = user("a@example.com", false, "ACTIVE", same);
        AtomicInteger statements = new AtomicInteger();
        JdbcAdminUserAdministrationAdapter counted = new JdbcAdminUserAdministrationAdapter(counting(statements));

        assertEquals(first, counted.findByIdAsGeneralAdmin(general, first).getId());
        assertEquals(1, statements.getAndSet(0));
        assertThrows(AdminUserNotFoundException.class,
            () -> counted.findByIdAsGeneralAdmin(general, UUID.randomUUID()));
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ForbiddenAdminUserAdministrationException.class,
            () -> counted.findByIdAsGeneralAdmin(projectAdmin, UUID.randomUUID()));
        assertEquals(1, statements.getAndSet(0));

        ManagedAdminUserPage page = counted.listAsGeneralAdmin(general, 0, 2, 0);
        assertEquals(3, page.getTotalElements());
        assertEquals(2, page.getItems().size());
        java.util.List<UUID> expected = jdbc.query("select id from admin_user order by created_at asc,id asc limit 2",
            (rs, row) -> (UUID) rs.getObject("id"));
        assertEquals(expected.get(0), page.getItems().get(0).getId());
        assertEquals(expected.get(1), page.getItems().get(1).getId());
        assertEquals(1, statements.get());
    }

    @Test
    void updateEmailIsCaseInsensitiveUniqueAndDoesNotChangeOtherFields() {
        UUID general = user("general@example.com", true, "ACTIVE");
        UUID target = user("target@example.com", false, "DISABLED");
        Instant changed = Instant.parse("2026-08-26T12:00:00Z");

        ManagedAdminUser updated = adapter.updateEmailAsGeneralAdmin(general, target,
            "changed@example.com", changed);
        assertEquals("changed@example.com", updated.getEmail());
        assertEquals("DISABLED", updated.getStatus());
        assertEquals(changed, updated.getUpdatedAt());
        assertThrows(AdminUserConflictException.class,
            () -> adapter.updateEmailAsGeneralAdmin(general, target, "GENERAL@example.com", changed));
    }

    @Test
    void activationIsIdempotentAndCannotDisableSelfOrLastAvailableGeneral() {
        UUID general = user("general@example.com", true, "ACTIVE");
        UUID target = user("target@example.com", false, "ACTIVE");
        Instant original = updatedAt(target);

        adapter.setActiveAsGeneralAdmin(general, target, true, original.plusSeconds(1));
        assertEquals(original, updatedAt(target));
        adapter.setActiveAsGeneralAdmin(general, target, false, original.plusSeconds(2));
        assertEquals(original.plusSeconds(2), updatedAt(target));
        adapter.setActiveAsGeneralAdmin(general, target, false, original.plusSeconds(3));
        assertEquals(original.plusSeconds(2), updatedAt(target));
        assertThrows(AdminUserConflictException.class,
            () -> adapter.setActiveAsGeneralAdmin(general, general, false, original.plusSeconds(4)));

        UUID resetRequired = user("reset@example.com", false, "PASSWORD_RESET_REQUIRED");
        Instant resetTimestamp = updatedAt(resetRequired);
        assertThrows(AdminUserConflictException.class,
            () -> adapter.setActiveAsGeneralAdmin(general, resetRequired, true, resetTimestamp.plusSeconds(1)));
        adapter.setActiveAsGeneralAdmin(general, resetRequired, false, resetTimestamp.plusSeconds(2));
        assertEquals("PASSWORD_RESET_REQUIRED", status(resetRequired));
        assertEquals(resetTimestamp, updatedAt(resetRequired));

        UUID secondGeneral = user("second@example.com", true, "ACTIVE");
        adapter.setActiveAsGeneralAdmin(general, secondGeneral, false, original.plusSeconds(5));
        assertEquals("DISABLED", status(secondGeneral));
    }

    @Test
    void concurrentGeneralDeactivationCannotLeaveSystemWithoutAnActiveGeneral() throws Exception {
        UUID first = user("first@example.com", true, "ACTIVE");
        UUID second = user("second@example.com", true, "ACTIVE");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<Boolean> one = pool.submit(() -> {
            start.await();
            try { adapter.setActiveAsGeneralAdmin(first, second, false, Instant.now()); return true; }
            catch (ForbiddenAdminUserAdministrationException | AdminUserConflictException rejected) { return false; }
        });
        java.util.concurrent.Future<Boolean> two = pool.submit(() -> {
            start.await();
            try { adapter.setActiveAsGeneralAdmin(second, first, false, Instant.now()); return true; }
            catch (ForbiddenAdminUserAdministrationException | AdminUserConflictException rejected) { return false; }
        });
        start.countDown();
        int successes = (one.get() ? 1 : 0) + (two.get() ? 1 : 0);
        pool.shutdownNow();

        assertEquals(1, successes);
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from admin_user where is_general_admin and status='ACTIVE'", Integer.class));
    }

    @Test
    void resetChangesHashEveryTimeSetsResetRequiredClearsLockAndRevokesSessions() {
        UUID general = user("general@example.com", true, "ACTIVE");
        UUID target = user("target@example.com", false, "ACTIVE");
        jdbc.update("update admin_user set password_hash='old',failed_login_count=4,locked_until=now()+interval '1 hour' where id=?", target);
        Instant changed = Instant.parse("2026-08-26T12:00:00Z");
        UUID session = UUID.randomUUID();
        jdbc.update("insert into admin_refresh_session(id,user_id,family_id,token_hash,issued_at,expires_at) values (?,?,?,?,?,?)",
            session, target, UUID.randomUUID(), repeat('a', 64), Timestamp.from(changed.minusSeconds(60)),
            Timestamp.from(changed.plusSeconds(86400)));

        reset(adapter, general, target, "new-hash-1", changed);
        assertEquals("new-hash-1", jdbc.queryForObject("select password_hash from admin_user where id=?", String.class, target));
        assertEquals("PASSWORD_RESET_REQUIRED", status(target));
        assertEquals(0, jdbc.queryForObject("select failed_login_count from admin_user where id=?", Integer.class, target));
        assertEquals(changed, jdbc.queryForObject("select revoked_at from admin_refresh_session where id=?", Timestamp.class, session).toInstant());
        reset(adapter, general, target, "new-hash-2", changed.plusSeconds(10));
        assertEquals("new-hash-2", jdbc.queryForObject("select password_hash from admin_user where id=?", String.class, target));
        assertEquals(changed.plusSeconds(10), updatedAt(target));
        assertEquals(changed, jdbc.queryForObject("select revoked_at from admin_refresh_session where id=?", Timestamp.class, session).toInstant());
    }

    @Test
    void failureAfterHashMutationRollsBackCredentialAndSessionChangesTogether() {
        UUID general = user("general@example.com", true, "ACTIVE");
        UUID target = user("target@example.com", false, "ACTIVE");
        Instant now = Instant.parse("2026-08-26T12:00:00Z");
        UUID session = UUID.randomUUID();
        jdbc.update("insert into admin_refresh_session(id,user_id,family_id,token_hash,issued_at,expires_at) values (?,?,?,?,?,?)",
            session, target, UUID.randomUUID(), repeat('b', 64), Timestamp.from(now.minusSeconds(1)),
            Timestamp.from(now.plusSeconds(60)));
        JdbcAdminUserAdministrationAdapter failing = new JdbcAdminUserAdministrationAdapter(jdbc) {
            @Override protected void afterPasswordHashChanged() { throw new IllegalStateException("forced rollback"); }
        };

        assertThrows(IllegalStateException.class,
            () -> reset(failing, general, target, "never-committed", now));

        assertEquals("hash", jdbc.queryForObject("select password_hash from admin_user where id=?", String.class, target));
        assertEquals("ACTIVE", status(target));
        assertEquals(null, jdbc.queryForObject("select revoked_at from admin_refresh_session where id=?", Timestamp.class, session));
    }

    private UUID user(String email, boolean general, String status) { return user(email, general, status, Instant.now()); }
    private void reset(JdbcAdminUserAdministrationAdapter targetAdapter, UUID actorId, UUID userId,
                       String hash, Instant now) {
        transactions.execute(() -> {
            targetAdapter.resetPasswordAsGeneralAdmin(actorId, userId, hash, now);
            new JdbcRefreshSessionRepository(jdbc).revokeAllByUserOrdered(userId, now);
            return null;
        });
    }
    private UUID user(String email, boolean general, String status, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_user(id,email,password_hash,status,is_general_admin,created_at,updated_at) values (?,?,?,?,?,?,?)",
            id, email, "hash", status, general, Timestamp.from(at), Timestamp.from(at));
        return id;
    }
    private String status(UUID id) { return jdbc.queryForObject("select status from admin_user where id=?", String.class, id); }
    private Instant updatedAt(UUID id) { return jdbc.queryForObject("select updated_at from admin_user where id=?", Timestamp.class, id).toInstant(); }
    private JdbcTemplate counting(final AtomicInteger count) {
        return new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> java.util.List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                count.incrementAndGet(); return super.query(sql, mapper, args);
            }
            @Override public void query(String sql, org.springframework.jdbc.core.RowCallbackHandler handler, Object... args) {
                count.incrementAndGet(); super.query(sql, handler, args);
            }
        };
    }
    private static String repeat(char c, int n) { StringBuilder b = new StringBuilder(); while (n-- > 0) b.append(c); return b.toString(); }
}
