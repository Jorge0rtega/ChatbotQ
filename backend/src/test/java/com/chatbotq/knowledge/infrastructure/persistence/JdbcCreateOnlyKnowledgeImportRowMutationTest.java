package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportRow;
import com.chatbotq.knowledge.application.port.KnowledgeEntryIdentityGenerator;
import com.chatbotq.knowledge.application.port.KnowledgeImportRowMutationPort;
import com.chatbotq.knowledge.application.usecase.ProcessOneCreateOnlyKnowledgeImportRowUseCase;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcCreateOnlyKnowledgeImportRowMutationTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private UUID project;
    private UUID job;
    private UUID token;
    private UUID entry;
    private UUID actor;
    private ProcessOneCreateOnlyKnowledgeImportRowUseCase useCase;

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
        jdbc.update("delete from knowledge_entry");
        jdbc.update("delete from project");
        jdbc.update("delete from admin_user");
        project = UUID.randomUUID();
        job = UUID.randomUUID();
        token = UUID.randomUUID();
        entry = UUID.randomUUID();
        actor = UUID.randomUUID();
        jdbc.update("insert into admin_user(id,email,password_hash,status,is_general_admin) values (?,?,?,'ACTIVE',true)", actor, actor + "@test.invalid", "hash");
        jdbc.update("insert into project(id,name,status) values (?,?,'ACTIVE')", project, "Project");
        jdbc.update("insert into knowledge_import_job(id,project_id,created_by,file_name,strategy,status,total_rows,valid_rows,invalid_rows,imported_rows,error_summary,created_at) values (?,?,?,?,?,?,?,?,?,?,?::jsonb,clock_timestamp())",
            job, project, actor, "knowledge.csv", "CREATE_ONLY", "READY", 1, 1, 0, 0, "[]");
        jdbc.update("update knowledge_import_job set status='PROCESSING',execution_claim_token=?,execution_lease_expires_at=clock_timestamp()+interval '5 minutes' where id=?", token, job);
        jdbc.update("insert into knowledge_import_row(import_job_id,row_number,question,answer,external_id,active,status,errors,attempt_count) values (?,?,?,?,?,?, 'PROCESSING', '[]'::jsonb, 1)",
            job, 2, "  Question  ", "  Answer  ", " external-1 ", false);
        useCase = useCase(entry);
    }

    @Test
    void importsClaimedCreateOnlyRowAsCanonicalPendingEntryWithoutEmbeddingIo() {
        ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result result = useCase.process(claim(), row());

        assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.IMPORTED, result);
        assertEquals("IMPORTED", rowStatus());
        assertEquals(entry, rowEntryId());
        assertEquals("Question", jdbc.queryForObject("select question from knowledge_entry where id=?", String.class, entry));
        assertEquals("Answer", jdbc.queryForObject("select answer from knowledge_entry where id=?", String.class, entry));
        assertEquals("external-1", jdbc.queryForObject("select external_id from knowledge_entry where id=?", String.class, entry));
        assertEquals(false, jdbc.queryForObject("select active from knowledge_entry where id=?", Boolean.class, entry));
        assertEquals("PENDING", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, entry));
        assertEquals(actor, jdbc.queryForObject("select created_by from knowledge_entry where id=?", UUID.class, entry));
        assertEquals(actor, jdbc.queryForObject("select updated_by from knowledge_entry where id=?", UUID.class, entry));
        assertEquals(0, jdbc.queryForObject("select count(*) from provider_usage", Integer.class).intValue());
    }

    @Test
    void marksClaimedRowFailedOnSameProjectExternalIdCollisionWithoutChangingExistingEntry() {
        UUID existing = UUID.randomUUID();
        jdbc.update("insert into knowledge_entry(id,project_id,question,answer,external_id,active,embedding_input_token_upper_bound,created_by,updated_by) values (?,?,?,?,?,?,?,?,?)",
            existing, project, "Existing question", "Existing answer", "external-1", true, 17, actor, actor);

        assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.EXTERNAL_ID_CONFLICT, useCase.process(claim(), row()));
        assertEquals("FAILED", rowStatus());
        assertEquals("knowledge_external_id_conflict", rowError());
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_entry where project_id=?", Integer.class, project).intValue());
        assertEquals("Existing question", jdbc.queryForObject("select question from knowledge_entry where id=?", String.class, existing));
    }

    @Test
    void sameExternalIdInDifferentProjectDoesNotConflict() {
        UUID otherProject = UUID.randomUUID();
        jdbc.update("insert into project(id,name,status) values (?,?,'ACTIVE')", otherProject, "Other");
        jdbc.update("insert into knowledge_entry(id,project_id,question,answer,external_id,active,embedding_input_token_upper_bound,created_by,updated_by) values (?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), otherProject, "Existing question", "Existing answer", "external-1", true, 17, actor, actor);

        assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.IMPORTED, useCase.process(claim(), row()));
        assertEquals("IMPORTED", rowStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_entry where project_id=? and external_id='external-1'", Integer.class, project).intValue());
    }

    @Test
    void staleTokenCreatesNoEntryAndLeavesRowUnchanged() {
        assertStaleWithoutMutation("PROCESSING", () -> jdbc.update("update knowledge_import_job set execution_claim_token=? where id=?", UUID.randomUUID(), job));
    }

    @Test
    void expiredLeaseCreatesNoEntryAndLeavesRowUnchanged() {
        assertStaleWithoutMutation("PROCESSING", () -> jdbc.update("update knowledge_import_job set execution_lease_expires_at=clock_timestamp()-interval '1 second' where id=?", job));
    }

    @Test
    void noLongerProcessingRowCreatesNoEntryAndLeavesRowUnchanged() {
        assertStaleWithoutMutation("VALID", () -> jdbc.update("update knowledge_import_row set status='VALID' where import_job_id=? and row_number=?", job, 2));
    }

    @Test
    void jobWithoutAuditableCreatorCannotCreateOrTerminalizeRow() {
        jdbc.update("update knowledge_import_job set created_by=null where id=?", job);

        assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.STALE, useCase.process(claim(), row()));
        assertEquals("PROCESSING", rowStatus());
        assertEquals(0, knowledgeCount());
    }

    @Test
    void staleCompletionAfterV015ReclaimCannotCreateOrTerminalizeReclaimedRow() {
        UUID replacementToken = UUID.randomUUID();
        jdbc.update("update knowledge_import_job set execution_claim_token=?,execution_lease_expires_at=clock_timestamp()+interval '5 minutes' where id=?", replacementToken, job);

        assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.STALE, useCase.process(claim(), row()));
        assertEquals("PROCESSING", rowStatus());
        assertEquals(0, knowledgeCount());

        assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.IMPORTED,
            useCase(UUID.randomUUID()).process(new ClaimedKnowledgeImportExecution(job, project, "CREATE_ONLY", replacementToken), row()));
        assertEquals("IMPORTED", rowStatus());
        assertEquals(1, knowledgeCount());
    }

    @Test
    void concurrentCompletionOfOneClaimCreatesOneEntryAndTerminalizesOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result> first = pool.submit(process(entry));
            Future<ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result> second = pool.submit(process(UUID.randomUUID()));
            ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result a = first.get();
            ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result b = second.get();

            assertEquals(1, (a == ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.IMPORTED ? 1 : 0)
                + (b == ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.IMPORTED ? 1 : 0));
            assertEquals(1, knowledgeCount());
            assertEquals("IMPORTED", rowStatus());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void committedConcurrentExternalIdInsertTerminalizesTheClaimedRowAsConflict() throws Exception {
        UUID competing = UUID.randomUUID();
        CountDownLatch competingInsertHeld = new CountDownLatch(1);
        CountDownLatch commitCompetingInsert = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> inserter = pool.submit(() -> transactions.execute(status -> {
                jdbc.update("insert into knowledge_entry(id,project_id,question,answer,external_id,active,embedding_input_token_upper_bound,created_by,updated_by) values (?,?,?,?,?,?,?,?,?)",
                    competing, project, "Concurrent", "Answer", "external-1", true, 17, actor, actor);
                competingInsertHeld.countDown();
                await(commitCompetingInsert);
                return null;
            }));
            assertTrue(competingInsertHeld.await(5, TimeUnit.SECONDS));

            Future<ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result> imported = pool.submit(() -> useCase.process(claim(), row()));
            waitForDatabaseLock(imported);
            commitCompetingInsert.countDown();
            inserter.get(5, TimeUnit.SECONDS);

            assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.EXTERNAL_ID_CONFLICT, imported.get(5, TimeUnit.SECONDS));
            assertEquals("FAILED", rowStatus());
            assertEquals("knowledge_external_id_conflict", rowError());
            assertEquals(1, knowledgeCount());
        } finally {
            commitCompetingInsert.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void deletionOfInitiallyConflictingEntryLetsTheClaimedRowImportTheCanonicalEntry() throws Exception {
        UUID existing = UUID.randomUUID();
        jdbc.update("insert into knowledge_entry(id,project_id,question,answer,external_id,active,embedding_input_token_upper_bound,created_by,updated_by) values (?,?,?,?,?,?,?,?,?)",
            existing, project, "Existing", "Answer", "external-1", true, 17, actor, actor);
        CountDownLatch deletionHeld = new CountDownLatch(1);
        CountDownLatch commitDeletion = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> deleter = pool.submit(() -> transactions.execute(status -> {
                jdbc.update("delete from knowledge_entry where id=?", existing);
                deletionHeld.countDown();
                await(commitDeletion);
                return null;
            }));
            assertTrue(deletionHeld.await(5, TimeUnit.SECONDS));

            Future<ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result> imported = pool.submit(() -> useCase.process(claim(), row()));
            waitForDatabaseLock(imported);
            commitDeletion.countDown();
            deleter.get(5, TimeUnit.SECONDS);

            assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.IMPORTED, imported.get(5, TimeUnit.SECONDS));
            assertEquals("IMPORTED", rowStatus());
            assertEquals(entry, rowEntryId());
            assertEquals(1, knowledgeCount());
        } finally {
            commitDeletion.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void oldMutationFencedAfterItsRowLockCannotCreateOrTerminalizeAfterV015Reclaim() throws Exception {
        CountDownLatch oldFenceAndRowLocked = new CountDownLatch(1);
        CountDownLatch releaseOldMutation = new CountDownLatch(1);
        CountDownLatch reclaimLockAttempt = new CountDownLatch(1);
        JdbcKnowledgeAdministrationAdapter mutations = new JdbcKnowledgeAdministrationAdapter(jdbc, () -> {
            oldFenceAndRowLocked.countDown();
            await(releaseOldMutation);
        });
        ProcessOneCreateOnlyKnowledgeImportRowUseCase oldWorker = useCase(mutations, entry);
        JdbcKnowledgeImportExecutionAdapter executions = new JdbcKnowledgeImportExecutionAdapter(jdbc, () -> { },
            pid -> reclaimLockAttempt.countDown());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result> old = pool.submit(() ->
                transactions.execute(status -> oldWorker.process(claim(), row())));
            assertTrue(oldFenceAndRowLocked.await(5, TimeUnit.SECONDS));
            jdbc.update("update knowledge_import_job set execution_lease_expires_at=clock_timestamp()-interval '1 second' where id=?", job);

            Future<ClaimedKnowledgeImportExecution> reclaimed = pool.submit(() ->
                transactions.execute(status -> executions.claimReadyForExecution(actor, project, job)));
            assertTrue(reclaimLockAttempt.await(5, TimeUnit.SECONDS));
            ClaimedKnowledgeImportExecution replacement = reclaimed.get(5, TimeUnit.SECONDS);
            releaseOldMutation.countDown();

            assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.STALE, old.get(5, TimeUnit.SECONDS));
            assertEquals("PROCESSING", rowStatus());
            assertEquals(0, knowledgeCount());
            assertEquals(replacement.getClaimToken(), jdbc.queryForObject(
                "select execution_claim_token from knowledge_import_job where id=?", UUID.class, job));
        } finally {
            releaseOldMutation.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void postInsertFenceLossRollsBackEntryBeforeReplacementClaimImportsIt() throws Exception {
        UUID replacementToken = UUID.randomUUID();
        CountDownLatch oldEntryInsertedBeforeTerminalization = new CountDownLatch(1);
        CountDownLatch releaseOldTerminalization = new CountDownLatch(1);
        JdbcKnowledgeAdministrationAdapter rawMutations = new JdbcKnowledgeAdministrationAdapter(jdbc, () -> { }, () -> {
            oldEntryInsertedBeforeTerminalization.countDown();
            await(releaseOldTerminalization);
            jdbc.update("update knowledge_import_job set execution_claim_token=?,execution_lease_expires_at=clock_timestamp()+interval '5 minutes' where id=?",
                replacementToken, job);
        });
        KnowledgeImportRowMutationPort transactionalMutations = new KnowledgeImportRowMutationPort() {
            @Override
            public Result createOnly(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row,
                                     com.chatbotq.knowledge.application.model.NewKnowledgeEntry entry) {
                try {
                    return transactions.execute(status -> rawMutations.createOnly(execution, row, entry));
                } catch (com.chatbotq.knowledge.application.usecase.StaleKnowledgeImportMutationException stale) {
                    return Result.STALE;
                }
            }
        };
        ProcessOneCreateOnlyKnowledgeImportRowUseCase oldWorker = useCase(transactionalMutations, entry);
        JdbcKnowledgeImportExecutionAdapter executions = new JdbcKnowledgeImportExecutionAdapter(jdbc);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result> old = pool.submit(() -> oldWorker.process(claim(), row()));
            assertTrue(oldEntryInsertedBeforeTerminalization.await(5, TimeUnit.SECONDS));
            releaseOldTerminalization.countDown();

            assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.STALE, old.get(5, TimeUnit.SECONDS));
            assertEquals("PROCESSING", rowStatus());
            assertEquals(0, knowledgeCount());

            jdbc.update("update knowledge_import_job set execution_lease_expires_at=clock_timestamp()-interval '1 second' where id=?", job);
            ClaimedKnowledgeImportExecution replacement = transactions.execute(status ->
                executions.claimReadyForExecution(actor, project, job));
            assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.IMPORTED,
                useCase(UUID.randomUUID()).process(replacement, row()));
            assertEquals("IMPORTED", rowStatus());
            assertEquals(1, knowledgeCount());
        } finally {
            releaseOldTerminalization.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void assertStaleWithoutMutation(String expectedRowStatus, Callable<Integer> invalidate) {
        try { invalidate.call(); } catch (Exception failure) { throw new AssertionError(failure); }
        assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.STALE, useCase.process(claim(), row()));
        assertEquals(expectedRowStatus, rowStatus());
        assertEquals(0, knowledgeCount());
    }

    private Callable<ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result> process(final UUID id) {
        return new Callable<ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result>() {
            @Override public ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result call() { return useCase(id).process(claim(), row()); }
        };
    }

    private ProcessOneCreateOnlyKnowledgeImportRowUseCase useCase(final UUID id) {
        return useCase(new JdbcKnowledgeAdministrationAdapter(jdbc), id);
    }

    private ProcessOneCreateOnlyKnowledgeImportRowUseCase useCase(KnowledgeImportRowMutationPort mutations, final UUID id) {
        return new ProcessOneCreateOnlyKnowledgeImportRowUseCase(mutations, new KnowledgeEntryIdentityGenerator() {
            @Override public UUID newKnowledgeEntryId() { return id; }
        }, Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC), 4000);
    }

    private int knowledgeCount() { return jdbc.queryForObject("select count(*) from knowledge_entry where project_id=?", Integer.class, project); }
    private String rowStatus() { return jdbc.queryForObject("select status from knowledge_import_row where import_job_id=? and row_number=?", String.class, job, 2); }
    private UUID rowEntryId() { return jdbc.queryForObject("select knowledge_entry_id from knowledge_import_row where import_job_id=? and row_number=?", UUID.class, job, 2); }
    private String rowError() { return jdbc.queryForObject("select execution_error_code from knowledge_import_row where import_job_id=? and row_number=?", String.class, job, 2); }
    private ClaimedKnowledgeImportExecution claim() { return new ClaimedKnowledgeImportExecution(job, project, "CREATE_ONLY", token); }
    private ClaimedKnowledgeImportRow row() { return new ClaimedKnowledgeImportRow(job, 2, "  Question  ", "  Answer  ", " external-1 ", false); }

    private void waitForDatabaseLock(Future<?> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (future.isDone()) {
                future.get(1, TimeUnit.SECONDS);
                throw new AssertionError("mutation completed before waiting on the concurrent transaction");
            }
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock'", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20L);
        }
        throw new AssertionError("mutation did not wait on the concurrent transaction");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for concurrent transaction");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
