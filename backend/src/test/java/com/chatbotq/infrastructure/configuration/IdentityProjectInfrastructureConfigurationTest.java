package com.chatbotq.infrastructure.configuration;

import com.chatbotq.ChatbotQApplication;
import com.chatbotq.identityaccess.application.usecase.AdministerUserProjectAssignmentsUseCase;
import com.chatbotq.identityaccess.application.usecase.CreateAdminUserUseCase;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.knowledge.application.port.KnowledgeEmbeddingProcessingPort;

import com.chatbotq.projects.application.usecase.AddAllowedOriginUseCase;
import com.chatbotq.projects.application.usecase.CreateProjectUseCase;
import com.chatbotq.projects.domain.AllowedOrigin;
import com.chatbotq.projects.domain.Project;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(
    classes = ChatbotQApplication.class,
    properties = {
        "chatbotq.security.bcrypt-strength=4",
        "chatbotq.security.jwt.secret=test-fixture-signing-material-with-thirty-two-bytes"
    }
)
class IdentityProjectInfrastructureConfigurationTest {
    private static final DockerImageName IMAGE = DockerImageName.parse("pgvector/pgvector:pg15")
        .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(IMAGE)
        .withDatabaseName("chatbotq")
        .withUsername("chatbotq")
        .withPassword("chatbotq-test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private CreateProjectUseCase createProject;

    @Autowired
    private CreateAdminUserUseCase createAdminUser;

    @Autowired
    private AddAllowedOriginUseCase addAllowedOrigin;

    @Autowired
    private AdministerUserProjectAssignmentsUseCase assignments;

    @Autowired
    private KnowledgeEmbeddingProcessingPort embeddingProcessing;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void wiresUseCasesToRealPostgresAdapters() {
        Project project = createProject.execute("Proyecto integrado");
        AllowedOrigin origin = addAllowedOrigin.execute(
            project.getId(), "HTTPS://Example.COM:443");
        AdminUser user = createAdminUser.execute(
            "integrado@example.com", "temporal-segura", false);
        AdminUser general = createAdminUser.execute(
            "general-integrado@example.com", "temporal-segura", true);
        jdbc.update("update admin_user set status='ACTIVE' where id=?", general.getId());
        assignments.replace(general.getId(), user.getId(),
            java.util.Collections.singletonList(project.getId()));

        assertTrue(user.getPasswordHash().startsWith("$2"));
        assertTrue(embeddingProcessing.getClass().getName().contains("JdbcKnowledgeEmbeddingProcessingAdapter"));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from project where id = ?", Integer.class, project.getId()));
        assertEquals("https://example.com", jdbc.queryForObject(
            "select origin from project_allowed_origin where id = ?",
            String.class, origin.getId()));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from admin_user where id = ?", Integer.class, user.getId()));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from user_project_role where user_id = ? and project_id = ?",
            Integer.class, user.getId(), project.getId()));
    }
}
