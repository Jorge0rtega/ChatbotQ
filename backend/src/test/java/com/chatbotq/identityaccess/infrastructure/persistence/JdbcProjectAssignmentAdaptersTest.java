package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.domain.UserProjectAssignment;
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

class JdbcProjectAssignmentAdaptersTest {
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
    void cleanIdentityAndProjects() {
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void detectsOnlyActiveProjectsAndPersistsAssignment() {
        UUID activeProject = UUID.randomUUID();
        UUID disabledProject = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant assignedAt = Instant.parse("2026-08-20T22:00:00Z");
        jdbc.update("insert into project (id, name, status) values (?, ?, ?)",
            activeProject, "Activo", "ACTIVE");
        jdbc.update("insert into project (id, name, status) values (?, ?, ?)",
            disabledProject, "Inactivo", "DISABLED");
        jdbc.update("insert into admin_user (id, email, password_hash) values (?, ?, ?)",
            userId, "admin@example.com", "hash");
        JdbcProjectAccessPort projects = new JdbcProjectAccessPort(jdbc);
        JdbcUserProjectAssignmentRepository assignments =
            new JdbcUserProjectAssignmentRepository(jdbc);
        UserProjectAssignment assignment = UserProjectAssignment.projectAdmin(
            userId, activeProject, assignedAt);

        UserProjectAssignment saved = assignments.save(assignment);

        assertTrue(projects.existsActiveProject(activeProject));
        assertFalse(projects.existsActiveProject(disabledProject));
        assertFalse(projects.existsActiveProject(UUID.randomUUID()));
        assertEquals(assignment, saved);
        assertTrue(assignments.exists(userId, activeProject));
        assertFalse(assignments.exists(userId, disabledProject));
        assertEquals("PROJECT_ADMIN", jdbc.queryForObject(
            "select role from user_project_role where user_id = ? and project_id = ?",
            String.class, userId, activeProject));
        assertEquals(assignedAt, jdbc.queryForObject(
            "select assigned_at from user_project_role where user_id = ? and project_id = ?",
            java.sql.Timestamp.class, userId, activeProject).toInstant());
    }
}
