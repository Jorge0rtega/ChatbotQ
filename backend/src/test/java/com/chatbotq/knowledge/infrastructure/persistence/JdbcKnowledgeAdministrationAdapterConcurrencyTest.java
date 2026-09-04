package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.identityaccess.infrastructure.persistence.JdbcAdminUserAdministrationAdapter;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcUserProjectAssignmentRepository;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;
import com.chatbotq.projects.infrastructure.persistence.JdbcProjectAdministrationAdapter;
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
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcKnowledgeAdministrationAdapterConcurrencyTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private JdbcKnowledgeAdministrationAdapter knowledge;
    private JdbcAdminUserAdministrationAdapter users;
    private JdbcProjectAdministrationAdapter projects;
    private JdbcUserProjectAssignmentRepository assignments;
    private UUID general;
    private UUID projectAdmin;
    private UUID project;
    private UUID entry;

    @BeforeAll
    static void database() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
            postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stop() { if (postgres != null) postgres.stop(); }

    @BeforeEach
    void seed() {
        jdbc.update("delete from knowledge_entry");
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        knowledge = new JdbcKnowledgeAdministrationAdapter(jdbc);
        users = new JdbcAdminUserAdministrationAdapter(jdbc);
        projects = new JdbcProjectAdministrationAdapter(jdbc);
        assignments = new JdbcUserProjectAssignmentRepository(jdbc);
        general = user(true);
        projectAdmin = user(false);
        project = UUID.randomUUID();
        entry = UUID.randomUUID();
        jdbc.update("insert into project(id,name,status) values (?,?,'ACTIVE')", project, "Project");
        jdbc.update("insert into user_project_role(user_id,project_id,role) values (?,?,'PROJECT_ADMIN')",
            projectAdmin, project);
        jdbc.update("insert into knowledge_entry(id,project_id,question,answer,active,created_by,updated_by) "
            + "values (?,?,? ,?,true,?,?)", entry, project, "Question", "Answer", general, general);
    }

    @Test
    void statusProjectAndAssignmentRevocationCannotCommitBetweenAuthorizationAndKnowledgeUpdate() throws Exception {
        assertRevocationWaitsForAuthorizedUpdate(new Revocation() {
            @Override public void revoke() {
                users.setActiveAsGeneralAdmin(general, projectAdmin, false, Instant.parse("2026-09-03T12:00:00Z"));
            }
        });
        assertEquals("DISABLED", jdbc.queryForObject("select status from admin_user where id=?", String.class, projectAdmin));

        seed();
        assertRevocationWaitsForAuthorizedUpdate(new Revocation() {
            @Override public void revoke() {
                projects.setActiveAsGeneralAdmin(general, project, false, Instant.parse("2026-09-03T12:00:00Z"));
            }
        });
        assertEquals("DISABLED", jdbc.queryForObject("select status from project where id=?", String.class, project));

        seed();
        assertRevocationWaitsForAuthorizedUpdate(new Revocation() {
            @Override public void revoke() {
                assignments.replaceAsGeneralAdmin(general, projectAdmin, Collections.<UUID>emptyList(),
                    Instant.parse("2026-09-03T12:00:00Z"));
            }
        });
        assertEquals(0, jdbc.queryForObject("select count(*) from user_project_role where user_id=? and project_id=?",
            Integer.class, projectAdmin, project).intValue());
    }

    private void assertRevocationWaitsForAuthorizedUpdate(final Revocation revocation) throws Exception {
        final CountDownLatch entryLocked = new CountDownLatch(1);
        final CountDownLatch releaseEntry = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> lock = executor.submit(new Runnable() {
                @Override public void run() {
                    transactions.execute(status -> {
                        jdbc.queryForObject("select id from knowledge_entry where id=? for update", UUID.class, entry);
                        entryLocked.countDown();
                        await(releaseEntry);
                        return null;
                    });
                }
            });
            assertTrue(entryLocked.await(5, TimeUnit.SECONDS));
            Future<ManagedKnowledgeEntry> update = executor.submit(() -> transactions.execute(status -> knowledge.update(
                projectAdmin, project, entry, "Changed", "Answer", "external", true, 0,
                Instant.parse("2026-09-03T12:00:00Z"))));
            waitForKnowledgeUpdateToBlock(update);
            Future<?> revoke = executor.submit(new Runnable() {
                @Override public void run() { transactions.execute(status -> { revocation.revoke(); return null; }); }
            });
            assertThrows(TimeoutException.class, () -> revoke.get(500, TimeUnit.MILLISECONDS));
            releaseEntry.countDown();
            lock.get(5, TimeUnit.SECONDS);
            assertEquals("Changed", update.get(5, TimeUnit.SECONDS).getQuestion());
            revoke.get(5, TimeUnit.SECONDS);
        } finally {
            releaseEntry.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void waitForKnowledgeUpdateToBlock(Future<ManagedKnowledgeEntry> update) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (update.isDone()) {
                update.get(1, TimeUnit.SECONDS);
                return;
            }
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock'",
                Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20L);
        }
        throw new AssertionError("knowledge update did not block on the locked entry");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting to release entry lock");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private UUID user(boolean generalAdmin) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_user(id,email,password_hash,status,is_general_admin) values (?,?,?,'ACTIVE',?)",
            id, id + "@example.com", "hash", generalAdmin);
        return id;
    }

    private interface Revocation { void revoke(); }
}
