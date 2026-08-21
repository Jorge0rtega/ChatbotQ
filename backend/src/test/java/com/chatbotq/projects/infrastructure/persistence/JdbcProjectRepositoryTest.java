package com.chatbotq.projects.infrastructure.persistence;

import com.chatbotq.projects.domain.Project;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcProjectRepositoryTest {
    private static final DockerImageName IMAGE = DockerImageName.parse("pgvector/pgvector:pg15")
        .asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateDatabase() {
        postgres = new PostgreSQLContainer<>(IMAGE)
            .withDatabaseName("chatbotq")
            .withUsername("chatbotq")
            .withPassword("chatbotq-test");
        postgres.start();
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @BeforeEach
    void cleanProjects() {
        jdbc.update("delete from project");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void savesProjectWithDomainDefaultsAndExactTimestamps() {
        UUID id = UUID.randomUUID();
        UUID siteKey = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-20T22:00:00Z");
        Project project = Project.create(id, "Proyecto piloto", siteKey, now);
        JdbcProjectRepository repository = new JdbcProjectRepository(jdbc);

        Project saved = repository.save(project);

        Map<String, Object> row = jdbc.queryForMap(
            "select name, status, site_key, conversation_inactivity_seconds, "
                + "similarity_threshold, retrieval_top_k, created_at, updated_at "
                + "from project where id = ?", id);
        assertEquals(project, saved);
        assertEquals("Proyecto piloto", row.get("name"));
        assertEquals("ACTIVE", row.get("status"));
        assertEquals(siteKey, row.get("site_key"));
        assertEquals(600, row.get("conversation_inactivity_seconds"));
        assertEquals(0, Double.compare(0.700d,
            ((Number) row.get("similarity_threshold")).doubleValue()));
        assertEquals(5, row.get("retrieval_top_k"));
        assertEquals(now, ((java.sql.Timestamp) row.get("created_at")).toInstant());
        assertEquals(now, ((java.sql.Timestamp) row.get("updated_at")).toInstant());
    }
}
