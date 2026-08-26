package com.chatbotq.identityaccess.web;

import com.chatbotq.identityaccess.application.usecase.CreateAdminUserUseCase;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "chatbotq.security.bcrypt-strength=4",
    "chatbotq.security.jwt.secret=test-only-signing-key-at-least-thirty-two-bytes-long",
    "chatbotq.security.jwt.issuer=chatbotq-project-authorization-test"
})
@AutoConfigureMockMvc
@Import(ProjectAuthorizationHttpIntegrationTest.TestProjectController.class)
class ProjectAuthorizationHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("chatbotq").withUsername("chatbotq").withPassword("chatbotq-test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired CreateAdminUserUseCase createUser;
    @Autowired @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMappings;

    private UUID assignedProject;
    private UUID otherProject;
    private AdminUser generalAdmin;
    private AdminUser projectAdmin;

    @BeforeEach
    void seedCurrentAuthorizationState() {
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        assignedProject = UUID.randomUUID();
        otherProject = UUID.randomUUID();
        jdbc.update("insert into project (id, name, status) values (?, ?, 'ACTIVE')",
            assignedProject, "Asignado");
        jdbc.update("insert into project (id, name, status) values (?, ?, 'ACTIVE')",
            otherProject, "Otro");
        generalAdmin = activeUser("general@example.com", true);
        projectAdmin = activeUser("project@example.com", false);
        jdbc.update("insert into user_project_role (user_id, project_id, role) values (?, ?, 'PROJECT_ADMIN')",
            projectAdmin.getId(), assignedProject);
    }

    @Test
    void realJwtAllowsGeneralAndAssignedProjectAdminButDeniesCrossProject() throws Exception {
        String generalToken = login("general@example.com");
        String projectToken = login("project@example.com");

        mvc.perform(get(path(assignedProject)).header("Authorization", bearer(generalToken)))
            .andExpect(status().isOk());
        mvc.perform(get(path(assignedProject)).header("Authorization", bearer(projectToken)))
            .andExpect(status().isOk());
        mvc.perform(get(path(otherProject)).header("Authorization", bearer(projectToken)))
            .andExpect(status().isForbidden());
    }

    @Test
    void assignmentChangesTakeEffectWithoutReissuingJwt() throws Exception {
        String token = login("project@example.com");
        mvc.perform(get(path(assignedProject)).header("Authorization", bearer(token)))
            .andExpect(status().isOk());

        jdbc.update("delete from user_project_role where user_id = ? and project_id = ?",
            projectAdmin.getId(), assignedProject);
        mvc.perform(get(path(assignedProject)).header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());

        jdbc.update("insert into user_project_role (user_id, project_id, role) values (?, ?, 'PROJECT_ADMIN')",
            projectAdmin.getId(), assignedProject);
        mvc.perform(get(path(assignedProject)).header("Authorization", bearer(token)))
            .andExpect(status().isOk());
    }

    @Test
    void userGlobalRoleLockAndProjectStatusChangesUsePersistenceNotJwtClaims() throws Exception {
        String generalToken = login("general@example.com");
        mvc.perform(get(path(otherProject)).header("Authorization", bearer(generalToken)))
            .andExpect(status().isOk());

        jdbc.update("update admin_user set is_general_admin=false where id=?", generalAdmin.getId());
        mvc.perform(get(path(otherProject)).header("Authorization", bearer(generalToken)))
            .andExpect(status().isForbidden());

        jdbc.update("update admin_user set is_general_admin=true, locked_until=now() + interval '1 hour' where id=?",
            generalAdmin.getId());
        mvc.perform(get(path(otherProject)).header("Authorization", bearer(generalToken)))
            .andExpect(status().isForbidden());

        jdbc.update("update admin_user set locked_until=null where id=?", generalAdmin.getId());
        jdbc.update("update project set status='DISABLED' where id=?", otherProject);
        mvc.perform(get(path(otherProject)).header("Authorization", bearer(generalToken)))
            .andExpect(status().isForbidden());
    }

    @Test
    void disabledAndPasswordResetRequiredUsersAreDeniedWithExistingJwt() throws Exception {
        String token = login("project@example.com");
        jdbc.update("update admin_user set status='DISABLED' where id=?", projectAdmin.getId());
        mvc.perform(get(path(assignedProject)).header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());

        jdbc.update("update admin_user set status='PASSWORD_RESET_REQUIRED' where id=?", projectAdmin.getId());
        mvc.perform(get(path(assignedProject)).header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());
    }

    @Test
    void returns401WithoutAuthenticationAndUniform403ForMalformedCrossAndMissingProjects() throws Exception {
        String token = login("project@example.com");
        mvc.perform(get(path(assignedProject))).andExpect(status().isUnauthorized());

        MvcResult cross = mvc.perform(get(path(otherProject))
                .header("Authorization", bearer(token)))
            .andExpect(status().isForbidden()).andReturn();
        MvcResult missing = mvc.perform(get(path(UUID.randomUUID()))
                .header("Authorization", bearer(token)))
            .andExpect(status().isForbidden()).andReturn();
        MvcResult malformed = mvc.perform(get("/api/admin/test/projects/not-a-uuid")
                .header("Authorization", bearer(token)))
            .andExpect(status().isForbidden()).andReturn();

        mvc.perform(get("/api/admin/test/projects/1-1-1-1-1")
                .header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/test/projects/g0000000-0000-0000-0000-000000000000")
                .header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());
        assertEquals(cross.getResponse().getContentAsString(), missing.getResponse().getContentAsString());
        assertEquals(cross.getResponse().getContentAsString(), malformed.getResponse().getContentAsString());
    }

    @Test
    void testAuthorizationEndpointIsProvidedOnlyByTheTestController() {
        long matchingMappings = handlerMappings.getHandlerMethods().entrySet().stream()
            .filter(entry -> entry.getKey().toString().contains("/api/admin/test/"))
            .peek(entry -> assertEquals(TestProjectController.class,
                ((HandlerMethod) entry.getValue()).getBeanType()))
            .count();

        assertEquals(1L, matchingMappings);
    }

    private AdminUser activeUser(String email, boolean general) {
        AdminUser user = createUser.execute(email, "correct", general);
        jdbc.update("update admin_user set status='ACTIVE' where id=?", user.getId());
        return user;
    }

    private String login(String email) throws Exception {
        Map<String, String> request = new HashMap<>();
        request.put("email", email);
        request.put("password", "correct");
        JsonNode response = json.readTree(mvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return response.get("accessToken").asText();
    }

    private String path(UUID projectId) {
        return "/api/admin/test/projects/" + projectId;
    }

    private String bearer(String token) { return "Bearer " + token; }

    @RestController
    @RequestMapping("/api/admin/test/projects")
    static class TestProjectController {
        @GetMapping("/{projectId}")
        @PreAuthorize("@projectAuthorization.canAdminister(authentication, #projectId)")
        String project(@PathVariable String projectId) {
            return projectId;
        }
    }
}
