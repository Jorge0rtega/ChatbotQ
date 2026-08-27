package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.application.model.CurrentAdminView;
import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.usecase.AdminUserConflictException;
import com.chatbotq.identityaccess.application.usecase.AdminUserNotFoundException;
import com.chatbotq.identityaccess.application.usecase.AssignedProjectNotFoundException;
import com.chatbotq.identityaccess.application.usecase.ForbiddenAdminUserAdministrationException;
import com.chatbotq.infrastructure.transaction.SpringApplicationTransaction;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcProjectAssignmentAdaptersTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static ApplicationTransaction transactions;
    private JdbcUserProjectAssignmentRepository assignments;

    @BeforeAll static void database() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres")); postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new SpringApplicationTransaction(new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }
    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }
    @BeforeEach void clean() {
        jdbc.update("delete from admin_refresh_session"); jdbc.update("delete from user_project_role");
        jdbc.update("delete from admin_user"); jdbc.update("delete from project");
        assignments = new JdbcUserProjectAssignmentRepository(jdbc);
    }

    @Test void replacesWholeSetIncludingDisabledProjectsAndListsDeterministicallyForManagedDisabledTarget() {
        UUID actor = user("general@example.com", true, "ACTIVE");
        UUID target = user("target@example.com", false, "DISABLED");
        UUID high = project("ffffffff-ffff-ffff-ffff-ffffffffffff", "ACTIVE");
        UUID low = project("00000000-0000-0000-0000-000000000001", "DISABLED");
        Instant now = Instant.parse("2026-08-26T12:00:00Z");

        List<UUID> result = transactions.execute(() -> assignments.replaceAsGeneralAdmin(
            actor, target, Arrays.asList(low, high), now));

        assertEquals(Arrays.asList(low, high), result);
        assertEquals(Arrays.asList(low, high), assignments.listAsGeneralAdmin(actor, target));
        assertEquals(2, jdbc.queryForObject("select count(*) from user_project_role where user_id=?", Integer.class, target));
        assertEquals(now, jdbc.queryForObject("select min(assigned_at) from user_project_role where user_id=?",
            Timestamp.class, target).toInstant());
        transactions.execute(() -> assignments.replaceAsGeneralAdmin(actor, target, Collections.<UUID>emptyList(), now));
        assertEquals(Collections.emptyList(), assignments.listAsGeneralAdmin(actor, target));
    }

    @Test void classifiesAuthorizationBeforeTargetAndRejectsGeneralTargetAndMissingProjectWithoutPartialState() {
        UUID actor = user("general@example.com", true, "ACTIVE");
        UUID projectAdmin = user("project@example.com", false, "PASSWORD_RESET_REQUIRED");
        UUID generalTarget = user("other-general@example.com", true, "ACTIVE");
        UUID project = project(UUID.randomUUID().toString(), "ACTIVE");
        transactions.execute(() -> assignments.replaceAsGeneralAdmin(actor, projectAdmin,
            Collections.singletonList(project), Instant.EPOCH));

        UUID unauthorized = user("unauthorized@example.com", false, "ACTIVE");
        assertThrows(ForbiddenAdminUserAdministrationException.class,
            () -> assignments.listAsGeneralAdmin(unauthorized, UUID.randomUUID()));
        jdbc.update("update admin_user set status='DISABLED' where id=?", actor);
        assertThrows(ForbiddenAdminUserAdministrationException.class, () -> transactions.execute(() ->
            assignments.replaceAsGeneralAdmin(actor, UUID.randomUUID(), Collections.<UUID>emptyList(), Instant.EPOCH)));
        jdbc.update("update admin_user set status='ACTIVE',locked_until=now()+interval '1 hour' where id=?", actor);
        assertThrows(ForbiddenAdminUserAdministrationException.class, () -> transactions.execute(() ->
            assignments.replaceAsGeneralAdmin(actor, projectAdmin, Collections.<UUID>emptyList(), Instant.EPOCH)));
        jdbc.update("update admin_user set locked_until=null where id=?", actor);
        assertThrows(AdminUserNotFoundException.class, () -> assignments.listAsGeneralAdmin(actor, UUID.randomUUID()));
        assertThrows(AdminUserConflictException.class, () -> assignments.listAsGeneralAdmin(actor, generalTarget));
        assertThrows(AssignedProjectNotFoundException.class, () -> transactions.execute(() ->
            assignments.replaceAsGeneralAdmin(actor, projectAdmin,
                Arrays.asList(project, UUID.randomUUID()), Instant.EPOCH)));
        assertEquals(Collections.singletonList(project), assignments.listAsGeneralAdmin(actor, projectAdmin));
    }

    @Test void meUsesCurrentRoleStatusAndOnlyActiveProjects() {
        UUID general = user("general@example.com", true, "ACTIVE");
        UUID target = user("target@example.com", false, "ACTIVE");
        UUID active = project(UUID.randomUUID().toString(), "ACTIVE");
        UUID disabled = project(UUID.randomUUID().toString(), "DISABLED");
        transactions.execute(() -> assignments.replaceAsGeneralAdmin(general, target,
            Arrays.asList(disabled, active), Instant.EPOCH));
        assertEquals(Collections.singletonList(active), assignments.listActiveForCurrentUser(target));
        assertEquals(Collections.emptyList(), assignments.listActiveForCurrentUser(general));
        jdbc.update("update project set status='DISABLED' where id=?", active);
        assertEquals(Collections.emptyList(), assignments.listActiveForCurrentUser(target));
        jdbc.update("update admin_user set status='DISABLED' where id=?", target);
        assertThrows(ForbiddenAdminUserAdministrationException.class,
            () -> assignments.listActiveForCurrentUser(target));
    }

    @Test void currentAdminViewUsesOneStatementForIdentityRoleAndActiveProjects() {
        UUID general = user("general@example.com", true, "ACTIVE");
        UUID target = user("target@example.com", false, "ACTIVE");
        UUID active = project("00000000-0000-0000-0000-000000000002", "ACTIVE");
        UUID disabled = project("00000000-0000-0000-0000-000000000001", "DISABLED");
        transactions.execute(() -> assignments.replaceAsGeneralAdmin(general, target,
            Arrays.asList(disabled, active), Instant.EPOCH));
        AtomicInteger statements = new AtomicInteger();
        JdbcTemplate counted = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> T query(String sql,
                    org.springframework.jdbc.core.ResultSetExtractor<T> extractor, Object... args) {
                statements.incrementAndGet(); return super.query(sql, extractor, args);
            }
        };

        CurrentAdminView view = new JdbcCurrentAdminViewAdapter(counted).findAvailable(target).get();

        assertEquals(target, view.getUserId());
        assertEquals("target@example.com", view.getEmail());
        assertEquals(false, view.isGeneralAdmin());
        assertEquals(Collections.singletonList(active), view.getProjectIds());
        assertEquals(1, statements.get());
        assertEquals(Collections.emptyList(), new JdbcCurrentAdminViewAdapter(jdbc)
            .findAvailable(general).get().getProjectIds());
        jdbc.update("update admin_user set locked_until=now()+interval '1 hour' where id=?", target);
        assertEquals(false, new JdbcCurrentAdminViewAdapter(jdbc).findAvailable(target).isPresent());
    }

    @Test void failureAfterAtLeastOneInsertRollsBackTheOriginalCompleteSet() {
        UUID actor = user("general@example.com", true, "ACTIVE");
        UUID target = user("target@example.com", false, "ACTIVE");
        UUID original = project(UUID.randomUUID().toString(), "ACTIVE");
        UUID replacement = project(UUID.randomUUID().toString(), "ACTIVE");
        UUID secondReplacement = project(UUID.randomUUID().toString(), "ACTIVE");
        transactions.execute(() -> assignments.replaceAsGeneralAdmin(actor,target,
            Collections.singletonList(original),Instant.EPOCH));
        AtomicInteger inserted = new AtomicInteger();
        JdbcUserProjectAssignmentRepository failing = new JdbcUserProjectAssignmentRepository(jdbc) {
            @Override protected void afterAssignmentInserted(UUID ignored) {
                if (inserted.incrementAndGet() == 1) throw new IllegalStateException("forced rollback after insert");
            }
        };
        assertThrows(IllegalStateException.class, () -> transactions.execute(() -> failing.replaceAsGeneralAdmin(
            actor,target,Arrays.asList(replacement,secondReplacement),Instant.EPOCH)));
        assertEquals(1, inserted.get(), "seam must prove an insert happened before failure");
        assertEquals(Collections.singletonList(original), assignments.listAsGeneralAdmin(actor,target));
    }

    @Test void thousandAssignmentsUseOneBatchInsteadOfOneStatementPerId() {
        UUID actor = user("general@example.com", true, "ACTIVE");
        UUID target = user("target@example.com", false, "ACTIVE");
        java.util.ArrayList<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) ids.add(project(UUID.randomUUID().toString(), "ACTIVE"));
        AtomicInteger batches = new AtomicInteger();
        JdbcTemplate counted = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public int[] batchUpdate(String sql, java.util.List<Object[]> args) {
                batches.incrementAndGet(); return super.batchUpdate(sql, args);
            }
        };

        transactions.execute(() -> new JdbcUserProjectAssignmentRepository(counted)
            .replaceAsGeneralAdmin(actor, target, ids, Instant.EPOCH));

        assertEquals(1, batches.get());
        assertEquals(1000, jdbc.queryForObject("select count(*) from user_project_role where user_id=?",
            Integer.class, target).intValue());
    }

    @Test void replaceRacingActorDeactivationIsSerializedAgainstCurrentActorState() throws Exception {
        UUID actor = user("general@example.com", true, "ACTIVE");
        UUID secondGeneral = user("second-general@example.com", true, "ACTIVE");
        UUID target = user("target@example.com", false, "ACTIVE");
        UUID project = project(UUID.randomUUID().toString(), "ACTIVE");
        CountDownLatch replaceLocked = new CountDownLatch(1);
        CountDownLatch deactivateAttempted = new CountDownLatch(1);
        JdbcUserProjectAssignmentRepository blocking = new JdbcUserProjectAssignmentRepository(jdbc) {
            @Override protected void afterUsersLocked() {
                replaceLocked.countDown(); await(deactivateAttempted);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> replace = pool.submit(() -> transactions.execute(() ->
                blocking.replaceAsGeneralAdmin(actor,target,Collections.singletonList(project),Instant.EPOCH)));
            assertTrue(replaceLocked.await(5, TimeUnit.SECONDS), "replace did not acquire ordered user locks");
            Future<?> deactivate = pool.submit(() -> {
                deactivateAttempted.countDown();
                return transactions.execute(() -> {
                    new JdbcAdminUserAdministrationAdapter(jdbc)
                        .setActiveAsGeneralAdmin(secondGeneral,actor,false,Instant.now());
                    return null;
                });
            });
            replace.get(5, TimeUnit.SECONDS);
            deactivate.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals("DISABLED",jdbc.queryForObject("select status from admin_user where id=?",String.class,actor));
        assertEquals(1,jdbc.queryForObject("select count(*) from user_project_role where user_id=?",
            Integer.class,target).intValue());
    }

    @Test void replaceRacingTargetDeactivationWaitsAndCompletesWithoutDeadlock() throws Exception {
        UUID target = user(UUID.fromString("00000000-0000-0000-0000-000000000010"),
            "target-fixed@example.com", false, "ACTIVE");
        UUID deactivator = user(UUID.fromString("00000000-0000-0000-0000-000000000020"),
            "deactivator-fixed@example.com", true, "ACTIVE");
        UUID actor = user(UUID.fromString("00000000-0000-0000-0000-000000000030"),
            "actor-fixed@example.com", true, "ACTIVE");
        UUID project = project(UUID.randomUUID().toString(), "ACTIVE");
        CountDownLatch replaceLocked = new CountDownLatch(1);
        CountDownLatch releaseReplace = new CountDownLatch(1);
        CountDownLatch deactivateStarted = new CountDownLatch(1);
        AtomicInteger deactivatorPid = new AtomicInteger();
        JdbcUserProjectAssignmentRepository blocking = new JdbcUserProjectAssignmentRepository(jdbc) {
            @Override protected void afterUsersLocked() {
                replaceLocked.countDown(); await(releaseReplace);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> replace = null;
        Future<?> deactivate = null;
        try {
            replace = pool.submit(() -> transactions.execute(() -> blocking.replaceAsGeneralAdmin(
                actor, target, Collections.singletonList(project), Instant.EPOCH)));
            assertTrue(replaceLocked.await(5, TimeUnit.SECONDS));
            deactivate = pool.submit(() -> transactions.execute(() -> {
                deactivatorPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                deactivateStarted.countDown();
                new JdbcAdminUserAdministrationAdapter(jdbc)
                    .setActiveAsGeneralAdmin(deactivator, target, false, Instant.now());
                return null;
            }));
            assertTrue(deactivateStarted.await(5, TimeUnit.SECONDS));
            assertTrue(awaitDatabaseLock(deactivatorPid.get(), 5),
                "deactivation must actually wait on the replace admin_user lock");
            releaseReplace.countDown();
            replace.get(5, TimeUnit.SECONDS);
            deactivate.get(5, TimeUnit.SECONDS);
        } finally {
            releaseReplace.countDown();
            if (replace != null && !replace.isDone()) replace.cancel(true);
            if (deactivate != null && !deactivate.isDone()) deactivate.cancel(true);
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals("DISABLED", jdbc.queryForObject("select status from admin_user where id=?", String.class, target));
        assertEquals(Collections.singletonList(project), assignments.listAsGeneralAdmin(actor, target));
    }

    @Test void concurrentReplacesSerializeAndNeverProduceAMixedSet() throws Exception {
        UUID actor = user("general@example.com", true, "ACTIVE");
        UUID target = user("target@example.com", false, "ACTIVE");
        UUID a = project(UUID.randomUUID().toString(), "ACTIVE"); UUID b = project(UUID.randomUUID().toString(), "ACTIVE");
        UUID c = project(UUID.randomUUID().toString(), "ACTIVE"); UUID d = project(UUID.randomUUID().toString(), "ACTIVE");
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        JdbcUserProjectAssignmentRepository first = new JdbcUserProjectAssignmentRepository(jdbc) {
            @Override protected void afterUsersLocked() {
                firstLocked.countDown(); await(secondAttempted);
            }
        };
        JdbcUserProjectAssignmentRepository second = new JdbcUserProjectAssignmentRepository(jdbc) {
            @Override public List<UUID> replaceAsGeneralAdmin(UUID actorId, UUID userId,
                    List<UUID> ids, Instant now) {
                secondAttempted.countDown();
                return super.replaceAsGeneralAdmin(actorId, userId, ids, now);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = pool.submit(() -> transactions.execute(() ->
                first.replaceAsGeneralAdmin(actor,target,Arrays.asList(a,b),Instant.EPOCH)));
            assertTrue(firstLocked.await(5, TimeUnit.SECONDS), "first replace did not acquire ordered user locks");
            Future<?> two = pool.submit(() -> transactions.execute(() ->
                second.replaceAsGeneralAdmin(actor,target,Arrays.asList(c,d),Instant.EPOCH)));
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        List<UUID> actual = assignments.listAsGeneralAdmin(actor, target);
        assertEquals(sorted(c,d), actual, "last serialized replacement must be its complete set");
    }

    private static void await(CountDownLatch latch) { try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
    private boolean awaitDatabaseLock(int pid, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject(
                "select coalesce((select wait_event_type='Lock' from pg_stat_activity where pid=?),false)",
                Boolean.class, pid);
            if (Boolean.TRUE.equals(waiting)) return true;
            Thread.sleep(25L);
        }
        return false;
    }
    private static List<UUID> sorted(UUID a, UUID b) { List<UUID> values = Arrays.asList(a,b); values.sort((x,y)->x.toString().compareTo(y.toString())); return values; }
    private UUID user(String email, boolean general, String status) { UUID id=UUID.randomUUID(); jdbc.update("insert into admin_user(id,email,password_hash,status,is_general_admin) values (?,?,?,?,?)", id,email,"hash",status,general); return id; }
    private UUID user(UUID id, String email, boolean general, String status) { jdbc.update("insert into admin_user(id,email,password_hash,status,is_general_admin) values (?,?,?,?,?)", id,email,"hash",status,general); return id; }
    private UUID project(String id, String status) { UUID uuid=UUID.fromString(id); jdbc.update("insert into project(id,name,status) values (?,?,?)",uuid,"project-"+id,status); return uuid; }
}
