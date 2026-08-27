package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.model.AuthenticationTokens;
import com.chatbotq.identityaccess.application.port.AccessTokenIssuer;
import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.port.PasswordVerifier;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.application.port.RefreshTokenManager;
import com.chatbotq.identityaccess.application.usecase.AdminUserConflictException;
import com.chatbotq.identityaccess.application.usecase.CompleteAdminPasswordResetUseCase;
import com.chatbotq.identityaccess.application.usecase.ForbiddenAdminUserAdministrationException;
import com.chatbotq.identityaccess.application.usecase.InvalidAuthenticationException;
import com.chatbotq.identityaccess.application.usecase.LoginAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.LogoutAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.RefreshAdminSessionUseCase;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.RefreshSession;
import com.chatbotq.infrastructure.transaction.SpringApplicationTransaction;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;


import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgreSQL row-lock races. No timing sleeps: every ordering is established by latches at lock seams. */
class AdminCredentialSerializationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private JdbcAdminUserRepository users;
    private JdbcRefreshSessionRepository sessions;
    private ApplicationTransaction transactions;
    private UUID actor;
    private UUID target;
    private ExecutorService pool;

    @BeforeAll static void database() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
            postgres.getPassword()));
        jdbc.execute("alter role " + postgres.getUsername() + " set lock_timeout='3s'");
    }

    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }
    @AfterEach void stopPool() { if (pool != null) pool.shutdownNow(); }

    @BeforeEach void setUp() {
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from user_project_role");
        jdbc.update("delete from admin_user");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(jdbc.getDataSource());
        transactions = new SpringApplicationTransaction(new TransactionTemplate(manager));
        users = new JdbcAdminUserRepository(jdbc);
        sessions = new JdbcRefreshSessionRepository(jdbc);
        actor = user("general@example.com", true, "ACTIVE", "hash:actor");
        target = user("target@example.com", false, "ACTIVE", "hash:correct");
        pool = Executors.newFixedThreadPool(2);
    }

    @Test
    void loginWinnerCommitsSessionThenResetRevokesIt() throws Exception {
        CountDownLatch credentialLocked = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        AdminUserRepository hooked = new HookedUsers(users) {
            @Override public Optional<AdminUser> findByEmailForUpdate(String email) {
                Optional<AdminUser> result = super.findByEmailForUpdate(email);
                credentialLocked.countDown(); await(releaseLogin); return result;
            }
        };
        CountDownLatch resetAttempted = new CountDownLatch(1);
        JdbcAdminUserAdministrationAdapter reset = new JdbcAdminUserAdministrationAdapter(jdbc) {
            @Override public void resetPasswordAsGeneralAdmin(UUID a, UUID u, String h, Instant n) {
                resetAttempted.countDown(); super.resetPasswordAsGeneralAdmin(a, u, h, n);
            }
        };
        LoginAdminUseCase login = login(hooked);
        Future<AuthenticationTokens> loginResult = pool.submit(() -> login.execute("target@example.com", "correct"));
        assertTrue(credentialLocked.await(10, TimeUnit.SECONDS));
        Future<?> resetResult = pool.submit(() -> reset(reset, actor, target, "hash:temp"));
        assertTrue(resetAttempted.await(10, TimeUnit.SECONDS));
        releaseLogin.countDown();

        assertNotNull(loginResult.get(10, TimeUnit.SECONDS));
        resetResult.get(10, TimeUnit.SECONDS);
        assertEquals(1, count("select count(*) from admin_refresh_session where user_id=?", target));
        assertEquals(1, count("select count(*) from admin_refresh_session where user_id=? and revoked_at is not null", target));
    }

    @Test
    void resetWinnerMakesQueuedLoginFailWithoutCreatingSession() throws Exception {
        CountDownLatch hashChanged = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        JdbcAdminUserAdministrationAdapter reset = pausingReset(hashChanged, releaseReset);
        CountDownLatch loginAttempted = new CountDownLatch(1);
        AdminUserRepository hooked = new HookedUsers(users) {
            @Override public Optional<AdminUser> findByEmailForUpdate(String email) {
                loginAttempted.countDown(); return super.findByEmailForUpdate(email);
            }
        };
        Future<?> resetResult = pool.submit(() -> reset(reset, actor, target, "hash:temp"));
        assertTrue(hashChanged.await(10, TimeUnit.SECONDS));
        Future<AuthenticationTokens> loginResult = pool.submit(() -> login(hooked).execute("target@example.com", "correct"));
        assertTrue(loginAttempted.await(10, TimeUnit.SECONDS));
        releaseReset.countDown();

        resetResult.get(10, TimeUnit.SECONDS);
        assertFutureInvalid(loginResult);
        assertEquals(0, count("select count(*) from admin_refresh_session where user_id=?", target));
    }

    @Test
    void refreshWinnerRotatesThenResetRevokesOldAndReplacement() throws Exception {
        String current = login(users).execute("target@example.com", "correct").getRefreshToken();
        CountDownLatch credentialLocked = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        AdminUserRepository hooked = new HookedUsers(users) {
            @Override public Optional<AdminUser> findByIdForUpdate(UUID id) {
                Optional<AdminUser> result = super.findByIdForUpdate(id);
                credentialLocked.countDown(); await(releaseRefresh); return result;
            }
        };
        CountDownLatch resetAttempted = new CountDownLatch(1);
        JdbcAdminUserAdministrationAdapter reset = new JdbcAdminUserAdministrationAdapter(jdbc) {
            @Override public void resetPasswordAsGeneralAdmin(UUID a, UUID u, String h, Instant n) {
                resetAttempted.countDown(); super.resetPasswordAsGeneralAdmin(a, u, h, n);
            }
        };
        Future<AuthenticationTokens> refreshResult = pool.submit(() -> refresh(hooked).execute(current));
        assertTrue(credentialLocked.await(10, TimeUnit.SECONDS));
        Future<?> resetResult = pool.submit(() -> reset(reset, actor, target, "hash:temp"));
        assertTrue(resetAttempted.await(10, TimeUnit.SECONDS));
        releaseRefresh.countDown();

        assertNotNull(refreshResult.get(10, TimeUnit.SECONDS));
        resetResult.get(10, TimeUnit.SECONDS);
        assertEquals(2, count("select count(*) from admin_refresh_session where user_id=?", target));
        assertEquals(2, count("select count(*) from admin_refresh_session where user_id=? and revoked_at is not null", target));
    }

    @Test
    void resetWinnerMakesQueuedRefreshFailWithoutReplacement() throws Exception {
        String current = login(users).execute("target@example.com", "correct").getRefreshToken();
        CountDownLatch hashChanged = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        JdbcAdminUserAdministrationAdapter reset = pausingReset(hashChanged, releaseReset);
        CountDownLatch refreshAttempted = new CountDownLatch(1);
        AdminUserRepository hooked = new HookedUsers(users) {
            @Override public Optional<AdminUser> findByIdForUpdate(UUID id) {
                refreshAttempted.countDown(); return super.findByIdForUpdate(id);
            }
        };
        Future<?> resetResult = pool.submit(() -> reset(reset, actor, target, "hash:temp"));
        assertTrue(hashChanged.await(10, TimeUnit.SECONDS));
        Future<AuthenticationTokens> refreshResult = pool.submit(() -> refresh(hooked).execute(current));
        assertTrue(refreshAttempted.await(10, TimeUnit.SECONDS));
        releaseReset.countDown();

        resetResult.get(10, TimeUnit.SECONDS);
        assertFutureInvalid(refreshResult);
        assertEquals(1, count("select count(*) from admin_refresh_session where user_id=?", target));
        assertEquals(1, count("select count(*) from admin_refresh_session where user_id=? and revoked_at is not null", target));
    }

    @Test
    void completeResetWinningRaceSerializesRefreshAndLeavesNoUsableSession() throws Exception {
        assertCompleteResetSerializes(false);
    }

    @Test
    void completeResetWinningRaceSerializesLogoutAndLeavesNoUsableSession() throws Exception {
        assertCompleteResetSerializes(true);
    }

    @Test
    void administrativeResetWinningRaceSerializesLogoutWithoutDeadlock() throws Exception {
        String currentHash = repeat('5', 64);
        insertSession(UUID.randomUUID(), UUID.randomUUID(), currentHash, null, null);
        CountDownLatch hashChanged = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        JdbcAdminUserAdministrationAdapter reset = pausingReset(hashChanged, releaseReset);
        CountDownLatch logoutAttempted = new CountDownLatch(1);
        AdminUserRepository loggingOutUsers = new HookedUsers(users) {
            @Override public Optional<AdminUser> findByIdForUpdate(UUID id) {
                logoutAttempted.countDown(); return super.findByIdForUpdate(id);
            }
        };
        LogoutAdminUseCase logout = new LogoutAdminUseCase(identityTokens(), loggingOutUsers, sessions, transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));

        Future<?> resetResult = pool.submit(() -> reset(reset, actor, target, "hash:temp"));
        assertTrue(hashChanged.await(10, TimeUnit.SECONDS));
        Future<?> logoutResult = pool.submit(() -> logout.execute(currentHash));
        assertTrue(logoutAttempted.await(10, TimeUnit.SECONDS));
        releaseReset.countDown();

        resetResult.get(10, TimeUnit.SECONDS);
        logoutResult.get(10, TimeUnit.SECONDS);
        assertEquals("PASSWORD_RESET_REQUIRED", jdbc.queryForObject(
            "select status from admin_user where id=?", String.class, target));
        assertEquals(1, count("select count(*) from admin_refresh_session where user_id=? and revoked_at is not null",
            target));
    }

    @Test
    void logoutOfReplacementCannotDeadlockWithReuseOfRotatedTokenAndFinallyRevokesFamily() throws Exception {
        UUID family = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID replacementId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID rotatedId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        String replacementHash = repeat('7', 64);
        String rotatedHash = repeat('8', 64);
        insertSession(replacementId, family, replacementHash, null, null);
        insertSession(rotatedId, family, rotatedHash, NOW.minusSeconds(1), replacementId);

        CountDownLatch rotatedLocked = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        RefreshSessionRepository hookedSessions = new HookedSessions(sessions) {
            @Override public Optional<RefreshSession> findByTokenHashAndLockFamily(String hash) {
                Optional<RefreshSession> result = super.findByTokenHashAndLockFamily(hash);
                if (rotatedHash.equals(hash)) {
                    rotatedLocked.countDown();
                    await(releaseRefresh);
                }
                return result;
            }
        };
        RefreshTokenManager identityTokens = new RefreshTokenManager() {
            public String generate() { return repeat('9', 64); }
            public String hash(String token) { return token; }
        };
        RefreshAdminSessionUseCase refresh = new RefreshAdminSessionUseCase(users, access(), identityTokens,
            hookedSessions, transactions, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7));
        CountDownLatch logoutAttempted = new CountDownLatch(1);
        AdminUserRepository loggingOutUsers = new HookedUsers(users) {
            @Override public Optional<AdminUser> findByIdForUpdate(UUID id) {
                logoutAttempted.countDown(); return super.findByIdForUpdate(id);
            }
        };
        LogoutAdminUseCase logout = new LogoutAdminUseCase(identityTokens, loggingOutUsers, hookedSessions, transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));

        Future<?> refreshResult = pool.submit(() -> refresh.execute(rotatedHash));
        assertTrue(rotatedLocked.await(10, TimeUnit.SECONDS));
        Future<?> logoutResult = pool.submit(() -> logout.execute(replacementHash));
        assertTrue(logoutAttempted.await(10, TimeUnit.SECONDS));
        releaseRefresh.countDown();

        assertFutureInvalid(refreshResult);
        logoutResult.get(10, TimeUnit.SECONDS);

        logout.execute(replacementHash);
        logout.execute(repeat('0', 64));
        assertEquals(2, count("select count(*) from admin_refresh_session where family_id=? and revoked_at is not null",
            family));
    }

    @Test
    void logoutQueuedBehindNormalRotationRevokesRotatedTokenAndItsReplacement() throws Exception {
        UUID family = UUID.fromString("00000000-0000-0000-0000-000000000020");
        String currentHash = repeat('6', 64);
        insertSession(UUID.fromString("00000000-0000-0000-0000-000000000021"), family,
            currentHash, null, null);
        CountDownLatch familyLocked = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        RefreshSessionRepository hookedSessions = new HookedSessions(sessions) {
            @Override public Optional<RefreshSession> findByTokenHashAndLockFamily(String hash) {
                Optional<RefreshSession> result = super.findByTokenHashAndLockFamily(hash);
                if (currentHash.equals(hash)) {
                    familyLocked.countDown();
                    await(releaseRefresh);
                }
                return result;
            }
        };
        RefreshTokenManager identityTokens = new RefreshTokenManager() {
            public String generate() { return repeat('5', 64); }
            public String hash(String token) { return token; }
        };
        RefreshAdminSessionUseCase refresh = new RefreshAdminSessionUseCase(users, access(), identityTokens,
            hookedSessions, transactions, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7));
        CountDownLatch logoutAttempted = new CountDownLatch(1);
        AdminUserRepository loggingOutUsers = new HookedUsers(users) {
            @Override public Optional<AdminUser> findByIdForUpdate(UUID id) {
                logoutAttempted.countDown(); return super.findByIdForUpdate(id);
            }
        };
        LogoutAdminUseCase logout = new LogoutAdminUseCase(identityTokens, loggingOutUsers, hookedSessions, transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));

        Future<AuthenticationTokens> refreshResult = pool.submit(() -> refresh.execute(currentHash));
        assertTrue(familyLocked.await(10, TimeUnit.SECONDS));
        Future<?> logoutResult = pool.submit(() -> logout.execute(currentHash));
        assertTrue(logoutAttempted.await(10, TimeUnit.SECONDS));
        releaseRefresh.countDown();

        assertNotNull(refreshResult.get(10, TimeUnit.SECONDS));
        logoutResult.get(10, TimeUnit.SECONDS);
        assertEquals(2, count("select count(*) from admin_refresh_session where family_id=?", family));
        assertEquals(2, count("select count(*) from admin_refresh_session where family_id=? and revoked_at is not null",
            family));
    }

    @Test
    void actorResetWinningRacePreventsCreateAfterFailFastHashingWindow() throws Exception {
        UUID secondGeneral = user("second@example.com", true, "ACTIVE", "hash:second");
        CountDownLatch hashChanged = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        JdbcAdminUserAdministrationAdapter reset = pausingReset(hashChanged, releaseReset);
        CountDownLatch createAttempted = new CountDownLatch(1);
        JdbcAdminUserAdministrationAdapter create = new JdbcAdminUserAdministrationAdapter(jdbc) {
            @Override public com.chatbotq.identityaccess.application.model.ManagedAdminUser createAsGeneralAdmin(
                    UUID a, UUID u, String e, String h, boolean g, Instant n) {
                createAttempted.countDown(); return super.createAsGeneralAdmin(a, u, e, h, g, n);
            }
        };
        Future<?> resetResult = pool.submit(() -> reset(reset, secondGeneral, actor, "hash:temp"));
        assertTrue(hashChanged.await(10, TimeUnit.SECONDS));
        Future<?> createResult = pool.submit(() -> create.createAsGeneralAdmin(actor, UUID.randomUUID(),
            "blocked@example.com", "hash:new", false, NOW));
        assertTrue(createAttempted.await(10, TimeUnit.SECONDS));
        releaseReset.countDown();

        resetResult.get(10, TimeUnit.SECONDS);
        assertFutureForbidden(createResult);
        assertEquals(0, count("select count(*) from admin_user where email=?", "blocked@example.com"));
    }

    @Test
    void concurrentCaseInsensitiveCreatesPersistExactlyOneEmail() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        Future<?> first = pool.submit(() -> createAtLatch(start, "Race@Example.com", successes, conflicts));
        Future<?> second = pool.submit(() -> createAtLatch(start, "race@example.COM", successes, conflicts));
        start.countDown();
        first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        assertEquals(1, successes.get());
        assertEquals(1, conflicts.get());
        assertEquals(1, count("select count(*) from admin_user where lower(email)=lower(?)", "race@example.com"));
    }

    private void createAtLatch(CountDownLatch start, String email, AtomicInteger successes, AtomicInteger conflicts) {
        await(start);
        try {
            new JdbcAdminUserAdministrationAdapter(jdbc).createAsGeneralAdmin(actor, UUID.randomUUID(),
                email, "hash:new", false, NOW);
            successes.incrementAndGet();
        } catch (AdminUserConflictException expected) { conflicts.incrementAndGet(); }
    }

    private JdbcAdminUserAdministrationAdapter pausingReset(CountDownLatch changed, CountDownLatch release) {
        return new JdbcAdminUserAdministrationAdapter(jdbc) {
            @Override protected void afterPasswordHashChanged() { changed.countDown(); await(release); }
        };
    }

    private void assertCompleteResetSerializes(boolean logoutParticipant) throws Exception {
        jdbc.update("update admin_user set status='PASSWORD_RESET_REQUIRED',password_hash='hash:Temporary123' where id=?",
            target);
        String currentHash = logoutParticipant ? repeat('4', 64) : repeat('3', 64);
        insertSession(UUID.randomUUID(), UUID.randomUUID(), currentHash, null, null);
        CountDownLatch credentialLocked = new CountDownLatch(1);
        CountDownLatch releaseReset = new CountDownLatch(1);
        AdminUserRepository completingUsers = new HookedUsers(users) {
            @Override public Optional<AdminUser> findByEmailForUpdate(String email) {
                Optional<AdminUser> result = super.findByEmailForUpdate(email);
                credentialLocked.countDown(); await(releaseReset); return result;
            }
        };
        CountDownLatch participantAttempted = new CountDownLatch(1);
        AdminUserRepository participantUsers = new HookedUsers(users) {
            @Override public Optional<AdminUser> findByIdForUpdate(UUID id) {
                participantAttempted.countDown(); return super.findByIdForUpdate(id);
            }
        };
        CompleteAdminPasswordResetUseCase complete = complete(completingUsers);

        Future<?> completeResult = pool.submit(() -> complete.execute(
            "target@example.com", "Temporary123", "Permanent456"));
        assertTrue(credentialLocked.await(10, TimeUnit.SECONDS));
        Future<?> participantResult;
        if (logoutParticipant) {
            LogoutAdminUseCase logout = new LogoutAdminUseCase(
                identityTokens(), participantUsers, sessions, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
            participantResult = pool.submit(() -> logout.execute(currentHash));
        } else {
            participantResult = pool.submit(() -> refreshWithIdentityToken(participantUsers).execute(currentHash));
        }
        assertTrue(participantAttempted.await(10, TimeUnit.SECONDS));
        releaseReset.countDown();

        completeResult.get(10, TimeUnit.SECONDS);
        if (logoutParticipant) participantResult.get(10, TimeUnit.SECONDS);
        else assertFutureInvalid(participantResult);
        assertEquals("ACTIVE", jdbc.queryForObject("select status from admin_user where id=?", String.class, target));
        assertEquals("hash:Permanent456", jdbc.queryForObject(
            "select password_hash from admin_user where id=?", String.class, target));
        assertEquals(1, count("select count(*) from admin_refresh_session where user_id=?", target));
        assertEquals(1, count("select count(*) from admin_refresh_session where user_id=? and revoked_at is not null",
            target));
    }

    private void reset(JdbcAdminUserAdministrationAdapter adapter, UUID actorId, UUID userId, String hash) {
        transactions.execute(() -> {
            adapter.resetPasswordAsGeneralAdmin(actorId, userId, hash, NOW);
            sessions.revokeAllByUserOrdered(userId, NOW);
            return null;
        });
    }

    private LoginAdminUseCase login(AdminUserRepository repository) {
        PasswordVerifier verifier = (raw, encoded) -> ("hash:" + raw).equals(encoded);
        return new LoginAdminUseCase(repository, verifier, "hash:dummy", access(), tokens(), sessions,
            transactions, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7));
    }

    private RefreshAdminSessionUseCase refresh(AdminUserRepository repository) {
        return new RefreshAdminSessionUseCase(repository, access(), tokens(), sessions, transactions,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7));
    }

    private CompleteAdminPasswordResetUseCase complete(AdminUserRepository repository) {
        return new CompleteAdminPasswordResetUseCase(repository,
            (raw, encoded) -> ("hash:" + raw).equals(encoded), raw -> "hash:" + raw,
            sessions, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RefreshAdminSessionUseCase refreshWithIdentityToken(AdminUserRepository repository) {
        return new RefreshAdminSessionUseCase(repository, access(), identityTokens(), sessions, transactions,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(7));
    }

    private RefreshTokenManager identityTokens() {
        return new RefreshTokenManager() {
            public String generate() { return repeat('9', 64); }
            public String hash(String token) { return token; }
        };
    }

    private AccessTokenIssuer access() { return (user, issuedAt) -> "access:" + UUID.randomUUID(); }
    private RefreshTokenManager tokens() {
        return new com.chatbotq.identityaccess.infrastructure.security.SecureRefreshTokenManager();
    }

    private UUID user(String email, boolean general, String status, String hash) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_user(id,email,password_hash,status,is_general_admin,created_at,updated_at) values (?,?,?,?,?,?,?)",
            id, email, hash, status, general, Timestamp.from(NOW.minusSeconds(30)), Timestamp.from(NOW.minusSeconds(30)));
        return id;
    }

    private void insertSession(UUID id, UUID family, String hash, Instant rotatedAt, UUID replacedBy) {
        jdbc.update("insert into admin_refresh_session(id,user_id,family_id,token_hash,issued_at,expires_at,"
                + "rotated_at,revoked_at,replaced_by_id) values (?,?,?,?,?,?,?,?,?)",
            id, target, family, hash, Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.plusSeconds(3600)),
            rotatedAt == null ? null : Timestamp.from(rotatedAt), null, replacedBy);
    }


    private int count(String sql, Object value) { return jdbc.queryForObject(sql, Integer.class, value); }
    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void assertFutureInvalid(Future<?> future) throws Exception {
        java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> future.get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof InvalidAuthenticationException);
    }
    private static void assertFutureForbidden(Future<?> future) throws Exception {
        java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> future.get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof ForbiddenAdminUserAdministrationException);
    }

    private static class HookedUsers implements AdminUserRepository {
        private final AdminUserRepository delegate;
        HookedUsers(AdminUserRepository delegate) { this.delegate = delegate; }
        public boolean existsByEmail(String email) { return delegate.existsByEmail(email); }
        public Optional<AdminUser> findById(UUID id) { return delegate.findById(id); }
        public Optional<AdminUser> findByEmail(String email) { return delegate.findByEmail(email); }
        public Optional<AdminUser> findByIdForUpdate(UUID id) { return delegate.findByIdForUpdate(id); }
        public Optional<AdminUser> findByEmailForUpdate(String email) { return delegate.findByEmailForUpdate(email); }
        public void completePasswordReset(UUID id, String hash, Instant now) { delegate.completePasswordReset(id, hash, now); }
        public AdminUser save(AdminUser user) { return delegate.save(user); }
    }

    private static class HookedSessions implements RefreshSessionRepository {
        private final RefreshSessionRepository delegate;
        HookedSessions(RefreshSessionRepository delegate) { this.delegate = delegate; }
        public void save(RefreshSession session) { delegate.save(session); }
        public Optional<RefreshSession> findByTokenHash(String hash) { return delegate.findByTokenHash(hash); }
        public Optional<RefreshSession> findByTokenHashForUpdate(String hash) {
            return delegate.findByTokenHashForUpdate(hash);
        }
        public Optional<RefreshSession> findByTokenHashAndLockFamily(String hash) {
            return delegate.findByTokenHashAndLockFamily(hash);
        }
        public Optional<UUID> findUserIdByTokenHash(String hash) { return delegate.findUserIdByTokenHash(hash); }
        public boolean replaceIfUsable(RefreshSession current, RefreshSession replacement, Instant now) {
            return delegate.replaceIfUsable(current, replacement, now);
        }
        public void revokeFamilyOrdered(UUID familyId, Instant now) { delegate.revokeFamilyOrdered(familyId, now); }
        public void revokeAllByUserOrdered(UUID userId, Instant now) { delegate.revokeAllByUserOrdered(userId, now); }
    }
}
