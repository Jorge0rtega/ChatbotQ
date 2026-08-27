package com.chatbotq.projects.infrastructure.persistence;

import com.chatbotq.projects.application.model.ManagedSiteKey;
import com.chatbotq.projects.application.usecase.ForbiddenProjectAdministrationException;
import com.chatbotq.projects.application.usecase.ProjectNotFoundException;
import com.chatbotq.projects.application.usecase.SiteKeyRotationFailedException;
import com.chatbotq.projects.application.usecase.StaleSiteKeyVersionException;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcProjectSiteKeyAdministrationTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private JdbcProjectAdministrationAdapter adapter;

    @BeforeAll
    static void database() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(),
            postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }

    @BeforeEach
    void clean() {
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        adapter = new JdbcProjectAdministrationAdapter(jdbc);
    }

    @Test
    void readsOneSnapshotWithUniformProjectAdminDenialsAndGeneralDisabledVisibility() {
        UUID general = user(true);
        UUID projectAdmin = user(false);
        UUID assigned = project("ACTIVE");
        UUID foreign = project("ACTIVE");
        UUID disabled = project("DISABLED");
        UUID missing = UUID.randomUUID();
        jdbc.update("insert into user_project_role(user_id,project_id,role) values (?,?,'PROJECT_ADMIN')",
            projectAdmin, assigned);

        assertEquals(key(assigned), adapter.read(general, assigned).getSiteKey());
        assertEquals(key(disabled), adapter.read(general, disabled).getSiteKey());
        assertEquals(key(assigned), adapter.read(projectAdmin, assigned).getSiteKey());
        assertThrows(ForbiddenProjectAdministrationException.class, () -> adapter.read(projectAdmin, foreign));
        assertThrows(ForbiddenProjectAdministrationException.class, () -> adapter.read(projectAdmin, disabled));
        assertThrows(ForbiddenProjectAdministrationException.class, () -> adapter.read(projectAdmin, missing));
        assertThrows(ProjectNotFoundException.class, () -> adapter.read(general, missing));
    }

    @Test
    void rotatesAtomicallyWithCasAuditAndImmediateOldKeyInvalidationIncludingDisabledTarget() {
        UUID general = user(true);
        UUID project = project("DISABLED");
        UUID oldKey = key(project);
        UUID newKey = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-26T13:00:00Z");

        ManagedSiteKey rotated = transactions.execute(status ->
            adapter.rotateAsGeneralAdmin(general, project, 1L, newKey, now));

        assertEquals(newKey, rotated.getSiteKey());
        assertEquals(2L, rotated.getVersion());
        assertEquals(now, rotated.getRotatedAt());
        Map<String, Object> row = jdbc.queryForMap("select site_key,site_key_version,site_key_rotated_at,"
            + "site_key_rotated_by,status from project where id=?", project);
        assertEquals(newKey, row.get("site_key"));
        assertEquals(2L, row.get("site_key_version"));
        assertEquals(now, ((Timestamp) row.get("site_key_rotated_at")).toInstant());
        assertEquals(general, row.get("site_key_rotated_by"));
        assertEquals("DISABLED", row.get("status"));
        assertEquals(0, jdbc.queryForObject("select count(*) from project where site_key=?", Integer.class, oldKey));

        assertThrows(StaleSiteKeyVersionException.class, () -> transactions.execute(status ->
            adapter.rotateAsGeneralAdmin(general, project, 1L, UUID.randomUUID(), now.plusSeconds(1))));
        assertEquals(newKey, key(project));
    }

    @Test
    void authenticatesBeforeTargetAndRejectsVersionOverflowWithoutMutation() {
        UUID projectAdmin = user(false);
        UUID general = user(true);
        UUID project = project("ACTIVE");
        UUID before = key(project);
        UUID missing = UUID.randomUUID();

        assertThrows(ForbiddenProjectAdministrationException.class, () -> transactions.execute(status ->
            adapter.rotateAsGeneralAdmin(projectAdmin, missing, 1L, UUID.randomUUID(), Instant.now())));
        jdbc.update("update project set site_key_version=? where id=?", Long.MAX_VALUE, project);
        assertThrows(StaleSiteKeyVersionException.class, () -> transactions.execute(status ->
            adapter.rotateAsGeneralAdmin(general, project, Long.MAX_VALUE, UUID.randomUUID(), Instant.now())));
        assertEquals(before, key(project));
        assertEquals(Long.MAX_VALUE,
            jdbc.queryForObject("select site_key_version from project where id=?", Long.class, project));
    }

    @Test
    void uniqueCollisionFailsClosedAndRollsBackWithoutChangingCurrentKey() {
        UUID general = user(true);
        UUID project = project("ACTIVE");
        UUID other = project("ACTIVE");
        UUID before = key(project);
        UUID colliding = key(other);

        assertThrows(SiteKeyRotationFailedException.class, () -> transactions.execute(status ->
            adapter.rotateAsGeneralAdmin(general, project, 1L, colliding, Instant.now())));
        assertEquals(before, key(project));
        assertEquals(1L, jdbc.queryForObject("select site_key_version from project where id=?",
            Long.class, project));
    }

    @Test
    void concurrentRotationsWithSameExpectedVersionProduceOneWinnerAndOneConflict() throws Exception {
        UUID general = user(true);
        UUID project = project("ACTIVE");
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> rotateOutcome(start, general, project, firstKey));
            Future<Object> second = pool.submit(() -> rotateOutcome(start, general, project, secondKey));
            start.countDown();
            Object firstOutcome = first.get(10, TimeUnit.SECONDS);
            Object secondOutcome = second.get(10, TimeUnit.SECONDS);

            int successes = (firstOutcome instanceof ManagedSiteKey ? 1 : 0)
                + (secondOutcome instanceof ManagedSiteKey ? 1 : 0);
            int conflicts = (firstOutcome instanceof StaleSiteKeyVersionException ? 1 : 0)
                + (secondOutcome instanceof StaleSiteKeyVersionException ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(1, conflicts);
            ManagedSiteKey winner = firstOutcome instanceof ManagedSiteKey
                ? (ManagedSiteKey) firstOutcome : (ManagedSiteKey) secondOutcome;
            assertEquals(winner.getSiteKey(), key(project));
            assertEquals(2L, winner.getVersion());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void actorDeactivationSerializedByActorLockPreventsRotation() throws Exception {
        UUID general = user(true);
        UUID project = project("ACTIVE");
        UUID before = key(project);
        CountDownLatch actorQueryEntered = new CountDownLatch(1);
        JdbcTemplate observingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> java.util.List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper,
                                               Object... args) {
                if (sql.contains("from admin_user u") && sql.contains("for update")) {
                    actorQueryEntered.countDown();
                }
                return super.query(sql, mapper, args);
            }
        };
        JdbcProjectAdministrationAdapter observingAdapter = new JdbcProjectAdministrationAdapter(observingJdbc);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> rotation = transactions.execute(status -> {
                jdbc.queryForObject("select id from admin_user where id=? for update", UUID.class, general);
                Future<Object> pending = pool.submit(() -> {
                    try {
                        return transactions.execute(inner -> observingAdapter.rotateAsGeneralAdmin(general, project,
                            1L, UUID.randomUUID(), Instant.now()));
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                });
                try {
                    assertTrue(actorQueryEntered.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                jdbc.update("update admin_user set status='DISABLED' where id=?", general);
                return pending;
            });
            Object outcome = rotation.get(10, TimeUnit.SECONDS);
            assertTrue(outcome instanceof ForbiddenProjectAdministrationException);
            assertEquals(before, key(project));
            assertEquals(1L, jdbc.queryForObject("select site_key_version from project where id=?",
                Long.class, project));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Object rotateOutcome(CountDownLatch start, UUID actor, UUID project, UUID generatedKey) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) return new AssertionError("start timeout");
            return transactions.execute(status -> adapter.rotateAsGeneralAdmin(actor, project, 1L,
                generatedKey, Instant.now()));
        } catch (RuntimeException failure) {
            return failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return interrupted;
        }
    }

    private UUID user(boolean general) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_user(id,email,password_hash,status,is_general_admin) values (?,?,?,'ACTIVE',?)",
            id, id + "@example.com", "hash", general);
        return id;
    }

    private UUID project(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into project(id,name,status,created_at,updated_at) values (?,? ,?,now(),now())",
            id, "Project " + id, status);
        return id;
    }

    private UUID key(UUID project) {
        return jdbc.queryForObject("select site_key from project where id=?", UUID.class, project);
    }
}
