package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.identityaccess.infrastructure.persistence.JdbcUserProjectAssignmentRepository;
import com.chatbotq.knowledge.application.model.PersistedKnowledgeImportJob;
import com.chatbotq.knowledge.application.usecase.ForbiddenKnowledgeAdministrationException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcKnowledgeImportAdministrationAdapterConcurrencyTest {
    private static PostgreSQLContainer<?> postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private UUID general;
    private UUID projectAdmin;
    private UUID project;
    private UUID job;

    @BeforeAll
    static void database() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stop() { if (postgres != null) postgres.stop(); }

    @BeforeEach
    void seed() {
        jdbc.update("delete from knowledge_import_row");
        jdbc.update("delete from knowledge_import_job");
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        general = user(true);
        projectAdmin = user(false);
        project = UUID.randomUUID();
        job = UUID.randomUUID();
        jdbc.update("insert into project(id,name,status) values (?,?,'ACTIVE')", project, "Project");
        jdbc.update("insert into user_project_role(user_id,project_id,role) values (?,?,'PROJECT_ADMIN')", projectAdmin, project);
        jdbc.update("insert into knowledge_import_job(id,project_id,created_by,file_name,strategy,status,total_rows,valid_rows,invalid_rows,imported_rows,error_summary,created_at) values (?,?,?,?,?,?,?,?,?,?,?::jsonb,?)",
            job, project, general, "knowledge.csv", "UPSERT", "READY", 1, 1, 0, 0, "[]", java.sql.Timestamp.from(Instant.EPOCH));
        jdbc.update("insert into knowledge_import_row(import_job_id,row_number,question,answer,active,status,errors) values (?,?,?,?,?,?,?::jsonb)",
            job, 1, "Question", "Answer", true, "VALID", "[]");
    }

    @Test
    void revokedProjectAdminCannotReadImportDetailWhenRevocationCommitsBeforeJobRetrieval() throws Exception {
        CountDownLatch detailReachedJobRetrieval = new CountDownLatch(1);
        CountDownLatch releaseDetail = new CountDownLatch(1);
        JdbcKnowledgeImportAdministrationAdapter imports = new JdbcKnowledgeImportAdministrationAdapter(
            pausingBeforeJobRetrieval(detailReachedJobRetrieval, releaseDetail));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistedKnowledgeImportJob> detail = executor.submit(() -> transactions.execute(status ->
                imports.get(projectAdmin, project, job, 0, 10)));
            assertTrue(detailReachedJobRetrieval.await(5, TimeUnit.SECONDS),
                "detail request did not reach job retrieval after authorization");

            Future<?> revoke = executor.submit(() -> transactions.execute(status -> {
                new JdbcUserProjectAssignmentRepository(jdbc).replaceAsGeneralAdmin(general, projectAdmin,
                    Collections.<UUID>emptyList(), Instant.EPOCH);
                return null;
            }));
            revoke.get(5, TimeUnit.SECONDS);

            releaseDetail.countDown();
            assertThrows(ForbiddenKnowledgeAdministrationException.class, () -> awaitDetail(detail));
        } finally {
            releaseDetail.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static PersistedKnowledgeImportJob awaitDetail(Future<PersistedKnowledgeImportJob> detail) throws Exception {
        try {
            return detail.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw (RuntimeException) failed.getCause();
        }
    }

    private JdbcTemplate pausingBeforeJobRetrieval(CountDownLatch reached, CountDownLatch release) {
        return new JdbcTemplate(dataSource) {
            @Override public <T> java.util.List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                if (sql.contains("from knowledge_import_job")) {
                    reached.countDown();
                    await(release);
                }
                return super.query(sql, mapper, args);
            }
        };
    }

    private UUID user(boolean generalAdmin) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_user(id,email,password_hash,status,is_general_admin) values (?,?,?,'ACTIVE',?)",
            id, id + "@example.com", "hash", generalAdmin);
        return id;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting to release detail request");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
