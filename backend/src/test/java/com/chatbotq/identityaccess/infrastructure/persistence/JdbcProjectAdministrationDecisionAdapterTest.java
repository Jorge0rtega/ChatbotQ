package com.chatbotq.identityaccess.infrastructure.persistence;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcProjectAdministrationDecisionAdapterTest {
    private static PostgreSQLContainer<?> postgres;
    private static CountingJdbcTemplate jdbc;
    private JdbcProjectAdministrationDecisionAdapter decisions;

    private UUID activeProject;
    private UUID disabledProject;
    private UUID generalAdmin;
    private UUID projectAdmin;
    private UUID disabledUser;
    private UUID resetRequiredUser;
    private UUID lockedUser;

    @BeforeAll
    static void migrateDatabase() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("chatbotq")
            .withUsername("chatbotq")
            .withPassword("chatbotq-test");
        postgres.start();
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
        jdbc = new CountingJdbcTemplate(new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @BeforeEach
    void seedMatrix() {
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        activeProject = project("Activo", "ACTIVE");
        disabledProject = project("Inactivo", "DISABLED");
        generalAdmin = user("general@example.com", "ACTIVE", true, null);
        projectAdmin = user("project@example.com", "ACTIVE", false, null);
        disabledUser = user("disabled@example.com", "DISABLED", true, null);
        resetRequiredUser = user("reset@example.com", "PASSWORD_RESET_REQUIRED", true, null);
        lockedUser = user("locked@example.com", "ACTIVE", true,
            Instant.now().plusSeconds(3600));
        jdbc.update("insert into user_project_role (user_id, project_id, role) "
            + "values (?, ?, 'PROJECT_ADMIN')", projectAdmin, activeProject);
        decisions = new JdbcProjectAdministrationDecisionAdapter(jdbc);
        jdbc.resetDecisionQueries();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void evaluatesTheCompleteAuthorizationMatrixInPostgresql() {
        assertDecision(true, generalAdmin, activeProject);
        assertDecision(true, projectAdmin, activeProject);
        assertDecision(false, projectAdmin, disabledProject);
        assertDecision(false, generalAdmin, disabledProject);
        assertDecision(false, disabledUser, activeProject);
        assertDecision(false, resetRequiredUser, activeProject);
        assertDecision(false, lockedUser, activeProject);
        assertDecision(false, UUID.randomUUID(), activeProject);
        assertDecision(false, generalAdmin, UUID.randomUUID());

        UUID otherActiveUser = user("other@example.com", "ACTIVE", false, null);
        UUID otherActiveProject = project("Otro", "ACTIVE");
        jdbc.update("insert into user_project_role (user_id, project_id, role) "
            + "values (?, ?, 'PROJECT_ADMIN')", otherActiveUser, otherActiveProject);
        assertDecision(false, projectAdmin, otherActiveProject);
        assertDecision(false, otherActiveUser, activeProject);
        assertDecision(true, otherActiveUser, otherActiveProject);
    }

    @Test
    void eachDecisionUsesOneParameterizedStatementAndSeesChangesBeforeAndAfter() {
        assertDecision(true, projectAdmin, activeProject);

        jdbc.update("delete from user_project_role where user_id = ? and project_id = ?",
            projectAdmin, activeProject);
        assertDecision(false, projectAdmin, activeProject);

        jdbc.update("insert into user_project_role (user_id, project_id, role) "
            + "values (?, ?, 'PROJECT_ADMIN')", projectAdmin, activeProject);
        assertDecision(true, projectAdmin, activeProject);

        jdbc.update("update project set status = 'DISABLED' where id = ?", activeProject);
        assertDecision(false, projectAdmin, activeProject);
    }

    private void assertDecision(boolean expected, UUID userId, UUID projectId) {
        int before = jdbc.getDecisionQueries();
        if (expected) {
            assertTrue(decisions.canAdminister(userId, projectId));
        } else {
            assertFalse(decisions.canAdminister(userId, projectId));
        }
        assertEquals(before + 1, jdbc.getDecisionQueries(),
            "each authorization decision must execute exactly one SQL statement");
    }

    private UUID project(String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into project (id, name, status) values (?, ?, ?)", id, name, status);
        return id;
    }

    private UUID user(String email, String status, boolean general, Instant lockedUntil) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_user "
                + "(id, email, password_hash, status, is_general_admin, locked_until) "
                + "values (?, ?, 'hash', ?, ?, ?)",
            id, email, status, general,
            lockedUntil == null ? null : Timestamp.from(lockedUntil));
        return id;
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {
        private int decisionQueries;

        private CountingJdbcTemplate(DriverManagerDataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            decisionQueries++;
            return super.queryForObject(sql, requiredType, args);
        }

        private int getDecisionQueries() {
            return decisionQueries;
        }

        private void resetDecisionQueries() {
            decisionQueries = 0;
        }
    }
}
