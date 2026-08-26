package com.chatbotq.projects.infrastructure.persistence;

import com.chatbotq.projects.application.model.ManagedProject;
import com.chatbotq.projects.application.model.ManagedProjectPage;
import com.chatbotq.projects.application.usecase.ForbiddenProjectAdministrationException;
import com.chatbotq.projects.application.usecase.ProjectNotFoundException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcProjectAdministrationAdapterTest {
    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private JdbcProjectAdministrationAdapter adapter;

    @BeforeAll
    static void database() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg15")
            .asCompatibleSubstituteFor("postgres"));
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
            postgres.getPassword()));
    }

    @AfterAll static void stop() { if (postgres != null) postgres.stop(); }

    @BeforeEach
    void clean() {
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        adapter = new JdbcProjectAdministrationAdapter(jdbc);
    }

    @Test
    void conditionalWritesUseCurrentGeneralRoleAndReturn404OnlyToGeneralAdmin() {
        UUID general = user(true);
        UUID ordinary = user(false);
        UUID project = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-26T12:00:00Z");

        ManagedProject created = adapter.createAsGeneralAdmin(general, project, "One", UUID.randomUUID(), now);
        assertEquals(project, created.getId());
        jdbc.update("update admin_user set is_general_admin=false where id=?", general);
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> adapter.setActiveAsGeneralAdmin(general, project, false, now.plusSeconds(1)));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> adapter.findVisibleById(ordinary, UUID.randomUUID()));
        jdbc.update("update admin_user set is_general_admin=true where id=?", general);
        assertThrows(ProjectNotFoundException.class,
            () -> adapter.findVisibleById(general, UUID.randomUUID()));
    }

    @Test
    void assignmentAndActiveStatusAreCheckedByConditionalUpdateAndPagesAreStable() {
        UUID general = user(true);
        UUID projectAdmin = user(false);
        UUID first = project("First", Instant.parse("2026-08-26T10:00:00Z"));
        UUID second = project("Second", Instant.parse("2026-08-26T11:00:00Z"));
        jdbc.update("insert into user_project_role(user_id,project_id,role) values (?,?,'PROJECT_ADMIN')",
            projectAdmin, first);

        assertEquals("Renamed", adapter.updateName(projectAdmin, first, "Renamed", Instant.now()).getName());
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> adapter.updateName(projectAdmin, second, "Foreign", Instant.now()));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> adapter.updateName(projectAdmin, UUID.randomUUID(), "Missing", Instant.now()));
        jdbc.update("update project set status='DISABLED' where id=?", first);
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> adapter.updateName(projectAdmin, first, "Denied", Instant.now()));
        adapter.setActiveAsGeneralAdmin(general, first, true, Instant.now());
        assertTrue(adapter.findVisibleById(general, first).isActive());
        adapter.setActiveAsGeneralAdmin(general, first, false, Instant.now());
        assertFalse(adapter.findVisibleById(general, first).isActive());

        ManagedProjectPage page = adapter.listAsGeneralAdmin(general, 0, 1, 0L);
        assertEquals(2L, page.getTotalElements());
        assertEquals(2, page.getTotalPages());
        assertEquals(first, page.getItems().get(0).getId());
        assertEquals(second, adapter.listAsGeneralAdmin(general, 1, 1, 1L).getItems().get(0).getId());
    }

    @Test
    void findVisibleByIdUsesExactlyOneJdbcStatementForEveryAuthorizationOutcome() {
        UUID general = user(true);
        UUID revoked = user(true);
        UUID projectAdmin = user(false);
        UUID ordinary = user(false);
        UUID assigned = project("Assigned", Instant.parse("2026-08-26T10:00:00Z"));
        UUID foreign = project("Foreign", Instant.parse("2026-08-26T11:00:00Z"));
        UUID missing = UUID.randomUUID();
        jdbc.update("insert into user_project_role(user_id,project_id,role) values (?,?,'PROJECT_ADMIN')",
            projectAdmin, assigned);
        jdbc.update("update admin_user set status='DISABLED' where id=?", revoked);
        AtomicInteger statements = new AtomicInteger();
        JdbcProjectAdministrationAdapter oneStatementAdapter =
            new JdbcProjectAdministrationAdapter(countingProjectStatements(statements));

        assertEquals(assigned, oneStatementAdapter.findVisibleById(general, assigned).getId());
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ProjectNotFoundException.class,
            () -> oneStatementAdapter.findVisibleById(general, missing));
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> oneStatementAdapter.findVisibleById(revoked, assigned));
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> oneStatementAdapter.findVisibleById(revoked, missing));
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> oneStatementAdapter.findVisibleById(ordinary, assigned));
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> oneStatementAdapter.findVisibleById(ordinary, missing));
        assertEquals(1, statements.getAndSet(0));
        assertEquals(assigned, oneStatementAdapter.findVisibleById(projectAdmin, assigned).getId());
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> oneStatementAdapter.findVisibleById(projectAdmin, foreign));
        assertEquals(1, statements.get());
    }

    @Test
    void updateNameUsesExactlyOneJdbcStatementForEveryAuthorizationOutcome() {
        UUID general = user(true);
        UUID revoked = user(true);
        UUID projectAdmin = user(false);
        UUID ordinary = user(false);
        UUID assigned = project("Assigned", Instant.parse("2026-08-26T10:00:00Z"));
        UUID foreign = project("Foreign", Instant.parse("2026-08-26T11:00:00Z"));
        UUID missing = UUID.randomUUID();
        jdbc.update("insert into user_project_role(user_id,project_id,role) values (?,?,'PROJECT_ADMIN')",
            projectAdmin, assigned);
        jdbc.update("update admin_user set is_general_admin=false,status='DISABLED' where id=?", revoked);
        AtomicInteger statements = new AtomicInteger();
        JdbcProjectAdministrationAdapter oneStatementAdapter =
            new JdbcProjectAdministrationAdapter(countingProjectStatements(statements));
        Instant changedAt = Instant.parse("2026-08-26T12:00:00Z");

        ManagedProject updated = oneStatementAdapter.updateName(general, assigned, "General", changedAt);
        assertEquals("General", updated.getName());
        assertEquals(changedAt, updated.getUpdatedAt());
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ProjectNotFoundException.class,
            () -> oneStatementAdapter.updateName(general, missing, "Missing", changedAt));
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> oneStatementAdapter.updateName(revoked, assigned, "Denied", changedAt));
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> oneStatementAdapter.updateName(revoked, missing, "Denied", changedAt));
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> oneStatementAdapter.updateName(ordinary, assigned, "Denied", changedAt));
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> oneStatementAdapter.updateName(ordinary, missing, "Denied", changedAt));
        assertEquals(1, statements.getAndSet(0));
        assertEquals("Project admin",
            oneStatementAdapter.updateName(projectAdmin, assigned, "Project admin", changedAt).getName());
        assertEquals(1, statements.getAndSet(0));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> oneStatementAdapter.updateName(projectAdmin, foreign, "Denied", changedAt));
        assertEquals(1, statements.get());
    }

    @Test
    void noSecondStatementAllowsProjectInsertionBetweenFindAndClassification() {
        UUID general = user(true);
        UUID missing = UUID.randomUUID();
        AtomicInteger statements = new AtomicInteger();
        JdbcTemplate insertingJdbc = afterFirstProjectStatement(statements, new Runnable() {
            @Override public void run() {
                project(missing, "Inserted concurrently", Instant.parse("2026-08-26T10:00:00Z"));
            }
        });

        assertThrows(ProjectNotFoundException.class,
            () -> new JdbcProjectAdministrationAdapter(insertingJdbc).findVisibleById(general, missing));
        assertEquals(1, statements.get());
    }

    @Test
    void noSecondStatementAllowsRevocationBetweenUpdateAndClassification() {
        UUID general = user(true);
        UUID missing = UUID.randomUUID();
        AtomicInteger statements = new AtomicInteger();
        JdbcTemplate revokingJdbc = afterFirstProjectStatement(statements, new Runnable() {
            @Override public void run() {
                jdbc.update("update admin_user set is_general_admin=false where id=?", general);
            }
        });

        assertThrows(ProjectNotFoundException.class, () -> new JdbcProjectAdministrationAdapter(revokingJdbc)
            .updateName(general, missing, "Missing", Instant.parse("2026-08-26T12:00:00Z")));
        assertEquals(1, statements.get());
    }

    @Test
    void listUsesOneAuthorizedSnapshotForItemsAndTotalIncludingEmptyPages() {
        UUID general = user(true);
        project("First", Instant.parse("2026-08-26T10:00:00Z"));
        project("Second", Instant.parse("2026-08-26T11:00:00Z"));
        AtomicInteger statements = new AtomicInteger();
        JdbcProjectAdministrationAdapter snapshotAdapter =
            new JdbcProjectAdministrationAdapter(countingQueries(statements));

        ManagedProjectPage firstPage = snapshotAdapter.listAsGeneralAdmin(general, 0, 100, 0L);
        assertEquals(firstPage.getTotalElements(), firstPage.getItems().size());
        assertEquals(1, statements.get());

        statements.set(0);
        ManagedProjectPage emptyPage = snapshotAdapter.listAsGeneralAdmin(general, 100, 100, 10_000L);
        assertTrue(emptyPage.getItems().isEmpty());
        assertEquals(2L, emptyPage.getTotalElements());
        assertEquals(1, statements.get());
    }

    @Test
    void repeatedActivationAndDeactivationLeaveUpdatedTimestampStable() {
        UUID general = user(true);
        UUID project = project("Stable", Instant.parse("2026-08-26T10:00:00Z"));

        adapter.setActiveAsGeneralAdmin(general, project, true, Instant.parse("2026-08-26T11:00:00Z"));
        assertEquals(Instant.parse("2026-08-26T10:00:00Z"), updatedAt(project));

        adapter.setActiveAsGeneralAdmin(general, project, false, Instant.parse("2026-08-26T12:00:00Z"));
        assertEquals(Instant.parse("2026-08-26T12:00:00Z"), updatedAt(project));
        adapter.setActiveAsGeneralAdmin(general, project, false, Instant.parse("2026-08-26T13:00:00Z"));
        assertEquals(Instant.parse("2026-08-26T12:00:00Z"), updatedAt(project));

        adapter.setActiveAsGeneralAdmin(general, project, true, Instant.parse("2026-08-26T14:00:00Z"));
        assertEquals(Instant.parse("2026-08-26T14:00:00Z"), updatedAt(project));
        adapter.setActiveAsGeneralAdmin(general, project, true, Instant.parse("2026-08-26T15:00:00Z"));
        assertEquals(Instant.parse("2026-08-26T14:00:00Z"), updatedAt(project));
    }

    @ParameterizedTest(name = "{0} actor is rejected by every project administration operation")
    @ValueSource(strings = {"DISABLED", "PASSWORD_RESET_REQUIRED", "LOCKED"})
    void unavailableGeneralActorIsRejectedAcrossAllOperations(String actorState) {
        UUID actor = user(true);
        UUID existing = project("Existing", Instant.parse("2026-08-26T10:00:00Z"));
        if ("LOCKED".equals(actorState)) {
            jdbc.update("update admin_user set locked_until=now() + interval '1 hour' where id=?", actor);
        } else {
            jdbc.update("update admin_user set status=? where id=?", actorState, actor);
        }

        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> adapter.createAsGeneralAdmin(actor, UUID.randomUUID(), "Denied", UUID.randomUUID(), Instant.now()));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> adapter.findVisibleById(actor, existing));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> adapter.listAsGeneralAdmin(actor, 0, 20, 0L));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> adapter.updateName(actor, existing, "Denied", Instant.now()));
        assertThrows(ForbiddenProjectAdministrationException.class,
            () -> adapter.setActiveAsGeneralAdmin(actor, existing, false, Instant.now()));
        assertEquals("Existing", jdbc.queryForObject("select name from project where id=?", String.class, existing));
        assertEquals("ACTIVE", jdbc.queryForObject("select status from project where id=?", String.class, existing));
    }

    private UUID user(boolean general) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_user(id,email,password_hash,status,is_general_admin) values (?,?,?,'ACTIVE',?)",
            id, id + "@example.com", "hash", general);
        return id;
    }

    private UUID project(String name, Instant createdAt) {
        UUID id = UUID.randomUUID();
        project(id, name, createdAt);
        return id;
    }

    private void project(UUID id, String name, Instant createdAt) {
        jdbc.update("insert into project(id,name,status,created_at,updated_at) values (?,?,'ACTIVE',?,?)",
            id, name, java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt));
    }

    private Instant updatedAt(UUID projectId) {
        return jdbc.queryForObject("select updated_at from project where id=?", java.sql.Timestamp.class,
            projectId).toInstant();
    }


    private JdbcTemplate countingProjectStatements(final AtomicInteger statements) {
        return new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> java.util.List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> rowMapper,
                                               Object... args) {
                statements.incrementAndGet();
                return super.query(sql, rowMapper, args);
            }

            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                statements.incrementAndGet();
                return super.queryForObject(sql, requiredType, args);
            }

            @Override
            public <T> T queryForObject(String sql, org.springframework.jdbc.core.RowMapper<T> rowMapper,
                                        Object... args) {
                statements.incrementAndGet();
                return super.queryForObject(sql, rowMapper, args);
            }
        };
    }

    private JdbcTemplate countingQueries(final AtomicInteger statements) {
        return new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public void query(String sql, org.springframework.jdbc.core.RowCallbackHandler handler,
                              Object... args) {
                statements.incrementAndGet();
                super.query(sql, handler, args);
            }
        };
    }

    private JdbcTemplate afterFirstProjectStatement(final AtomicInteger statements, final Runnable action) {
        return new JdbcTemplate(jdbc.getDataSource()) {
            private final AtomicBoolean first = new AtomicBoolean(true);

            @Override
            public <T> java.util.List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> rowMapper,
                                               Object... args) {
                statements.incrementAndGet();
                java.util.List<T> result = super.query(sql, rowMapper, args);
                if (first.compareAndSet(true, false)) action.run();
                return result;
            }

            @Override
            public <T> T queryForObject(String sql, org.springframework.jdbc.core.RowMapper<T> rowMapper,
                                        Object... args) {
                statements.incrementAndGet();
                T result = super.queryForObject(sql, rowMapper, args);
                if (first.compareAndSet(true, false)) action.run();
                return result;
            }
        };
    }
}
