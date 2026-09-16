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

        assertEquals(15, result.migrationsExecuted);
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
                    + "'admin_refresh_session', 'embedding_budget_reservation')")) {
                assertTrue(tables.next());
                assertEquals(14, tables.getInt(1));
            }

            UUID projectId = UUID.randomUUID();
            UUID knowledgeEntryId = UUID.randomUUID();
            try (PreparedStatement insertProject = connection.prepareStatement(
                    "insert into project(id,name) values (?,?)");
                 PreparedStatement insertKnowledge = connection.prepareStatement(
                    "insert into knowledge_entry(id,project_id,question,answer,embedding_input_token_upper_bound) values (?,?,?,?,?)");
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
                insertKnowledge.setInt(5, 22);
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
        assertEquals(15, flyway.migrate().migrationsExecuted);

        String schemaUrl = POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
        try (Connection connection = DriverManager.getConnection(schemaUrl,
                POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema + ", public");
            UUID projectId = UUID.randomUUID();
            statement.executeUpdate("insert into project(id,name) values ('" + projectId + "','Lifecycle constraints')");

            SQLException readyWithoutVectorAndTimestamp = assertThrows(SQLException.class, () -> statement.executeUpdate(
                "insert into knowledge_entry(id,project_id,question,answer,embedding_status,embedding_input_token_upper_bound) values ('"
                    + UUID.randomUUID() + "','" + projectId + "','Question','Answer','READY',1)"));
            assertEquals("23514", readyWithoutVectorAndTimestamp.getSQLState());

            for (String nonReadyStatus : new String[]{"PENDING", "PROCESSING", "FAILED"}) {
                SQLException nonReadyWithVectorAndTimestamp = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "insert into knowledge_entry(id,project_id,question,answer,embedding_status,embedding_input_token_upper_bound,embedding,embedded_at) values ('"
                        + UUID.randomUUID() + "','" + projectId + "','Question','Answer','" + nonReadyStatus
                        + "',1,array_fill(0::real, ARRAY[1536])::vector,current_timestamp)"));
                assertEquals("23514", nonReadyWithVectorAndTimestamp.getSQLState());
            }
        }
    }

    @Test
    void upgradesAProcessingV009EntryToPendingWithoutLeaseOwnership() throws Exception {
        String schema = "processing_lease_upgrade";
        installExtensionsInPublicSchema();
        Flyway before = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).createSchemas(true).locations("classpath:db/migration").target("009").load();
        assertEquals(9, before.migrate().migrationsExecuted);

        UUID projectId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        String schemaUrl = POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
        try (Connection connection = DriverManager.getConnection(schemaUrl,
                POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement insertProject = connection.prepareStatement(
                 "insert into project(id,name) values (?,?)");
             PreparedStatement insertEntry = connection.prepareStatement(
                 "insert into knowledge_entry(id,project_id,question,answer,embedding_status) values (?,?,?,?,'PROCESSING')")) {
            insertProject.setObject(1, projectId);
            insertProject.setString(2, "Existing V009 processing");
            insertProject.executeUpdate();
            insertEntry.setObject(1, entryId);
            insertEntry.setObject(2, projectId);
            insertEntry.setString(3, "Question being processed before V010");
            insertEntry.setString(4, "Answer");
            insertEntry.executeUpdate();
        }

        Flyway upgraded = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).locations("classpath:db/migration").load();
        assertEquals(6, upgraded.migrate().migrationsExecuted);
        upgraded.validate();

        try (Connection connection = DriverManager.getConnection(schemaUrl,
                POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement query = connection.prepareStatement(
                 "select embedding_status,embedding_processing_claim_token,embedding_processing_lease_expires_at "
                     + "from knowledge_entry where id=?")) {
            query.setObject(1, entryId);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals("PENDING", row.getString("embedding_status"));
                assertNull(row.getObject("embedding_processing_claim_token"));
                assertNull(row.getObject("embedding_processing_lease_expires_at"));
            }
        }
    }

    @Test
    void backfillsUtf8EmbeddingInputTokenUpperBoundAndEnforcesPositiveValue() throws Exception {
        String schema = "embedding_input_token_upper_bound_upgrade";
        installExtensionsInPublicSchema();
        Flyway before = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).createSchemas(true).locations("classpath:db/migration").target("010").load();
        assertEquals(10, before.migrate().migrationsExecuted);
        String schemaUrl = POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
        UUID projectId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(schemaUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement project = connection.prepareStatement("insert into project(id,name) values (?,?)");
             PreparedStatement entry = connection.prepareStatement(
                 "insert into knowledge_entry(id,project_id,question,answer) values (?,?,?,?)")) {
            project.setObject(1, projectId); project.setString(2, "Token bound upgrade"); project.executeUpdate();
            entry.setObject(1, entryId); entry.setObject(2, projectId); entry.setString(3, "ñ🙂"); entry.setString(4, "Answer");
            entry.executeUpdate();
        }
        Flyway upgraded = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).locations("classpath:db/migration").load();
        assertEquals(5, upgraded.migrate().migrationsExecuted);
        upgraded.validate();
        try (Connection connection = DriverManager.getConnection(schemaUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement query = connection.prepareStatement(
                 "select embedding_input_token_upper_bound from knowledge_entry where id=?")) {
            query.setObject(1, entryId);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(6, row.getInt("embedding_input_token_upper_bound"));
            }
            SQLException nonPositive = assertThrows(SQLException.class, () -> connection.createStatement().executeUpdate(
                "update knowledge_entry set embedding_input_token_upper_bound=0 where id='" + entryId + "'"));
            assertEquals("23514", nonPositive.getSQLState());
        }
    }

    @Test
    void upgradesLegacyImportedRowsWithoutRewritingThemAndEnforcesImportedCoherenceForward() throws Exception {
        String schema = "legacy_imported_row_upgrade";
        installExtensionsInPublicSchema();
        Flyway before = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).createSchemas(true).locations("classpath:db/migration").target("014").load();
        assertEquals(14, before.migrate().migrationsExecuted);

        UUID projectId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String schemaUrl = POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
        try (Connection connection = DriverManager.getConnection(schemaUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement project = connection.prepareStatement("insert into project(id,name) values (?,?)");
             PreparedStatement job = connection.prepareStatement(
                 "insert into knowledge_import_job(id,project_id,file_name,strategy,status,total_rows,valid_rows,invalid_rows,imported_rows,error_summary,created_at) "
                     + "values (?,?,?,?,?,?,?,?,?,?::jsonb,clock_timestamp())");
             PreparedStatement row = connection.prepareStatement(
                 "insert into knowledge_import_row(import_job_id,row_number,question,answer,active,status,errors,knowledge_entry_id) "
                     + "values (?,?,?,?,?,?,?::jsonb,?)")) {
            project.setObject(1, projectId); project.setString(2, "Legacy import rows"); project.executeUpdate();
            job.setObject(1, jobId); job.setObject(2, projectId); job.setString(3, "legacy.csv"); job.setString(4, "UPSERT");
            job.setString(5, "COMPLETED"); job.setInt(6, 2); job.setInt(7, 1); job.setInt(8, 1); job.setInt(9, 1); job.setString(10, "[]"); job.executeUpdate();
            row.setObject(1, jobId); row.setInt(2, 1); row.setString(3, "Broken"); row.setString(4, "Preview"); row.setBoolean(5, true);
            row.setString(6, "INVALID"); row.setString(7, "[\"bad row\"]"); row.setObject(8, null); row.executeUpdate();
            row.setObject(1, jobId); row.setInt(2, 2); row.setString(3, "Historic"); row.setString(4, "Imported"); row.setBoolean(5, true);
            row.setString(6, "IMPORTED"); row.setString(7, "[]"); row.setObject(8, null); row.executeUpdate();
        }

        Flyway upgraded = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema).locations("classpath:db/migration").load();
        assertEquals(1, upgraded.migrate().migrationsExecuted);
        upgraded.validate();

        try (Connection connection = DriverManager.getConnection(schemaUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema + ", public");
            try (ResultSet legacy = statement.executeQuery("select status,knowledge_entry_id from knowledge_import_row where import_job_id='"
                + jobId + "' order by row_number")) {
                assertTrue(legacy.next()); assertEquals("INVALID", legacy.getString(1)); assertNull(legacy.getObject(2));
                assertTrue(legacy.next()); assertEquals("IMPORTED", legacy.getString(1)); assertNull(legacy.getObject(2));
            }
            SQLException invalidImported = assertThrows(SQLException.class, () -> statement.executeUpdate(
                "insert into knowledge_import_row(import_job_id,row_number,status,errors,active) values ('" + jobId
                    + "',3,'IMPORTED','[]'::jsonb,true)"));
            assertEquals("23514", invalidImported.getSQLState());

            UUID entryId = UUID.randomUUID();
            statement.executeUpdate("insert into knowledge_entry(id,project_id,question,answer,embedding_input_token_upper_bound) values ('"
                + entryId + "','" + projectId + "','Valid','Entry',1)");
            assertEquals(1, statement.executeUpdate("insert into knowledge_import_row(import_job_id,row_number,status,errors,active,knowledge_entry_id) values ('"
                + jobId + "',4,'IMPORTED','[]'::jsonb,true,'" + entryId + "')"));
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
        assertEquals(9, upgraded.migrate().migrationsExecuted);
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
