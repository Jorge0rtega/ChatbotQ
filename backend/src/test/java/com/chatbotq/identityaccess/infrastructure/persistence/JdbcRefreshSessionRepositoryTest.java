package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.port.AccessTokenIssuer;
import com.chatbotq.identityaccess.application.port.RefreshTokenManager;
import com.chatbotq.identityaccess.application.usecase.InvalidAuthenticationException;
import com.chatbotq.identityaccess.application.usecase.RefreshAdminSessionUseCase;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.RefreshSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRefreshSessionRepositoryTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;
    private JdbcRefreshSessionRepository repository;
    private TransactionTemplate transactions;
    private UUID userId;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("chatbotq").withUsername("chatbotq").withPassword("chatbotq-test");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration").load().migrate();
        dataSource = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-25T10:00:00Z");
        AdminUser user = AdminUser.create(userId, "admin@example.com", "hash", true, now.minusSeconds(60));
        user.activate(now.minusSeconds(30));
        new JdbcAdminUserRepository(jdbc).save(user);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new JdbcRefreshSessionRepository(jdbc, transactions);
    }

    @AfterAll
    static void stopDatabase() { if (postgres != null) postgres.stop(); }

    @Test
    void savesAndFindsSessionByHashWithoutRawTokenColumn() {
        Instant now = Instant.parse("2026-08-25T10:00:00Z");
        RefreshSession session = RefreshSession.issue(UUID.randomUUID(), userId, UUID.randomUUID(),
            repeat('a', 64), now, now.plusSeconds(3600));

        repository.save(session);
        RefreshSession loaded = repository.findByTokenHash(repeat('a', 64)).get();

        assertEquals(session.getId(), loaded.getId());
        assertEquals(session.getFamilyId(), loaded.getFamilyId());
        assertTrue(loaded.isUsableAt(now.plusSeconds(1)));
        assertEquals(0, jdbc.queryForObject(
            "select count(*) from information_schema.columns where table_name='admin_refresh_session' and column_name='token'",
            Integer.class));
    }

    @Test
    void atomicallyRotatesOnceAndRevokesEveryMemberOfFamily() {
        Instant now = Instant.parse("2026-08-25T10:00:00Z");
        UUID familyId = UUID.randomUUID();
        RefreshSession current = RefreshSession.issue(UUID.randomUUID(), userId, familyId,
            repeat('b', 64), now, now.plusSeconds(3600));
        RefreshSession replacement = RefreshSession.issue(UUID.randomUUID(), userId, familyId,
            repeat('c', 64), now.plusSeconds(10), now.plusSeconds(3610));
        repository.save(current);

        assertTrue(repository.replaceIfUsable(current, replacement, now.plusSeconds(10)));
        assertFalse(repository.replaceIfUsable(current, replacement, now.plusSeconds(11)));
        assertFalse(repository.findByTokenHash(repeat('b', 64)).get().isUsableAt(now.plusSeconds(11)));
        assertTrue(repository.findByTokenHash(repeat('c', 64)).get().isUsableAt(now.plusSeconds(11)));

        repository.revokeFamily(familyId, now.plusSeconds(12));
        assertFalse(repository.findByTokenHash(repeat('c', 64)).get().isUsableAt(now.plusSeconds(13)));
        assertEquals(2, jdbc.queryForObject(
            "select count(*) from admin_refresh_session where family_id=? and revoked_at is not null",
            Integer.class, familyId));
    }

    @Test
    void rotationUsesIndependentTransactionAndSurvivesOuterRollback() {
        Instant now = Instant.parse("2026-08-25T10:00:00Z");
        UUID familyId = UUID.randomUUID();
        RefreshSession current = RefreshSession.issue(UUID.randomUUID(), userId, familyId,
            repeat('d', 64), now, now.plusSeconds(3600));
        RefreshSession replacement = RefreshSession.issue(UUID.randomUUID(), userId, familyId,
            repeat('e', 64), now.plusSeconds(10), now.plusSeconds(3610));
        repository.save(current);

        transactions.execute(status -> {
            assertTrue(repository.replaceIfUsable(current, replacement, now.plusSeconds(10)));
            status.setRollbackOnly();
            return null;
        });

        assertEquals(2, jdbc.queryForObject(
            "select count(*) from admin_refresh_session where family_id=?", Integer.class, familyId));
    }

    @Test
    void lostCasInsideOuterTransactionDurablyRevokesFamilyWithoutOrphanReplacement() {
        Instant now = Instant.parse("2026-08-25T10:00:00Z");
        UUID familyId = UUID.randomUUID();
        RefreshSession current = RefreshSession.issue(UUID.randomUUID(), userId, familyId,
            repeat('f', 64), now.minusSeconds(60), now.plusSeconds(3600));
        RefreshSession winner = RefreshSession.issue(UUID.randomUUID(), userId, familyId,
            repeat('1', 64), now, now.plusSeconds(3600));
        repository.save(current);

        AccessTokenIssuer accessTokens = new AccessTokenIssuer() {
            @Override
            public String issue(AdminUser user, Instant issuedAt) {
                assertTrue(repository.replaceIfUsable(current, winner, now));
                return "access-token";
            }
        };
        RefreshTokenManager losingTokens = new RefreshTokenManager() {
            public String generate() { return "losing-replacement"; }
            public String hash(String token) {
                return "losing-replacement".equals(token) ? repeat('2', 64) : repeat('f', 64);
            }
        };
        RefreshAdminSessionUseCase useCase = new RefreshAdminSessionUseCase(
            new JdbcAdminUserRepository(jdbc), accessTokens, losingTokens, repository,
            Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(7));

        assertThrows(InvalidAuthenticationException.class,
            () -> transactions.execute(status -> useCase.execute("original-token")));

        DriverManagerDataSource observerDataSource = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate observer = new JdbcTemplate(observerDataSource);
        TransactionTemplate observerTransaction = new TransactionTemplate(
            new DataSourceTransactionManager(observerDataSource));
        observerTransaction.execute(status -> {
            assertEquals(2, observer.queryForObject(
                "select count(*) from admin_refresh_session where family_id=? and revoked_at is not null",
                Integer.class, familyId));
            assertEquals(0, observer.queryForObject(
                "select count(*) from admin_refresh_session where token_hash=?",
                Integer.class, repeat('2', 64)));
            return null;
        });
    }

    @Test
    void concurrentDistinctReplacementsProduceExactlyOneWinnerAndNoOrphan() throws Exception {
        Instant now = Instant.parse("2026-08-25T10:00:00Z");
        UUID familyId = UUID.randomUUID();
        RefreshSession current = RefreshSession.issue(UUID.randomUUID(), userId, familyId,
            repeat('3', 64), now.minusSeconds(60), now.plusSeconds(3600));
        RefreshSession firstReplacement = RefreshSession.issue(UUID.randomUUID(), userId, familyId,
            repeat('4', 64), now, now.plusSeconds(3600));
        RefreshSession secondReplacement = RefreshSession.issue(UUID.randomUUID(), userId, familyId,
            repeat('5', 64), now, now.plusSeconds(3600));
        repository.save(current);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(() -> {
                start.await();
                return repository.replaceIfUsable(current, firstReplacement, now);
            });
            Future<Boolean> second = workers.submit(() -> {
                start.await();
                return repository.replaceIfUsable(current, secondReplacement, now);
            });

            assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
            assertEquals(2, jdbc.queryForObject(
                "select count(*) from admin_refresh_session where family_id=?", Integer.class, familyId));
            assertEquals(1, jdbc.queryForObject(
                "select count(*) from admin_refresh_session where token_hash in (?,?)",
                Integer.class, repeat('4', 64), repeat('5', 64)));
        } finally {
            workers.shutdownNow();
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
