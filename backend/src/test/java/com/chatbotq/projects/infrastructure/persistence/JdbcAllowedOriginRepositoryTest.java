package com.chatbotq.projects.infrastructure.persistence;

import com.chatbotq.projects.domain.AllowedOrigin;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAllowedOriginRepositoryTest {
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
    void savesOriginAndChecksDuplicateWithinItsProject() {
        UUID projectId = UUID.randomUUID();
        UUID disabledProjectId = UUID.randomUUID();
        UUID originId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-20T22:30:00Z");
        jdbc.update("insert into project (id, name) values (?, ?)", projectId, "Activo");
        jdbc.update("insert into project (id, name, status) values (?, ?, 'DISABLED')",
            disabledProjectId, "Inactivo");
        AllowedOrigin origin = AllowedOrigin.create(
            originId, projectId, "HTTPS://Example.COM:443", createdAt);
        JdbcAllowedOriginRepository origins = new JdbcAllowedOriginRepository(jdbc);
        JdbcProjectRepository projects = new JdbcProjectRepository(jdbc);

        AllowedOrigin saved = origins.save(origin);

        assertEquals(origin, saved);
        assertTrue(origins.exists(projectId, "https://example.com"));
        assertFalse(origins.exists(disabledProjectId, "https://example.com"));
        assertTrue(projects.existsActiveProject(projectId));
        assertFalse(projects.existsActiveProject(disabledProjectId));
        assertEquals("https://example.com", jdbc.queryForObject(
            "select origin from project_allowed_origin where id = ?", String.class, originId));
        assertEquals(createdAt, jdbc.queryForObject(
            "select created_at from project_allowed_origin where id = ?",
            java.sql.Timestamp.class, originId).toInstant());
    }
}
