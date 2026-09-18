package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.identityaccess.infrastructure.persistence.JdbcUserProjectAssignmentRepository;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.port.KnowledgeImportExecutionPort;
import com.chatbotq.knowledge.application.usecase.ForbiddenKnowledgeAdministrationException;
import com.chatbotq.knowledge.application.usecase.ImportExecutionNotReadyException;
import com.chatbotq.knowledge.application.usecase.KnowledgeImportJobNotFoundException;
import com.chatbotq.projects.application.usecase.ProjectNotFoundException;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcKnowledgeImportExecutionAdapterTest {
    private static PostgreSQLContainer<?> postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private KnowledgeImportExecutionPort executions;
    private UUID general;
    private UUID projectAdmin;
    private UUID unassignedProjectAdmin;
    private UUID project;
    private UUID job;

    @BeforeAll
    static void database() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stop() { if (postgres != null) postgres.stop(); }

    @BeforeEach
    void seed() {
        jdbc.update("delete from knowledge_import_row");
        jdbc.update("delete from knowledge_import_job");
        jdbc.update("delete from user_project_role");
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        general = user(true);
        projectAdmin = user(false);
        unassignedProjectAdmin = user(false);
        project = UUID.randomUUID();
        job = UUID.randomUUID();
        jdbc.update("insert into project(id,name,status) values (?,?,'ACTIVE')", project, "Project");
        jdbc.update("insert into user_project_role(user_id,project_id,role) values (?,?,'PROJECT_ADMIN')", projectAdmin, project);
        readyJob(job, project);
        executions = new JdbcKnowledgeImportExecutionAdapter(jdbc);
    }

    @Test
    void claimsReadyJobWithDatabaseLeaseAndFencingToken() {
        ClaimedKnowledgeImportExecution claim = executions.claimReadyForExecution(general, project, job);

        assertEquals(job, claim.getJobId());
        assertEquals(project, claim.getProjectId());
        assertEquals("UPSERT", claim.getStrategy());
        assertNotNull(claim.getClaimToken());
        Object[] state = jdbc.queryForObject("select status,execution_claim_token,execution_lease_expires_at > clock_timestamp(),execution_attempt_count,last_execution_error_code from knowledge_import_job where id=?",
            (rs, n) -> new Object[] { rs.getString(1), rs.getObject(2, UUID.class), rs.getBoolean(3), rs.getInt(4), rs.getString(5) }, job);
        assertEquals("PROCESSING", state[0]);
        assertEquals(claim.getClaimToken(), state[1]);
        assertEquals(Boolean.TRUE, state[2]);
        assertEquals(1, state[3]);
        assertEquals(null, state[4]);
    }

    @Test
    void permitsOnlyOneConcurrentClaim() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Object> one = executor.submit(() -> claimAfter(start));
            Future<Object> two = executor.submit(() -> claimAfter(start));
            start.countDown();
            Object first = one.get(5, TimeUnit.SECONDS);
            Object second = two.get(5, TimeUnit.SECONDS);
            assertTrue((first instanceof ClaimedKnowledgeImportExecution && second instanceof ImportExecutionNotReadyException)
                || (second instanceof ClaimedKnowledgeImportExecution && first instanceof ImportExecutionNotReadyException));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reclaimsExpiredProcessingLeaseWithNewToken() {
        ClaimedKnowledgeImportExecution first = executions.claimReadyForExecution(general, project, job);
        jdbc.update("update knowledge_import_job set execution_lease_expires_at=clock_timestamp()-interval '1 second' where id=?", job);

        ClaimedKnowledgeImportExecution reclaimed = executions.claimReadyForExecution(general, project, job);

        assertNotEquals(first.getClaimToken(), reclaimed.getClaimToken());
        assertEquals(2, jdbc.queryForObject("select execution_attempt_count from knowledge_import_job where id=?", Integer.class, job).intValue());
        assertEquals("PROCESSING", jdbc.queryForObject("select status from knowledge_import_job where id=?", String.class, job));
    }

    @Test
    void appliesAuthorizationAndProjectJobPrecedenceBeforeClaiming() {
        assertThrows(ForbiddenKnowledgeAdministrationException.class,
            () -> executions.claimReadyForExecution(unassignedProjectAdmin, project, UUID.randomUUID()));
        assertThrows(ProjectNotFoundException.class,
            () -> executions.claimReadyForExecution(general, UUID.randomUUID(), job));
        assertThrows(KnowledgeImportJobNotFoundException.class,
            () -> executions.claimReadyForExecution(general, project, UUID.randomUUID()));
        jdbc.update("update knowledge_import_job set status='VALIDATING' where id=?", job);
        assertThrows(ImportExecutionNotReadyException.class,
            () -> executions.claimReadyForExecution(general, project, job));
    }

    @Test
    void refusesNonExpiredProcessingAndAttemptCap() {
        executions.claimReadyForExecution(general, project, job);
        assertThrows(ImportExecutionNotReadyException.class,
            () -> executions.claimReadyForExecution(general, project, job));
        jdbc.update("update knowledge_import_job set execution_lease_expires_at=clock_timestamp()-interval '1 second',execution_attempt_count=5 where id=?", job);
        assertThrows(ImportExecutionNotReadyException.class,
            () -> executions.claimReadyForExecution(general, project, job));
    }

    @Test
    void projectRoleRevocationCannotCommitBetweenAuthorizedClaimAndJobLock() throws Exception {
        CountDownLatch claimReachedJobLock = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        CountDownLatch revocationStarted = new CountDownLatch(1);
        executions = new JdbcKnowledgeImportExecutionAdapter(pausingBeforeJobLock(claimReachedJobLock, releaseClaim));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ClaimedKnowledgeImportExecution> claim = executor.submit(() -> transactions.execute(status ->
                executions.claimReadyForExecution(projectAdmin, project, job)));
            assertTrue(claimReachedJobLock.await(5, TimeUnit.SECONDS),
                "claim did not reach the post-authorization job-lock seam");

            Future<?> revoke = executor.submit(() -> {
                revocationStarted.countDown();
                return transactions.execute(status -> new JdbcUserProjectAssignmentRepository(jdbc).replaceAsGeneralAdmin(
                    general, projectAdmin, Collections.<UUID>emptyList(), Instant.EPOCH));
            });
            assertTrue(revocationStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> revoke.get(500, TimeUnit.MILLISECONDS));

            releaseClaim.countDown();
            assertNotNull(claim.get(5, TimeUnit.SECONDS));
            revoke.get(5, TimeUnit.SECONDS);
            assertEquals(0, jdbc.queryForObject("select count(*) from user_project_role where user_id=? and project_id=?",
                Integer.class, projectAdmin, project).intValue());
        } finally {
            releaseClaim.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rollsBackClaimAndReclaimsAbandonedProcessingRowsWithoutResettingAttempts() {
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            executions.claimReadyForExecution(general, project, job);
            throw new IllegalStateException("rollback");
        }));
        assertEquals("READY", jdbc.queryForObject("select status from knowledge_import_job where id=?", String.class, job));
        assertEquals(0, jdbc.queryForObject("select execution_attempt_count from knowledge_import_job where id=?", Integer.class, job).intValue());

        ClaimedKnowledgeImportExecution first = executions.claimReadyForExecution(general, project, job);
        jdbc.update("insert into knowledge_import_row(import_job_id,row_number,question,answer,active,status,attempt_count,errors) values (?,?,?,? ,true,'PROCESSING',1,'[]'::jsonb)", job, 1, "Q", "A");
        jdbc.update("update knowledge_import_job set execution_lease_expires_at=clock_timestamp()-interval '1 second' where id=?", job);
        ClaimedKnowledgeImportExecution reclaimed = executions.claimReadyForExecution(general, project, job);

        assertNotEquals(first.getClaimToken(), reclaimed.getClaimToken());
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_import_row where import_job_id=? and status='VALID' and attempt_count=1", Integer.class, job).intValue());
    }

    @Test
    void recoversStrandedRowAfterAttemptCapWhenFirstReclaimSkippedLockedAbandonedRow() throws Exception {
        ClaimedKnowledgeImportExecution oldClaim = executions.claimReadyForExecution(general, project, job);
        jdbc.update("insert into knowledge_import_row(import_job_id,row_number,question,answer,active,status,attempt_count,errors) values (?,?,?,? ,true,'PROCESSING',1,'[]'::jsonb)", job, 1, "Q", "A");
        jdbc.update("update knowledge_import_job set execution_lease_expires_at=clock_timestamp()-interval '1 second',execution_attempt_count=4 where id=?", job);
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseRowLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> lock = executor.submit(() -> holdRowLock(rowLocked, releaseRowLock));
            assertTrue(rowLocked.await(5, TimeUnit.SECONDS), "abandoned row was not locked");

            ClaimedKnowledgeImportExecution cappedReplacement = transactions.execute(status ->
                executions.claimReadyForExecution(general, project, job));
            assertEquals(5, jdbc.queryForObject("select execution_attempt_count from knowledge_import_job where id=?", Integer.class, job).intValue());
            assertEquals("PROCESSING", jdbc.queryForObject("select status from knowledge_import_row where import_job_id=? and row_number=1", String.class, job));
            jdbc.update("update knowledge_import_job set execution_lease_expires_at=clock_timestamp()-interval '1 second' where id=?", job);

            releaseRowLock.countDown();
            lock.get(5, TimeUnit.SECONDS);
            ClaimedKnowledgeImportExecution recovery = transactions.execute(status ->
                executions.claimReadyForExecution(general, project, job));

            assertEquals(5, jdbc.queryForObject("select execution_attempt_count from knowledge_import_job where id=?", Integer.class, job).intValue());
            assertTrue(new JdbcKnowledgeImportExecutionAdapter(jdbc).claimNextValidRow(recovery).isPresent());
            assertTrue(!new JdbcKnowledgeImportExecutionAdapter(jdbc).claimNextValidRow(oldClaim).isPresent());
            assertNotEquals(cappedReplacement.getClaimToken(), recovery.getClaimToken());
        } finally {
            releaseRowLock.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void holdRowLock(CountDownLatch locked, CountDownLatch release) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                "select row_number from knowledge_import_row where import_job_id=? and row_number=1 for update")) {
                statement.setObject(1, job);
                statement.executeQuery();
                locked.countDown();
                await(release);
                connection.commit();
            }
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private JdbcTemplate pausingBeforeJobLock(CountDownLatch reached, CountDownLatch release) {
        return new JdbcTemplate(dataSource) {
            @Override public <T> java.util.List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                if (sql.contains("from knowledge_import_job where id=? and project_id=? for update")) {
                    reached.countDown();
                    await(release);
                }
                return super.query(sql, mapper, args);
            }
        };
    }

    private Object claimAfter(CountDownLatch start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try { return transactions.execute(status -> executions.claimReadyForExecution(general, project, job)); }
        catch (ImportExecutionNotReadyException expected) { return expected; }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting to release claim");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private void readyJob(UUID id, UUID projectId) {
        jdbc.update("insert into knowledge_import_job(id,project_id,file_name,strategy,status,total_rows,valid_rows,invalid_rows,imported_rows,error_summary,created_at) values (?,?,?,?,?,?,?,?,?,?::jsonb,clock_timestamp())",
            id, projectId, "knowledge.csv", "UPSERT", "READY", 1, 1, 0, 0, "[]");
    }

    private UUID user(boolean generalAdmin) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_user(id,email,password_hash,status,is_general_admin) values (?,?,?,'ACTIVE',?)",
            id, id + "@example.com", "hash", generalAdmin);
        return id;
    }
}
