package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.infrastructure.persistence.JdbcEmbeddingBudgetReservationAdapter;
import com.chatbotq.knowledge.infrastructure.persistence.JdbcKnowledgeEmbeddingProcessingAdapter;
import com.chatbotq.rag.application.port.EmbeddingProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessOneKnowledgeEmbeddingPostgresIntegrationTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void database() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
            postgres.getPassword()));
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clean() {
        jdbc.update("delete from embedding_budget_reservation");
        jdbc.update("delete from knowledge_entry");
        jdbc.update("delete from project");
    }

    @Test
    void invalidProviderResponseEndsTheClaimFailedWithTheFixedDiagnostic() {
        UUID entryId = pending("Invalid vector question");
        AtomicInteger providerInvocations = new AtomicInteger();
        ProcessOneKnowledgeEmbeddingUseCase useCase = new ProcessOneKnowledgeEmbeddingUseCase(
            new JdbcKnowledgeEmbeddingProcessingAdapter(jdbc), reservations(), input -> {
                providerInvocations.incrementAndGet();
                return null;
            });

        assertEquals(ProcessOneKnowledgeEmbeddingUseCase.Result.FAILED, useCase.processOne());
        assertEquals(1, providerInvocations.get());
        assertEquals(1, jdbc.queryForObject("select embedding_attempt_count from knowledge_entry where id=?", Integer.class, entryId).intValue());
        assertEquals("FAILED", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, entryId));
        assertEquals("PROVIDER_INVALID_RESPONSE", jdbc.queryForObject(
            "select embedding_last_error_code from knowledge_entry where id=?", String.class, entryId));
        assertEquals("Embedding provider returned an invalid response", jdbc.queryForObject(
            "select embedding_last_error_message from knowledge_entry where id=?", String.class, entryId));
        assertEquals(null, jdbc.queryForObject("select embedding from knowledge_entry where id=?", Object.class, entryId));
        assertEquals(1, jdbc.queryForObject("select count(*) from embedding_budget_reservation where knowledge_entry_id=?", Integer.class, entryId).intValue());
    }

    @Test
    void deniedBudgetDoesNotRecordAProviderAttempt() {
        UUID entryId = pending("Budget denied question");
        Timestamp priorAttemptAt = Timestamp.from(Instant.parse("2026-09-04T12:00:00Z"));
        jdbc.update("update knowledge_entry set embedding_attempt_count=3,embedding_last_attempt_at=? where id=?",
            priorAttemptAt, entryId);
        AtomicInteger providerInvocations = new AtomicInteger();
        EmbeddingProvider provider = input -> {
            providerInvocations.incrementAndGet();
            return new float[1536];
        };
        ProcessOneKnowledgeEmbeddingUseCase useCase = new ProcessOneKnowledgeEmbeddingUseCase(
            new JdbcKnowledgeEmbeddingProcessingAdapter(jdbc),
            new JdbcEmbeddingBudgetReservationAdapter(jdbc, 100, 250000, java.math.BigDecimal.ZERO), provider);

        assertEquals(ProcessOneKnowledgeEmbeddingUseCase.Result.FAILED, useCase.processOne());
        assertEquals(0, providerInvocations.get());
        assertEquals("FAILED", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, entryId));
        assertEquals(3, jdbc.queryForObject("select embedding_attempt_count from knowledge_entry where id=?", Integer.class, entryId).intValue());
        assertEquals(priorAttemptAt, jdbc.queryForObject("select embedding_last_attempt_at from knowledge_entry where id=?", Timestamp.class, entryId));
        assertEquals("EMBEDDING_BUDGET_LIMIT_REACHED", jdbc.queryForObject(
            "select embedding_last_error_code from knowledge_entry where id=?", String.class, entryId));
    }

    @Test
    void questionRevisionCanBeInvalidatedWhileProviderIsBlockedAndLateResultStaysStale() throws Exception {
        UUID entryId = pending("Original question");
        BlockingProvider provider = new BlockingProvider();
        ProcessOneKnowledgeEmbeddingUseCase useCase = new ProcessOneKnowledgeEmbeddingUseCase(
            new JdbcKnowledgeEmbeddingProcessingAdapter(jdbc), reservations(), provider);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<ProcessOneKnowledgeEmbeddingUseCase.Result> result = worker.submit(useCase::processOne);
            assertTrue(provider.started.await(5, TimeUnit.SECONDS));

            assertEquals(1, jdbc.update("update knowledge_entry set question=?,embedding_status='PENDING',"
                    + "embedding_revision=2,embedding_processing_claim_token=null,embedding_processing_lease_expires_at=null,"
                    + "embedding_attempt_count=0,embedding_last_attempt_at=null,"
                    + "embedding_last_error_code=null,embedding_last_error_message=null,updated_at=current_timestamp where id=?",
                "Revised question", entryId));

            provider.release.countDown();
            assertEquals(ProcessOneKnowledgeEmbeddingUseCase.Result.STALE, result.get(5, TimeUnit.SECONDS));
            assertEquals("PENDING", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, entryId));
            assertEquals(2L, jdbc.queryForObject("select embedding_revision from knowledge_entry where id=?", Long.class, entryId).longValue());
            assertEquals(null, jdbc.queryForObject("select embedding from knowledge_entry where id=?", Object.class, entryId));
            assertEquals(1, jdbc.queryForObject("select count(*) from embedding_budget_reservation where knowledge_entry_id=?", Integer.class, entryId).intValue());
        } finally {
            provider.release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void metadataOnlyEditDoesNotInvalidateTheClaimAndTheLateResultBecomesReady() throws Exception {
        UUID entryId = pending("Stable question");
        BlockingProvider provider = new BlockingProvider();
        ProcessOneKnowledgeEmbeddingUseCase useCase = new ProcessOneKnowledgeEmbeddingUseCase(
            new JdbcKnowledgeEmbeddingProcessingAdapter(jdbc),
            new JdbcEmbeddingBudgetReservationAdapter(jdbc, 100, 250000, java.math.BigDecimal.valueOf(20)), provider);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<ProcessOneKnowledgeEmbeddingUseCase.Result> result = worker.submit(useCase::processOne);
            assertTrue(provider.started.await(5, TimeUnit.SECONDS));

            assertEquals(1, jdbc.update("update knowledge_entry set answer=?,active=false,version=version+1,"
                    + "updated_at=current_timestamp where id=?", "Updated answer only", entryId));

            provider.release.countDown();
            assertEquals(ProcessOneKnowledgeEmbeddingUseCase.Result.READY, result.get(5, TimeUnit.SECONDS));
            assertEquals("READY", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, entryId));
            assertEquals(1L, jdbc.queryForObject("select embedding_revision from knowledge_entry where id=?", Long.class, entryId).longValue());
            assertFalse(jdbc.queryForObject("select active from knowledge_entry where id=?", Boolean.class, entryId));
            assertEquals(1536, jdbc.queryForObject("select vector_dims(embedding) from knowledge_entry where id=?", Integer.class, entryId).intValue());
            assertEquals(1, jdbc.queryForObject("select count(*) from embedding_budget_reservation where knowledge_entry_id=?", Integer.class, entryId).intValue());
        } finally {
            provider.release.countDown();
            worker.shutdownNow();
        }
    }

    private UUID pending(String question) {
        UUID projectId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-09-05T12:00:00Z"));
        jdbc.update("insert into project(id,name,status,created_at,updated_at) values (?,?,'ACTIVE',?,?)",
            projectId, "Project " + projectId, now, now);
        jdbc.update("insert into knowledge_entry(id,project_id,question,answer,embedding_status,embedding_revision,embedding_input_token_upper_bound,created_at,updated_at) "
                + "values (?,?,?,?,'PENDING',1,1,?,?)",
            entryId, projectId, question, "Answer", now, now);
        return entryId;
    }

    private JdbcEmbeddingBudgetReservationAdapter reservations() {
        return new JdbcEmbeddingBudgetReservationAdapter(jdbc, 100, 250000, java.math.BigDecimal.valueOf(20));
    }

    private static final class BlockingProvider implements EmbeddingProvider {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public float[] embed(String input) throws java.io.IOException {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("test provider not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("test provider interrupted", interrupted);
            }
            float[] vector = new float[1536];
            vector[0] = 0.5f;
            return vector;
        }
    }
}
