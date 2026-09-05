package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;
import com.chatbotq.knowledge.application.port.KnowledgeEmbeddingProcessingPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcKnowledgeEmbeddingProcessingAdapterTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private KnowledgeEmbeddingProcessingPort processing;

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
        jdbc.update("delete from knowledge_entry");
        jdbc.update("delete from project");
        processing = new JdbcKnowledgeEmbeddingProcessingAdapter(jdbc);
    }

    @Test
    void claimsOnePendingEntryByRevisionAndMakesItProcessingExactlyOnce() {
        UUID entryId = pending("What are your hours?", Instant.parse("2026-09-04T12:00:00Z"));

        Optional<ClaimedKnowledgeEmbedding> claimed = processing.claimOnePending();
        Optional<ClaimedKnowledgeEmbedding> second = processing.claimOnePending();

        assertTrue(claimed.isPresent());
        assertEquals(entryId, claimed.get().getEntryId());
        assertEquals(1L, claimed.get().getEmbeddingRevision());
        assertEquals("What are your hours?", claimed.get().getQuestion());
        assertFalse(second.isPresent());
        assertEquals("PROCESSING", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, entryId));
        assertEquals(1, jdbc.queryForObject("select embedding_attempt_count from knowledge_entry where id=?", Integer.class, entryId).intValue());
        assertTrue(jdbc.queryForObject("select embedding_last_attempt_at is not null from knowledge_entry where id=?", Boolean.class, entryId));
    }

    @Test
    void marksMatchingProcessingRevisionReadyWithVectorAndClearsPreviousFailureDiagnostics() {
        UUID entryId = pending("Ready question", Instant.parse("2026-09-04T12:00:00Z"));
        ClaimedKnowledgeEmbedding claim = processing.claimOnePending().get();
        jdbc.update("update knowledge_entry set embedding_last_error_code='TRANSIENT',embedding_last_error_message='old error' where id=?", entryId);

        assertTrue(processing.markReady(claim, vector()));

        assertEquals("READY", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, entryId));
        assertEquals(1536, jdbc.queryForObject("select vector_dims(embedding) from knowledge_entry where id=?", Integer.class, entryId).intValue());
        assertTrue(jdbc.queryForObject("select embedded_at is not null from knowledge_entry where id=?", Boolean.class, entryId));
        assertEquals(null, jdbc.queryForObject("select embedding_last_error_code from knowledge_entry where id=?", String.class, entryId));
        assertEquals(null, jdbc.queryForObject("select embedding_last_error_message from knowledge_entry where id=?", String.class, entryId));
    }

    @Test
    void marksMatchingProcessingRevisionFailedWithoutVectorAndWithSafeDiagnostics() {
        UUID entryId = pending("Failing question", Instant.parse("2026-09-04T12:00:00Z"));
        ClaimedKnowledgeEmbedding claim = processing.claimOnePending().get();

        assertTrue(processing.markFailed(claim, "PROVIDER_TRANSIENT", "Embedding provider temporarily unavailable"));

        assertEquals("FAILED", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, entryId));
        assertEquals(null, jdbc.queryForObject("select embedding from knowledge_entry where id=?", Object.class, entryId));
        assertEquals(null, jdbc.queryForObject("select embedded_at from knowledge_entry where id=?", Object.class, entryId));
        assertEquals(1, jdbc.queryForObject("select embedding_attempt_count from knowledge_entry where id=?", Integer.class, entryId).intValue());
        assertTrue(jdbc.queryForObject("select embedding_last_attempt_at is not null from knowledge_entry where id=?", Boolean.class, entryId));
        assertEquals("PROVIDER_TRANSIENT", jdbc.queryForObject("select embedding_last_error_code from knowledge_entry where id=?", String.class, entryId));
        assertEquals("Embedding provider temporarily unavailable", jdbc.queryForObject("select embedding_last_error_message from knowledge_entry where id=?", String.class, entryId));
    }

    @Test
    void staleClaimCannotMarkNewerPendingRevisionFailed() {
        UUID entryId = pending("Original question", Instant.parse("2026-09-04T12:00:00Z"));
        ClaimedKnowledgeEmbedding oldClaim = processing.claimOnePending().get();
        jdbc.update("update knowledge_entry set question='Updated question',embedding_status='PENDING',embedding_revision=2,"
            + "embedding_attempt_count=0,embedding_last_attempt_at=null,embedding_last_error_code=null,embedding_last_error_message=null where id=?", entryId);

        assertFalse(processing.markFailed(oldClaim, "PROVIDER_TRANSIENT", "Embedding provider temporarily unavailable"));

        assertEquals("PENDING", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, entryId));
        assertEquals(2L, jdbc.queryForObject("select embedding_revision from knowledge_entry where id=?", Long.class, entryId).longValue());
        assertEquals(null, jdbc.queryForObject("select embedding_last_error_code from knowledge_entry where id=?", String.class, entryId));
    }

    @Test
    void rejectsUnsafeFailureDiagnosticsBeforeDatabaseWrite() {
        UUID entryId = pending("Validation question", Instant.parse("2026-09-04T12:00:00Z"));
        ClaimedKnowledgeEmbedding claim = processing.claimOnePending().get();

        assertThrows(IllegalArgumentException.class, () -> processing.markFailed(claim, "provider-transient", "safe message"));
        assertThrows(IllegalArgumentException.class, () -> processing.markFailed(claim, "PROVIDER_TRANSIENT", "line one\nline two"));
        assertThrows(IllegalArgumentException.class, () -> processing.markFailed(claim, "PROVIDER_TRANSIENT",
            "OpenAI 401 api_key=sk-real-secret"));

        assertEquals("PROCESSING", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, entryId));
        assertEquals(null, jdbc.queryForObject("select embedding_last_error_code from knowledge_entry where id=?", String.class, entryId));
    }

    @Test
    void staleClaimCannotOverwriteNewerPendingRevision() {
        UUID entryId = pending("Original question", Instant.parse("2026-09-04T12:00:00Z"));
        ClaimedKnowledgeEmbedding oldClaim = processing.claimOnePending().get();
        jdbc.update("update knowledge_entry set question='Updated question',embedding_status='PENDING',embedding_revision=2,"
            + "embedding_attempt_count=0,embedding_last_attempt_at=null,embedding_last_error_code=null,embedding_last_error_message=null where id=?", entryId);

        assertFalse(processing.markReady(oldClaim, vector()));

        assertEquals("PENDING", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, entryId));
        assertEquals(2L, jdbc.queryForObject("select embedding_revision from knowledge_entry where id=?", Long.class, entryId).longValue());
        assertEquals(null, jdbc.queryForObject("select embedding from knowledge_entry where id=?", Object.class, entryId));
    }

    @Test
    void rejectsVectorsWithWrongDimensionsOrNonFiniteValuesBeforeDatabaseWrite() {
        ClaimedKnowledgeEmbedding claim = processing.claimOnePending().orElse(null);
        if (claim == null) {
            UUID entryId = pending("Validation question", Instant.parse("2026-09-04T12:00:00Z"));
            claim = processing.claimOnePending().get();
            assertTrue(entryId.equals(claim.getEntryId()));
        }
        float[] nonFinite = vector();
        nonFinite[1] = Float.NaN;

        final ClaimedKnowledgeEmbedding finalClaim = claim;
        assertThrows(IllegalArgumentException.class, () -> processing.markReady(finalClaim, new float[1535]));
        assertThrows(IllegalArgumentException.class, () -> processing.markReady(finalClaim, nonFinite));
        assertEquals("PROCESSING", jdbc.queryForObject("select embedding_status from knowledge_entry where id=?", String.class, claim.getEntryId()));
    }

    @Test
    void concurrentClaimersCannotClaimTheSamePendingRevision() throws Exception {
        UUID entryId = pending("Concurrent question", Instant.parse("2026-09-04T12:00:00Z"));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<ClaimedKnowledgeEmbedding>> first = workers.submit(() -> claimWhenReleased(ready, start));
            Future<Optional<ClaimedKnowledgeEmbedding>> second = workers.submit(() -> claimWhenReleased(ready, start));
            ready.await();
            start.countDown();

            Optional<ClaimedKnowledgeEmbedding> firstClaim = first.get();
            Optional<ClaimedKnowledgeEmbedding> secondClaim = second.get();
            int claims = (firstClaim.isPresent() ? 1 : 0) + (secondClaim.isPresent() ? 1 : 0);

            ClaimedKnowledgeEmbedding claimed = firstClaim.isPresent() ? firstClaim.get() : secondClaim.get();
            assertEquals(1, claims);
            assertEquals(entryId, claimed.getEntryId());
            assertEquals(1, jdbc.queryForObject("select embedding_attempt_count from knowledge_entry where id=?", Integer.class, entryId).intValue());
        } finally {
            workers.shutdownNow();
        }
    }

    private Optional<ClaimedKnowledgeEmbedding> claimWhenReleased(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return processing.claimOnePending();
    }

    private static float[] vector() {
        float[] values = new float[1536];
        values[0] = 0.5f;
        return values;
    }

    private UUID pending(String question, Instant updatedAt) {
        UUID projectId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        java.sql.Timestamp timestamp = java.sql.Timestamp.from(updatedAt);
        jdbc.update("insert into project(id,name,status,created_at,updated_at) values (?,?,'ACTIVE',?,?)",
            projectId, "Project " + projectId, timestamp, timestamp);
        jdbc.update("insert into knowledge_entry(id,project_id,question,answer,embedding_status,embedding_revision,created_at,updated_at) values (?,?,?,?,'PENDING',1,?,?)",
            entryId, projectId, question, "Answer", timestamp, timestamp);
        return entryId;
    }
}
