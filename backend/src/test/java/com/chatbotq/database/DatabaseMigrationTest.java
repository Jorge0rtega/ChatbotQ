package com.chatbotq.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        assertEquals(9, result.migrationsExecuted);
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

            UUID projectId = UUID.randomUUID();
            UUID knowledgeEntryId = UUID.randomUUID();
            try (PreparedStatement insertProject = connection.prepareStatement(
                    "insert into project(id,name) values (?,?)");
                 PreparedStatement insertKnowledge = connection.prepareStatement(
                    "insert into knowledge_entry(id,project_id,question,answer) values (?,?,?,?)");
                 PreparedStatement query = connection.prepareStatement(
                    "select embedding_status,embedding_revision,embedding_attempt_count,"
                        + "embedding_last_attempt_at,embedding_last_error_code,embedding_last_error_message "
                        + "from knowledge_entry where id=?")) {
                insertProject.setObject(1, projectId);
                insertProject.setString(2, "Embedding lifecycle");
                insertProject.executeUpdate();

                insertKnowledge.setObject(1, knowledgeEntryId);
                insertKnowledge.setObject(2, projectId);
                insertKnowledge.setString(3, "What is the lifecycle?");
                insertKnowledge.setString(4, "It is explicit and traceable.");
                insertKnowledge.executeUpdate();

                query.setObject(1, knowledgeEntryId);
                try (ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals("PENDING", row.getString("embedding_status"));
                    assertEquals(1L, row.getLong("embedding_revision"));
                    assertEquals(0, row.getInt("embedding_attempt_count"));
                    assertNull(row.getObject("embedding_last_attempt_at"));
                    assertNull(row.getObject("embedding_last_error_code"));
                    assertNull(row.getObject("embedding_last_error_message"));
                }
            }
        }
    }

    @Test
    void rejectsEmbeddingLifecycleStatesThatDoNotMatchVectorAndTimestamp() throws Exception {
        String schema = "embedding_lifecycle_coherence";
        installExtensionsInPublicSchema();
        Flyway flyway = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).createSchemas(true).locations("classpath:db/migration").load();
        assertEquals(9, flyway.migrate().migrationsExecuted);

        String schemaUrl = POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
        try (Connection connection = DriverManager.getConnection(schemaUrl,
                POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema + ", public");
            UUID projectId = UUID.randomUUID();
            statement.executeUpdate("insert into project(id,name) values ('" + projectId + "','Lifecycle constraints')");

            SQLException readyWithoutVectorAndTimestamp = assertThrows(SQLException.class, () -> statement.executeUpdate(
                "insert into knowledge_entry(id,project_id,question,answer,embedding_status) values ('"
                    + UUID.randomUUID() + "','" + projectId + "','Question','Answer','READY')"));
            assertEquals("23514", readyWithoutVectorAndTimestamp.getSQLState());

            for (String nonReadyStatus : new String[]{"PENDING", "PROCESSING", "FAILED"}) {
                SQLException nonReadyWithVectorAndTimestamp = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "insert into knowledge_entry(id,project_id,question,answer,embedding_status,embedding,embedded_at) values ('"
                        + UUID.randomUUID() + "','" + projectId + "','Question','Answer','" + nonReadyStatus
                        + "',array_fill(0::real, ARRAY[1536])::vector,current_timestamp)"));
                assertEquals("23514", nonReadyWithVectorAndTimestamp.getSQLState());
            }
        }
    }

    private static void installExtensionsInPublicSchema() throws Exception {
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("create extension if not exists pgcrypto with schema public");
            statement.execute("create extension if not exists vector with schema public");
        }
    }

    @Test
    void upgradesV001ThroughV006DataWithoutChangingSiteKey() throws Exception {
        String schema = "site_key_upgrade";
        Flyway before = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).createSchemas(true).locations("classpath:db/migration").target("006").load();
        assertEquals(6, before.migrate().migrationsExecuted);
        UUID projectId = UUID.randomUUID();
        UUID siteKey = UUID.randomUUID();
        UUID readyKnowledgeId = UUID.randomUUID();
        UUID pendingKnowledgeId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-20T22:00:00Z");
        String schemaUrl = POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
        try (Connection connection = DriverManager.getConnection(schemaUrl,
                POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement insert = connection.prepareStatement(
                 "insert into project(id,name,site_key,created_at,updated_at) values (?,?,?,?,?)");
             PreparedStatement insertKnowledge = connection.prepareStatement(
                 "insert into knowledge_entry(id,project_id,question,answer,embedding,embedded_at) "
                     + "values (?,?,?,?,array_fill(0::real, ARRAY[1536])::vector,?)");
             PreparedStatement insertUnembeddedKnowledge = connection.prepareStatement(
                 "insert into knowledge_entry(id,project_id,question,answer,embedding,embedded_at) "
                     + "values (?,?,?,?,NULL,NULL)")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("set search_path to " + schema + ", public");
            }
            insert.setObject(1, projectId);
            insert.setString(2, "Existing");
            insert.setObject(3, siteKey);
            insert.setTimestamp(4, Timestamp.from(createdAt));
            insert.setTimestamp(5, Timestamp.from(createdAt));
            insert.executeUpdate();

            insertKnowledge.setObject(1, readyKnowledgeId);
            insertKnowledge.setObject(2, projectId);
            insertKnowledge.setString(3, "Legacy ready question");
            insertKnowledge.setString(4, "Legacy ready answer");
            insertKnowledge.setTimestamp(5, Timestamp.from(createdAt));
            insertKnowledge.executeUpdate();

            insertUnembeddedKnowledge.setObject(1, pendingKnowledgeId);
            insertUnembeddedKnowledge.setObject(2, projectId);
            insertUnembeddedKnowledge.setString(3, "Legacy pending question");
            insertUnembeddedKnowledge.setString(4, "Legacy pending answer");
            insertUnembeddedKnowledge.executeUpdate();
        }

        Flyway upgraded = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).locations("classpath:db/migration").load();
        assertEquals(3, upgraded.migrate().migrationsExecuted);
        upgraded.validate();
        try (Connection connection = DriverManager.getConnection(schemaUrl,
                POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement query = connection.prepareStatement(
                 "select site_key,site_key_version,site_key_rotated_at,site_key_rotated_by from project where id=?");
             PreparedStatement knowledgeQuery = connection.prepareStatement(
                 "select embedding_status from knowledge_entry where id=?")) {
            query.setObject(1, projectId);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(siteKey, row.getObject("site_key"));
                assertEquals(1L, row.getLong("site_key_version"));
                assertEquals(createdAt, row.getTimestamp("site_key_rotated_at").toInstant());
                assertEquals(null, row.getObject("site_key_rotated_by"));
            }
            knowledgeQuery.setObject(1, readyKnowledgeId);
            try (ResultSet row = knowledgeQuery.executeQuery()) {
                assertTrue(row.next());
                assertEquals("READY", row.getString("embedding_status"));
            }
            knowledgeQuery.setObject(1, pendingKnowledgeId);
            try (ResultSet row = knowledgeQuery.executeQuery()) {
                assertTrue(row.next());
                assertEquals("PENDING", row.getString("embedding_status"));
            }
        }
    }
}
