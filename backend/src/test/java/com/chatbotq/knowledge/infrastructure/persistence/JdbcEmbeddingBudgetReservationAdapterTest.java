package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;
import com.chatbotq.knowledge.application.port.EmbeddingBudgetReservationPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcEmbeddingBudgetReservationAdapterTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void database() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @AfterAll
    static void stop() { if (postgres != null) postgres.stop(); }

    @BeforeEach
    void clean() {
        jdbc.update("delete from embedding_budget_reservation");
        jdbc.update("delete from knowledge_entry");
        jdbc.update("delete from project");
    }

    @Test
    void reservesOnceForTheSameClaimWithoutDoubleCharging() {
        ClaimedKnowledgeEmbedding claim = processingClaim(10);
        JdbcEmbeddingBudgetReservationAdapter reservations = adapter(2, 100, "1");

        assertEquals(EmbeddingBudgetReservationPort.Decision.RESERVED, reservations.reserve(claim).getDecision());
        assertEquals(EmbeddingBudgetReservationPort.Decision.RESERVED, reservations.reserve(claim).getDecision());
        assertEquals(1, jdbc.queryForObject("select count(*) from embedding_budget_reservation", Integer.class).intValue());
        assertEquals(new BigDecimal("0.00000020"), jdbc.queryForObject(
            "select reserved_cost_usd from embedding_budget_reservation", BigDecimal.class));
    }

    @Test
    void rejectsWhenDailyEntryCapacityIsAlreadyReserved() {
        JdbcEmbeddingBudgetReservationAdapter reservations = adapter(1, 100, "1");

        assertEquals(EmbeddingBudgetReservationPort.Decision.RESERVED, reservations.reserve(processingClaim(1)).getDecision());
        assertEquals(EmbeddingBudgetReservationPort.Decision.DENIED, reservations.reserve(processingClaim(1)).getDecision());
        assertEquals(1, jdbc.queryForObject("select count(*) from embedding_budget_reservation", Integer.class).intValue());
    }

    @Test
    void rejectsWhenDailyTokenCapacityIsAlreadyReserved() {
        JdbcEmbeddingBudgetReservationAdapter reservations = adapter(10, 10, "1");

        assertEquals(EmbeddingBudgetReservationPort.Decision.RESERVED, reservations.reserve(processingClaim(7)).getDecision());
        assertEquals(EmbeddingBudgetReservationPort.Decision.DENIED, reservations.reserve(processingClaim(4)).getDecision());
        assertEquals(7L, jdbc.queryForObject("select sum(input_token_upper_bound) from embedding_budget_reservation", Long.class).longValue());
    }

    @Test
    void rejectsWhenMonthlyHardCostCapacityIsAlreadyReserved() {
        JdbcEmbeddingBudgetReservationAdapter reservations = adapter(10, 200000000, "1.00000000");

        assertEquals(EmbeddingBudgetReservationPort.Decision.RESERVED, reservations.reserve(processingClaim(50000000)).getDecision());
        assertEquals(EmbeddingBudgetReservationPort.Decision.DENIED, reservations.reserve(processingClaim(1)).getDecision());
        assertEquals(new BigDecimal("1.00000000"), jdbc.queryForObject(
            "select sum(reserved_cost_usd) from embedding_budget_reservation", BigDecimal.class));
    }

    @Test
    void concurrentDistinctClaimsWithCapacityForOneReserveExactlyOneWithoutOverage() throws Exception {
        JdbcEmbeddingBudgetReservationAdapter reservations = adapter(1, 100, "1");
        ClaimedKnowledgeEmbedding first = processingClaim(1);
        ClaimedKnowledgeEmbedding second = processingClaim(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<EmbeddingBudgetReservationPort.Decision> one = workers.submit(() -> reserveWhenReleased(reservations, first, ready, start));
            Future<EmbeddingBudgetReservationPort.Decision> two = workers.submit(() -> reserveWhenReleased(reservations, second, ready, start));
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            int reserved = (one.get(5, TimeUnit.SECONDS) == EmbeddingBudgetReservationPort.Decision.RESERVED ? 1 : 0)
                + (two.get(5, TimeUnit.SECONDS) == EmbeddingBudgetReservationPort.Decision.RESERVED ? 1 : 0);
            assertEquals(1, reserved);
            assertEquals(1, jdbc.queryForObject("select count(*) from embedding_budget_reservation", Integer.class).intValue());
            assertEquals(1L, jdbc.queryForObject("select sum(input_token_upper_bound) from embedding_budget_reservation", Long.class).longValue());
        } finally { workers.shutdownNow(); }
    }

    @Test
    void concurrentClaimsAtDailyTokenBoundaryReserveExactlyOneWithoutTokenOverage() throws Exception {
        JdbcEmbeddingBudgetReservationAdapter reservations = adapter(10, 10, "1");
        ClaimedKnowledgeEmbedding first = processingClaim(10);
        ClaimedKnowledgeEmbedding second = processingClaim(10);

        assertConcurrentReservationsLeaveExactlyOneReservation(reservations, first, second);
        assertEquals(10L, jdbc.queryForObject("select sum(input_token_upper_bound) from embedding_budget_reservation", Long.class).longValue());
    }

    @Test
    void concurrentClaimsAtMonthlyUsdBoundaryReserveExactlyOneWithoutCostOverage() throws Exception {
        JdbcEmbeddingBudgetReservationAdapter reservations = adapter(10, 200000000, "1.00000000");
        ClaimedKnowledgeEmbedding first = processingClaim(50000000);
        ClaimedKnowledgeEmbedding second = processingClaim(50000000);

        assertConcurrentReservationsLeaveExactlyOneReservation(reservations, first, second);
        assertEquals(new BigDecimal("1.00000000"), jdbc.queryForObject(
            "select sum(reserved_cost_usd) from embedding_budget_reservation", BigDecimal.class));
    }

    private void assertConcurrentReservationsLeaveExactlyOneReservation(JdbcEmbeddingBudgetReservationAdapter reservations,
            ClaimedKnowledgeEmbedding first, ClaimedKnowledgeEmbedding second) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<EmbeddingBudgetReservationPort.Decision> one = workers.submit(() -> reserveWhenReleased(reservations, first, ready, start));
            Future<EmbeddingBudgetReservationPort.Decision> two = workers.submit(() -> reserveWhenReleased(reservations, second, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            EmbeddingBudgetReservationPort.Decision firstDecision = one.get(5, TimeUnit.SECONDS);
            EmbeddingBudgetReservationPort.Decision secondDecision = two.get(5, TimeUnit.SECONDS);
            int reserved = (firstDecision == EmbeddingBudgetReservationPort.Decision.RESERVED ? 1 : 0)
                + (secondDecision == EmbeddingBudgetReservationPort.Decision.RESERVED ? 1 : 0);
            int denied = (firstDecision == EmbeddingBudgetReservationPort.Decision.DENIED ? 1 : 0)
                + (secondDecision == EmbeddingBudgetReservationPort.Decision.DENIED ? 1 : 0);
            assertEquals(1, reserved);
            assertEquals(1, denied);
            assertEquals(1, jdbc.queryForObject("select count(*) from embedding_budget_reservation", Integer.class).intValue());
        } finally { workers.shutdownNow(); }
    }

    private static EmbeddingBudgetReservationPort.Decision reserveWhenReleased(JdbcEmbeddingBudgetReservationAdapter reservations,
            ClaimedKnowledgeEmbedding claim, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); start.await(5, TimeUnit.SECONDS); return reservations.reserve(claim).getDecision();
    }

    private JdbcEmbeddingBudgetReservationAdapter adapter(int entries, int tokens, String hardLimitUsd) {
        return new JdbcEmbeddingBudgetReservationAdapter(jdbc, entries, tokens, new BigDecimal(hardLimitUsd));
    }

    private ClaimedKnowledgeEmbedding processingClaim(int tokens) {
        UUID projectId = UUID.randomUUID(); UUID entryId = UUID.randomUUID(); UUID claimToken = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-09-06T12:00:00Z"));
        jdbc.update("insert into project(id,name,status,created_at,updated_at) values (?,?,'ACTIVE',?,?)", projectId, "Project " + projectId, now, now);
        jdbc.update("insert into knowledge_entry(id,project_id,question,answer,embedding_status,embedding_revision,embedding_processing_claim_token,embedding_processing_lease_expires_at,embedding_input_token_upper_bound,created_at,updated_at) values (?,?,?,?,'PROCESSING',1,?,clock_timestamp()+interval '5 minutes',?,?,?)",
            entryId, projectId, "Question", "Answer", claimToken, tokens, now, now);
        return new ClaimedKnowledgeEmbedding(entryId, projectId, 1, "Question", tokens, claimToken);
    }
}
