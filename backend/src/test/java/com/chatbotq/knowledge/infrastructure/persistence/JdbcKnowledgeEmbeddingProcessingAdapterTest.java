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
