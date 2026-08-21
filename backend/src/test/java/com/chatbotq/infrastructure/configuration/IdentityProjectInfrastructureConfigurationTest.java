package com.chatbotq.infrastructure.configuration;

import com.chatbotq.ChatbotQApplication;
import com.chatbotq.identityaccess.application.usecase.AssignProjectAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.CreateAdminUserUseCase;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.UserProjectAssignment;
import com.chatbotq.projects.application.usecase.CreateProjectUseCase;
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
    properties = "chatbotq.security.bcrypt-strength=4"
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
    private AssignProjectAdminUseCase assignProjectAdmin;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void wiresUseCasesToRealPostgresAdapters() {
        Project project = createProject.execute("Proyecto integrado");
        AdminUser user = createAdminUser.execute(
            "integrado@example.com", "temporal-segura", false);
        UserProjectAssignment assignment = assignProjectAdmin.execute(
            user.getId(), project.getId());

        assertTrue(user.getPasswordHash().startsWith("$2"));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from project where id = ?", Integer.class, project.getId()));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from admin_user where id = ?", Integer.class, user.getId()));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from user_project_role where user_id = ? and project_id = ?",
            Integer.class, assignment.getUserId(), assignment.getProjectId()));
    }
}
