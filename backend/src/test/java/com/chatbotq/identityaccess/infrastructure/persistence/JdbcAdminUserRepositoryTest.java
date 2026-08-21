package com.chatbotq.identityaccess.infrastructure.persistence;

import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.AdminUserStatus;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAdminUserRepositoryTest {
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
    void cleanUsers() {
        jdbc.update("delete from admin_user");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void savesAndRehydratesUserAndFindsEmailCaseInsensitively() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-20T22:00:00Z");
        Instant activatedAt = Instant.parse("2026-08-20T22:01:00Z");
        AdminUser user = AdminUser.create(
            id, "Admin@Example.com", "bcrypt-hash", true, createdAt);
        user.activate(activatedAt);
        JdbcAdminUserRepository repository = new JdbcAdminUserRepository(jdbc);

        AdminUser saved = repository.save(user);
        Optional<AdminUser> loaded = repository.findById(id);

        assertEquals(user, saved);
        assertTrue(repository.existsByEmail("ADMIN@EXAMPLE.COM"));
        assertFalse(repository.existsByEmail("missing@example.com"));
        assertTrue(loaded.isPresent());
        assertEquals(id, loaded.get().getId());
        assertEquals("admin@example.com", loaded.get().getEmail());
        assertEquals("bcrypt-hash", loaded.get().getPasswordHash());
        assertEquals(AdminUserStatus.ACTIVE, loaded.get().getStatus());
        assertTrue(loaded.get().isGeneralAdmin());
        assertEquals(createdAt, loaded.get().getCreatedAt());
        assertEquals(activatedAt, loaded.get().getUpdatedAt());
    }
}
