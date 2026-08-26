package com.chatbotq.projects.web;

import com.chatbotq.identityaccess.application.usecase.CreateAdminUserUseCase;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "chatbotq.security.bcrypt-strength=4",
    "chatbotq.security.jwt.secret=test-only-signing-key-at-least-thirty-two-bytes-long",
    "chatbotq.security.jwt.issuer=chatbotq-project-crud-test"
})
@AutoConfigureMockMvc
class AdminProjectsHttpIntegrationTest {
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

    private AdminUser general;
    private AdminUser projectAdmin;
    private UUID assigned;
    private UUID other;

    @BeforeEach
    void seed() {
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        assigned = insertProject("Assigned", "ACTIVE");
        other = insertProject("Other", "ACTIVE");
        general = activeUser("general-crud@example.com", true);
        projectAdmin = activeUser("project-crud@example.com", false);
        jdbc.update("insert into user_project_role (user_id, project_id, role) values (?, ?, 'PROJECT_ADMIN')",
            projectAdmin.getId(), assigned);
    }

    @Test
    void generalAdminCreatesAndListsStablePagesFromCurrentDatabaseRole() throws Exception {
        String token = login("general-crud@example.com");
        String body = mvc.perform(post("/api/admin/projects")
                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  Created  \"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("http://localhost/api/admin/projects/[0-9a-f-]{36}")))
            .andExpect(jsonPath("$.name", is("Created")))
            .andExpect(jsonPath("$.status", is("ACTIVE")))
            .andExpect(jsonPath("$.siteKey").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        String createdId = json.readTree(body).get("id").asText();

        mvc.perform(get("/api/admin/projects?page=0&size=2")
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(2)))
            .andExpect(jsonPath("$.items[*].name", contains("Assigned", "Other")))
            .andExpect(jsonPath("$.items[*].siteKey").doesNotExist())
            .andExpect(jsonPath("$.page", is(0)))
            .andExpect(jsonPath("$.size", is(2)))
            .andExpect(jsonPath("$.totalElements", is(3)))
            .andExpect(jsonPath("$.totalPages", is(2)));

        jdbc.update("update admin_user set is_general_admin=false where id=?", general.getId());
        mvc.perform(post("/api/admin/projects").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Denied\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/projects").header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());
        assertEquals(1, jdbc.queryForObject("select count(*) from project where id=?", Integer.class,
            UUID.fromString(createdId)));
    }

    @Test
    void projectAdminReadsAndRenamesOnlyItsCurrentlyAssignedActiveProject() throws Exception {
        String token = login("project-crud@example.com");
        mvc.perform(get("/api/admin/projects/" + assigned).header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name", is("Assigned")))
            .andExpect(jsonPath("$.siteKey").doesNotExist());
        mvc.perform(put("/api/admin/projects/" + assigned).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name", is("Renamed")))
            .andExpect(jsonPath("$.siteKey").doesNotExist());

        mvc.perform(get("/api/admin/projects/" + other).header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/projects/" + other).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Leaked\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/projects/" + UUID.randomUUID()).header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/projects").header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/projects").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Denied\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void activationEndpointsAreGeneralOnlyIdempotentAndGeneralCanSeeDisabledProjects() throws Exception {
        String generalToken = login("general-crud@example.com");
        String projectToken = login("project-crud@example.com");

        mvc.perform(post("/api/admin/projects/" + assigned + "/deactivate")
                .header("Authorization", bearer(generalToken)))
            .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(post("/api/admin/projects/" + assigned + "/deactivate")
                .header("Authorization", bearer(generalToken)))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/projects/" + assigned).header("Authorization", bearer(generalToken)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("DISABLED")));
        mvc.perform(get("/api/admin/projects/" + assigned).header("Authorization", bearer(projectToken)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/projects/" + assigned + "/activate")
                .header("Authorization", bearer(projectToken)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/projects/" + assigned + "/deactivate")
                .header("Authorization", bearer(projectToken)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/projects/" + assigned + "/activate")
                .header("Authorization", bearer(generalToken)))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/projects/" + assigned + "/activate")
                .header("Authorization", bearer(generalToken)))
            .andExpect(status().isNoContent());
    }

    @Test
    void validatesContractAndUses404OnlyForAuthorizedGeneralAdmin() throws Exception {
        String generalToken = login("general-crud@example.com");
        String projectToken = login("project-crud@example.com");
        UUID missing = UUID.randomUUID();

        mvc.perform(get("/api/admin/projects/" + assigned)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/projects/" + missing).header("Authorization", bearer(generalToken)))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/projects/" + missing + "/deactivate")
                .header("Authorization", bearer(generalToken)))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/projects/" + missing + "/activate")
                .header("Authorization", bearer(generalToken)))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/projects/" + missing).header("Authorization", bearer(generalToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Missing\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/projects/" + missing).header("Authorization", bearer(projectToken)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/projects").header("Authorization", bearer(generalToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/projects").header("Authorization", bearer(generalToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ok\",\"siteKey\":\"x\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/projects/" + assigned).header("Authorization", bearer(generalToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"" + other + "\",\"name\":\"x\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/projects?page=-1&size=101").header("Authorization", bearer(generalToken)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/projects?page=" + Integer.MAX_VALUE + "&size=100")
                .header("Authorization", bearer(generalToken)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/projects/not-a-uuid").header("Authorization", bearer(generalToken)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/projects?page=nope").header("Authorization", bearer(generalToken)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidJsonAndClosedDtoInputsWhileAcceptingNameBoundaries() throws Exception {
        String token = login("general-crud@example.com");

        assertCreateStatus(token, "", 400);
        assertCreateStatus(token, "null", 400);
        assertCreateStatus(token, "{", 400);
        assertCreateStatus(token, "{}", 400);
        assertCreateStatus(token, "{\"name\":null}", 400);
        assertCreateStatus(token, "{\"name\":\"\\u2003\\u2009\"}", 400);
        assertCreateStatus(token, "{\"name\":\"valid\\u0000name\"}", 400);
        assertCreateStatus(token, "{\"name\":\"ok\",\"unknown\":true}", 400);
        assertCreateStatus(token, "{\"name\":\"first\",\"name\":\"second\"}", 400);
        assertCreateStatus(token, "{\"name\":\"x\"}", 201);
        assertCreateStatus(token, "{\"name\":\"" + repeat('x', 160) + "\"}", 201);
        assertCreateStatus(token, "{\"name\":\"" + repeat('x', 161) + "\"}", 400);
    }

    @Test
    void revocationAndProjectStatusChangesApplyWithoutNewJwt() throws Exception {
        String token = login("project-crud@example.com");
        jdbc.update("delete from user_project_role where user_id=? and project_id=?", projectAdmin.getId(), assigned);
        mvc.perform(put("/api/admin/projects/" + assigned).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Denied\"}"))
            .andExpect(status().isForbidden());

        jdbc.update("insert into user_project_role (user_id, project_id, role) values (?, ?, 'PROJECT_ADMIN')",
            projectAdmin.getId(), assigned);
        jdbc.update("update project set status='DISABLED' where id=?", assigned);
        mvc.perform(put("/api/admin/projects/" + assigned).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Denied\"}"))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "existing JWT: {0} actor cannot {1}")
    @MethodSource("unavailableActorEndpointCases")
    void unavailableActorStateAppliesAcrossProjectEndpointsWithExistingJwt(String actorState,
                                                                            String endpoint) throws Exception {
        String token = login("general-crud@example.com");
        if ("LOCKED".equals(actorState)) {
            jdbc.update("update admin_user set locked_until=now() + interval '1 hour' where id=?", general.getId());
        } else {
            jdbc.update("update admin_user set status=? where id=?", actorState, general.getId());
        }

        mvc.perform(projectRequest(endpoint).header("Authorization", bearer(token)))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} is 401 for {1}")
    @MethodSource("unauthenticatedEndpointCases")
    void projectEndpointFamilyRequiresValidToken(String authorization, String endpoint) throws Exception {
        MockHttpServletRequestBuilder request = projectRequest(endpoint);
        if (authorization != null) request.header("Authorization", authorization);
        mvc.perform(request).andExpect(status().isUnauthorized());
    }

    static Stream<Arguments> unavailableActorEndpointCases() {
        return Stream.of("DISABLED", "PASSWORD_RESET_REQUIRED", "LOCKED")
            .flatMap(actorState -> Stream.of("create", "get", "list", "update", "setActive")
                .map(endpoint -> Arguments.of(actorState, endpoint)));
    }

    static Stream<Arguments> unauthenticatedEndpointCases() {
        return Stream.of((String) null, "Bearer invalid-token")
            .flatMap(authorization -> Stream.of("create", "get", "list", "update", "setActive")
                .map(endpoint -> Arguments.of(authorization, endpoint)));
    }

    private MockHttpServletRequestBuilder projectRequest(String endpoint) {
        if ("create".equals(endpoint)) {
            return post("/api/admin/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Denied\"}");
        }
        if ("get".equals(endpoint)) return get("/api/admin/projects/" + assigned);
        if ("list".equals(endpoint)) return get("/api/admin/projects");
        if ("update".equals(endpoint)) {
            return put("/api/admin/projects/" + assigned).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Denied\"}");
        }
        if ("setActive".equals(endpoint)) return post("/api/admin/projects/" + assigned + "/deactivate");
        throw new IllegalArgumentException("unknown endpoint: " + endpoint);
    }

    private UUID insertProject(String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into project (id, name, status, created_at, updated_at) values (?, ?, ?, now(), now())",
            id, name, status);
        return id;
    }

    private AdminUser activeUser(String email, boolean generalAdmin) {
        AdminUser user = createUser.execute(email, "correct", generalAdmin);
        jdbc.update("update admin_user set status='ACTIVE' where id=?", user.getId());
        return user;
    }

    private String login(String email) throws Exception {
        JsonNode response = json.readTree(mvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"correct\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return response.get("accessToken").asText();
    }

    private String bearer(String token) { return "Bearer " + token; }

    private void assertCreateStatus(String token, String body, int expectedStatus) throws Exception {
        mvc.perform(post("/api/admin/projects").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(expectedStatus));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
