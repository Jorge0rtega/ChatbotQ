package com.chatbotq.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class DatabaseMigrationTest {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
        .parse("pgvector/pgvector:pg15")
        .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
        .withDatabaseName("chatbotq")
        .withUsername("chatbotq")
        .withPassword("chatbotq-test");

    @Test
    void appliesAndValidatesCompleteInitialSchema() throws Exception {
        Flyway flyway = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load();

        MigrateResult result = flyway.migrate();

        assertEquals(6, result.migrationsExecuted);
        flyway.validate();

        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            try (ResultSet extension = statement.executeQuery(
                "select count(*) from pg_extension where extname in ('vector', 'pgcrypto')")) {
                assertTrue(extension.next());
                assertEquals(2, extension.getInt(1));
            }

            try (ResultSet tables = statement.executeQuery(
                "select count(*) from information_schema.tables "
                    + "where table_schema = 'public' "
                    + "and table_name in ('admin_user', 'project', 'project_allowed_origin', "
                    + "'user_project_role', 'knowledge_entry', 'conversation', "
                    + "'conversation_message', 'retrieval_trace', 'retrieval_candidate', "
                    + "'handoff_request', 'knowledge_import_job', 'provider_usage', "
                    + "'admin_refresh_session')")) {
                assertTrue(tables.next());
                assertEquals(13, tables.getInt(1));
            }
        }
    }
}
