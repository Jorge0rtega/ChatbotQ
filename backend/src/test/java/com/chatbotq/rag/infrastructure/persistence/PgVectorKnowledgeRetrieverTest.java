package com.chatbotq.rag.infrastructure.persistence;

import com.chatbotq.rag.application.model.RetrievalCandidate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgVectorKnowledgeRetrieverTest {

    private static final DockerImageName IMAGE = DockerImageName.parse("pgvector/pgvector:pg15")
        .asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer<?> postgres;

    private static JdbcTemplate jdbc;
    private static UUID projectA;
    private static UUID projectB;
    private static UUID hiddenCrossProjectEntry;
    private static UUID inactiveEntry;
    private static final Set<UUID> PROJECT_A_ENTRIES = new HashSet<>();

    @BeforeAll
    static void migrateAndSeed() {
        String jdbcUrl = System.getenv("CHATBOTQ_TEST_DB_URL");
        String username = System.getenv("CHATBOTQ_TEST_DB_USERNAME");
        String password = System.getenv("CHATBOTQ_TEST_DB_PASSWORD");
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            postgres = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("chatbotq")
                .withUsername("chatbotq")
                .withPassword("chatbotq-test");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        }

        Flyway.configure()
            .dataSource(jdbcUrl, username, password)
            .locations("classpath:db/migration")
            .load()
            .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            jdbcUrl, username, password);
        jdbc = new JdbcTemplate(dataSource);
        projectA = UUID.randomUUID();
        projectB = UUID.randomUUID();
        jdbc.update("insert into project (id, name) values (?, ?)", projectA, "Proyecto A");
        jdbc.update("insert into project (id, name) values (?, ?)", projectB, "Proyecto B");

        for (int index = 0; index < 6; index++) {
            UUID id = UUID.randomUUID();
            PROJECT_A_ENTRIES.add(id);
            insertKnowledge(id, projectA, true, vector(1.0f, index * 0.01f));
        }
        hiddenCrossProjectEntry = UUID.randomUUID();
        insertKnowledge(hiddenCrossProjectEntry, projectB, true, vector(1.0f, 0.0f));
        inactiveEntry = UUID.randomUUID();
        insertKnowledge(inactiveEntry, projectA, false, vector(1.0f, 0.0f));
    }

    @AfterAll
    static void cleanUp() {
        if (jdbc != null && projectA != null && projectB != null) {
            jdbc.update("delete from project where id in (?, ?)", projectA, projectB);
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void returnsAtMostTopFiveActiveCandidatesFromRequiredProject() {
        PgVectorKnowledgeRetriever retriever = new PgVectorKnowledgeRetriever(jdbc);

        List<RetrievalCandidate> candidates = retriever.retrieve(
            projectA, basisVector(), 0.70d, 5);

        assertEquals(5, candidates.size());
        assertTrue(candidates.stream().allMatch(candidate -> PROJECT_A_ENTRIES.contains(candidate.getKnowledgeEntryId())));
        assertFalse(candidates.stream().anyMatch(candidate -> candidate.getKnowledgeEntryId().equals(hiddenCrossProjectEntry)));
        assertFalse(candidates.stream().anyMatch(candidate -> candidate.getKnowledgeEntryId().equals(inactiveEntry)));
        assertTrue(candidates.get(0).getSimilarityScore() >= candidates.get(4).getSimilarityScore());
    }

    @Test
    void rejectsCandidatesBelowThreshold() {
        UUID orthogonal = UUID.randomUUID();
        insertKnowledge(orthogonal, projectA, true, vector(0.0f, 1.0f));
        PgVectorKnowledgeRetriever retriever = new PgVectorKnowledgeRetriever(jdbc);

        List<RetrievalCandidate> candidates = retriever.retrieve(
            projectA, vectorArray(0.0f, 1.0f), 0.99d, 5);

        assertEquals(1, candidates.size());
        assertEquals(orthogonal, candidates.get(0).getKnowledgeEntryId());
    }

    private static void insertKnowledge(UUID id, UUID projectId, boolean active, String embedding) {
        jdbc.update("insert into knowledge_entry "
                + "(id, project_id, external_id, question, answer, active, embedding, embedded_at) "
                + "values (?, ?, ?, ?, ?, ?, cast(? as vector), current_timestamp)",
            id, projectId, id.toString(), "Pregunta " + id, "Respuesta " + id, active, embedding);
    }

    private static float[] basisVector() {
        return vectorArray(1.0f, 0.0f);
    }

    private static float[] vectorArray(float first, float second) {
        float[] values = new float[1536];
        values[0] = first;
        values[1] = second;
        return values;
    }

    private static String vector(float first, float second) {
        float[] values = vectorArray(first, second);
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(values[index]);
        }
        return builder.append(']').toString();
    }
}
