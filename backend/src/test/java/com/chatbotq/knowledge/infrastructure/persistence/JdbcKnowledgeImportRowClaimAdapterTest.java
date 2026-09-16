package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportRow;
import com.chatbotq.knowledge.application.port.KnowledgeImportExecutionPort;
import com.chatbotq.knowledge.application.port.KnowledgeImportRowClaimPort;
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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcKnowledgeImportRowClaimAdapterTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private KnowledgeImportRowClaimPort claims;
    private KnowledgeImportExecutionPort executions;
    private UUID general;
    private UUID project;
    private UUID job;
    private UUID token;

    @BeforeAll
    static void database() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void seed() {
        jdbc.update("delete from knowledge_import_row");
        jdbc.update("delete from knowledge_import_job");
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        general = user(true);
        project = UUID.randomUUID();
        job = UUID.randomUUID();
        token = UUID.randomUUID();
        jdbc.update("insert into project(id,name,status) values (?,?,'ACTIVE')", project, "Project");
        jdbc.update("insert into knowledge_import_job(id,project_id,file_name,strategy,status,total_rows,valid_rows,invalid_rows,imported_rows,error_summary,created_at) values (?,?,?,?,?,?,?,?,?,?::jsonb,clock_timestamp())",
            job, project, "knowledge.csv", "UPSERT", "READY", 3, 3, 0, 0, "[]");
        jdbc.update("update knowledge_import_job set status='PROCESSING',execution_claim_token=?,execution_lease_expires_at=clock_timestamp()+interval '5 minutes' where id=?", token, job);
        claims = new JdbcKnowledgeImportExecutionAdapter(jdbc);
        executions = new JdbcKnowledgeImportExecutionAdapter(jdbc);
    }

    @Test
    void claimsLowestValidRowOneAtATimeAndIncrementsOnlyThatAttempt() throws Exception {
        row(20, "VALID");
        row(10, "VALID");

        Optional<ClaimedKnowledgeImportRow> first = claim();
        Optional<ClaimedKnowledgeImportRow> second = claim();

        assertTrue(first.isPresent());
        assertEquals(10, first.get().getRowNumber());
        assertTrue(second.isPresent());
        assertEquals(20, second.get().getRowNumber());
        assertEquals("PROCESSING", status(10));
        assertEquals("PROCESSING", status(20));
        assertEquals(1, attempts(10));
        assertEquals(1, attempts(20));
    }

    @Test
    void concurrentClaimersClaimDifferentRowsWithoutDuplicateAttempts() throws Exception {
        row(10, "VALID");
        row(20, "VALID");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<ClaimedKnowledgeImportRow>> first = workers.submit(() -> claimWhenReleased(ready, start));
            Future<Optional<ClaimedKnowledgeImportRow>> second = workers.submit(() -> claimWhenReleased(ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            Optional<ClaimedKnowledgeImportRow> firstClaim = first.get(5, TimeUnit.SECONDS);
            Optional<ClaimedKnowledgeImportRow> secondClaim = second.get(5, TimeUnit.SECONDS);

            assertTrue(firstClaim.isPresent());
            assertTrue(secondClaim.isPresent());
            assertFalse(firstClaim.get().getRowNumber() == secondClaim.get().getRowNumber());
            assertEquals(1, attempts(10));
            assertEquals(1, attempts(20));
        } finally {
            start.countDown();
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void staleTokenAndExpiredLeaseReturnNoRowWithoutChangingValidRows() {
        row(10, "VALID");
        UUID originalToken = token;
        token = UUID.randomUUID();

        assertFalse(claim().isPresent());
        assertEquals("VALID", status(10));
        assertEquals(0, attempts(10));

        token = originalToken;
        jdbc.update("update knowledge_import_job set execution_lease_expires_at=clock_timestamp()-interval '1 second' where id=?", job);
        assertFalse(claim().isPresent());
        assertEquals("VALID", status(10));
        assertEquals(0, attempts(10));
    }

    @Test
    void refusesRealJobClaimFromDifferentProjectWithoutMutatingValidRow() {
        row(10, "VALID");

        Optional<ClaimedKnowledgeImportRow> claimed = claims.claimNextValidRow(new ClaimedKnowledgeImportExecution(
            job, UUID.randomUUID(), "UPSERT", token));

        assertFalse(claimed.isPresent());
        assertEquals("VALID", status(10));
        assertEquals(0, attempts(10));
    }

    @Test
    void staleClaimCannotMutateRowAfterItsLeaseIsReclaimedBetweenValidationAndUpdate() throws Exception {
        row(10, "VALID");
        UUID staleToken = token;
        UUID freshToken = UUID.randomUUID();
        CountDownLatch oldClaimValidated = new CountDownLatch(1);
        CountDownLatch releaseOldClaim = new CountDownLatch(1);
        claims = new JdbcKnowledgeImportExecutionAdapter(jdbc, () -> {
            oldClaimValidated.countDown();
            await(releaseOldClaim);
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<ClaimedKnowledgeImportRow>> oldClaim = worker.submit(() -> transactions.execute(status -> claim()));
            assertTrue(oldClaimValidated.await(5, TimeUnit.SECONDS), "old claimant did not validate the parent job");

            assertEquals(1, jdbc.update("update knowledge_import_job set execution_lease_expires_at=clock_timestamp()-interval '1 second' "
                + "where id=? and project_id=? and status='PROCESSING' and execution_claim_token=?", job, project, staleToken));
            assertEquals(1, jdbc.update("update knowledge_import_job set execution_claim_token=?, "
                + "execution_lease_expires_at=clock_timestamp()+interval '5 minutes', execution_attempt_count=execution_attempt_count+1 "
                + "where id=? and project_id=? and status='PROCESSING' and execution_claim_token=? "
                + "and execution_lease_expires_at<=clock_timestamp()", freshToken, job, project, staleToken));

            releaseOldClaim.countDown();
            assertFalse(oldClaim.get(5, TimeUnit.SECONDS).isPresent());
            assertEquals("VALID", status(10));
            assertEquals(0, attempts(10));
            assertEquals(freshToken, jdbc.queryForObject("select execution_claim_token from knowledge_import_job where id=?", UUID.class, job));
            assertTrue(jdbc.queryForObject("select execution_lease_expires_at>clock_timestamp() from knowledge_import_job where id=?", Boolean.class, job));
        } finally {
            releaseOldClaim.countDown();
            worker.shutdownNow();
            worker.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void staleClaimCannotMutateRowAfterProductionReclaimBetweenValidationAndUpdate() throws Exception {
        row(10, "VALID");
        UUID staleToken = token;
        CountDownLatch oldClaimValidated = new CountDownLatch(1);
        CountDownLatch releaseOldClaim = new CountDownLatch(1);
        CountDownLatch reclaimerBackendIdentified = new CountDownLatch(1);
        AtomicReference<Integer> oldClaimBackendPid = new AtomicReference<>();
        AtomicReference<Integer> reclaimerBackendPid = new AtomicReference<>();
        claims = new JdbcKnowledgeImportExecutionAdapter(jdbc, () -> {
            oldClaimBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            oldClaimValidated.countDown();
            await(releaseOldClaim);
        });
        executions = new JdbcKnowledgeImportExecutionAdapter(jdbc, () -> { }, pid -> {
            reclaimerBackendPid.set(pid);
            reclaimerBackendIdentified.countDown();
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<ClaimedKnowledgeImportRow>> oldClaim = workers.submit(() -> transactions.execute(status -> claim()));
            assertTrue(oldClaimValidated.await(5, TimeUnit.SECONDS), "old claimant did not validate the parent job");

            assertEquals(1, jdbc.update("update knowledge_import_job set execution_lease_expires_at=clock_timestamp()-interval '1 second' "
                + "where id=? and project_id=? and status='PROCESSING' and execution_claim_token=?", job, project, staleToken));
            Future<ClaimedKnowledgeImportExecution> reclaimed = workers.submit(() ->
                transactions.execute(status -> executions.claimReadyForExecution(general, project, job)));
            assertTrue(reclaimerBackendIdentified.await(5, TimeUnit.SECONDS),
                "production reclaimer did not identify its transaction backend at the job-lock seam");
            assertTrue(waitingForKnowledgeImportJobLock(reclaimerBackendPid.get(), oldClaimBackendPid.get()),
                "production reclaimer was not observably waiting on the knowledge_import_job row lock");

            releaseOldClaim.countDown();
            assertFalse(oldClaim.get(5, TimeUnit.SECONDS).isPresent());
            ClaimedKnowledgeImportExecution freshClaim = reclaimed.get(5, TimeUnit.SECONDS);
            assertNotNull(freshClaim);
            assertNotEquals(staleToken, freshClaim.getClaimToken());
            assertEquals("VALID", status(10));
            assertEquals(0, attempts(10));
            assertEquals(freshClaim.getClaimToken(), jdbc.queryForObject(
                "select execution_claim_token from knowledge_import_job where id=?", UUID.class, job));
        } finally {
            releaseOldClaim.countDown();
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void neverClaimsOrMutatesNonValidLifecycleRows() {
        row(10, "INVALID");
        row(20, "IMPORTED");
        row(30, "FAILED");
        row(40, "PROCESSING");

        assertFalse(claim().isPresent());

        assertUnchanged(10, "INVALID");
        assertUnchanged(20, "IMPORTED");
        assertUnchanged(30, "FAILED");
        assertUnchanged(40, "PROCESSING");
    }

    @Test
    void preservesPreviewInvalidRowsWhileClaimingValidRows() {
        row(10, "INVALID");
        row(20, "VALID");

        Optional<ClaimedKnowledgeImportRow> claimed = claim();

        assertTrue(claimed.isPresent());
        assertEquals(20, claimed.get().getRowNumber());
        assertEquals("INVALID", status(10));
        assertEquals(0, attempts(10));
        assertEquals("PROCESSING", status(20));
        assertEquals(1, attempts(20));
    }

    private Optional<ClaimedKnowledgeImportRow> claimWhenReleased(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return claim();
    }

    private Optional<ClaimedKnowledgeImportRow> claim() {
        return claims.claimNextValidRow(new ClaimedKnowledgeImportExecution(job, project, "UPSERT", token));
    }

    private void row(int rowNumber, String status) {
        if ("IMPORTED".equals(status)) {
            UUID entry = UUID.randomUUID();
            jdbc.update("insert into knowledge_entry(id,project_id,question,answer,active,embedding_input_token_upper_bound) values (?,?,?,?,true,?)",
                entry, project, "Imported question " + rowNumber, "Imported answer " + rowNumber, 1);
            jdbc.update("insert into knowledge_import_row(import_job_id,row_number,question,answer,active,status,errors,knowledge_entry_id) values (?,?,?,?,?,?,?::jsonb,?)",
                job, rowNumber, "Question " + rowNumber, "Answer " + rowNumber, true, status, "[]", entry);
            return;
        }
        jdbc.update("insert into knowledge_import_row(import_job_id,row_number,question,answer,active,status,errors) values (?,?,?,?,?,?,?::jsonb)",
            job, rowNumber, "Question " + rowNumber, "Answer " + rowNumber, true, status, "[]");
    }

    private String status(int rowNumber) {
        return jdbc.queryForObject("select status from knowledge_import_row where import_job_id=? and row_number=?", String.class, job, rowNumber);
    }

    private UUID user(boolean generalAdmin) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_user(id,email,password_hash,status,is_general_admin) values (?,?,?,'ACTIVE',?)",
            id, id + "@example.com", "hash", generalAdmin);
        return id;
    }

    private int attempts(int rowNumber) {
        return jdbc.queryForObject("select attempt_count from knowledge_import_row where import_job_id=? and row_number=?", Integer.class, job, rowNumber).intValue();
    }

    private void assertUnchanged(int rowNumber, String expectedStatus) {
        assertEquals(expectedStatus, status(rowNumber));
        assertEquals(0, attempts(rowNumber));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting to release old claimant");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private boolean waitingForKnowledgeImportJobLock(Integer backendPid, Integer blockingBackendPid) {
        if (backendPid == null || blockingBackendPid == null) return false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbc.queryForObject(
                "select count(*) from pg_stat_activity waiter "
                    + "join pg_locks waiting on waiting.pid=waiter.pid and not waiting.granted and waiting.locktype='transactionid' "
                    + "join pg_locks blocker on blocker.locktype='transactionid' and blocker.transactionid=waiting.transactionid and blocker.granted "
                    + "where waiter.pid=? and blocker.pid=? and waiter.wait_event_type='Lock' "
                    + "and exists (select 1 from pg_locks waiter_relation where waiter_relation.pid=waiter.pid "
                    + "and waiter_relation.granted and waiter_relation.relation='knowledge_import_job'::regclass) "
                    + "and exists (select 1 from pg_locks blocker_relation where blocker_relation.pid=blocker.pid "
                    + "and blocker_relation.granted and blocker_relation.relation='knowledge_import_job'::regclass)",
                Integer.class, backendPid, blockingBackendPid);
            if (waiters != null && waiters.intValue() == 1) return true;
        }
        return false;
    }
}
