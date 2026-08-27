package com.chatbotq.identityaccess.web;

import com.chatbotq.identityaccess.application.usecase.CreateAdminUserUseCase;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.infrastructure.security.BCryptPasswordHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    "chatbotq.security.jwt.issuer=chatbotq-user-crud-test"
})
@AutoConfigureMockMvc
class AdminUsersHttpIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("chatbotq").withUsername("chatbotq").withPassword("chatbotq-test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired BCryptPasswordHasher passwords;
    @Autowired CreateAdminUserUseCase createUser;
    private AdminUser general;
    private AdminUser projectAdmin;

    @BeforeEach void seed() {
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from user_project_role");
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        general = active("general-users@example.com", true);
        projectAdmin = active("project-users@example.com", false);
    }

    @Test
    void generalAdminExecutesSafeCrudAndResetWithRealJwt() throws Exception {
        String token = login("general-users@example.com");
        String body = mvc.perform(post("/api/admin/users").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\" New@Example.COM \",\"temporaryPassword\":\"Temporary123\",\"role\":\"PROJECT_ADMIN\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", matchesPattern("http://localhost/api/admin/users/[0-9a-f-]{36}")))
            .andExpect(jsonPath("$.email", is("new@example.com")))
            .andExpect(jsonPath("$.role", is("PROJECT_ADMIN")))
            .andExpect(jsonPath("$.status", is("PASSWORD_RESET_REQUIRED")))
            .andExpect(jsonPath("$.temporaryPassword").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).get("id").asText());
        String oldHash = jdbc.queryForObject("select password_hash from admin_user where id=?", String.class, id);
        assertFalse(oldHash.contains("Temporary123"));
        org.junit.jupiter.api.Assertions.assertTrue(passwords.matches("Temporary123", oldHash));

        mvc.perform(get("/api/admin/users/" + id).header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email", is("new@example.com")))
            .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(get("/api/admin/users?page=0&size=2").header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)))
            .andExpect(jsonPath("$.totalElements", is(3))).andExpect(jsonPath("$.totalPages", is(2)));
        mvc.perform(put("/api/admin/users/" + id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"Changed@Example.com\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email", is("changed@example.com")));
        UUID stateTarget = projectAdmin.getId();
        mvc.perform(post("/api/admin/users/" + stateTarget + "/deactivate").header("Authorization", bearer(token)))
            .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(post("/api/admin/users/" + stateTarget + "/deactivate").header("Authorization", bearer(token)))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/users/" + stateTarget + "/activate").header("Authorization", bearer(token)))
            .andExpect(status().isNoContent());
        JsonNode session = loginResponse("project-users@example.com", "Correct12345");
        String oldTargetHash = jdbc.queryForObject("select password_hash from admin_user where id=?", String.class, stateTarget);
        mvc.perform(post("/api/admin/users/" + stateTarget + "/reset-password").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"temporaryPassword\":\"Replacement123\"}"))
            .andExpect(status().isNoContent()).andExpect(content().string(""));
        String newHash = jdbc.queryForObject("select password_hash from admin_user where id=?", String.class, stateTarget);
        assertNotEquals(oldTargetHash, newHash);
        org.junit.jupiter.api.Assertions.assertTrue(passwords.matches("Replacement123", newHash));
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + session.get("refreshToken").asText() + "\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users/" + id).header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("PASSWORD_RESET_REQUIRED")))
            .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void projectAdminIsAlwaysForbiddenWithoutEnumerationAndExistingJwtUsesCurrentDatabaseState() throws Exception {
        String projectToken = login("project-users@example.com");
        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/admin/users/" + general.getId()).header("Authorization", bearer(projectToken)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users/" + missing).header("Authorization", bearer(projectToken)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization", bearer(projectToken)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users").header("Authorization", bearer(projectToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.com\",\"temporaryPassword\":\"Temporary123\",\"role\":\"PROJECT_ADMIN\"}"))
            .andExpect(status().isForbidden());

        String generalToken = login("general-users@example.com");
        jdbc.update("update admin_user set is_general_admin=false where id=?", general.getId());
        mvc.perform(get("/api/admin/users/" + projectAdmin.getId()).header("Authorization", bearer(generalToken)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users/" + missing).header("Authorization", bearer(generalToken)))
            .andExpect(status().isForbidden());
    }

    @Test
    void returnsExpectedClassificationAndRejectsMassAssignmentMalformedJsonUuidAndPagination() throws Exception {
        String token = login("general-users@example.com");
        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/admin/users/" + missing).header("Authorization", bearer(token)))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/users/not-a-uuid").header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users?page=" + Integer.MAX_VALUE + "&size=100").header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users/" + general.getId())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/users").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/users").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"mass@example.com\",\"temporaryPassword\":\"Temporary123\",\"role\":\"PROJECT_ADMIN\",\"isGeneralAdmin\":true}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/users/" + projectAdmin.getId()).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"GENERAL-USERS@example.com\"}"))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/users/" + general.getId() + "/deactivate").header("Authorization", bearer(token)))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/users/" + general.getId() + "/reset-password").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"temporaryPassword\":\"Replacement123\"}"))
            .andExpect(status().isConflict());
        jdbc.update("update admin_user set status='PASSWORD_RESET_REQUIRED' where id=?", projectAdmin.getId());
        mvc.perform(post("/api/admin/users/" + projectAdmin.getId() + "/activate")
                .header("Authorization", bearer(token)))
            .andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/users/" + projectAdmin.getId()).header("Authorization", bearer(token)))
            .andExpect(status().isMethodNotAllowed());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
            "select count(*) from admin_user where id=?", Integer.class, projectAdmin.getId()));
    }

    @Test
    void managesProjectAssignmentsAtomicallyAndExistingJwtMeReflectsCurrentActiveProjects() throws Exception {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        jdbc.update("insert into project(id,name,status) values (?,?,?)", first, "First", "ACTIVE");
        jdbc.update("insert into project(id,name,status) values (?,?,?)", second, "Second", "DISABLED");
        String generalToken = login("general-users@example.com");
        String projectToken = login("project-users@example.com");

        mvc.perform(put("/api/admin/users/" + projectAdmin.getId() + "/projects")
                .header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectIds\":[\"" + second + "\",\"" + first + "\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.projectIds[0]", is(first.toString())))
            .andExpect(jsonPath("$.projectIds[1]", is(second.toString())))
            .andExpect(jsonPath("$.projectIds", hasSize(2)));
        mvc.perform(get("/api/admin/users/" + projectAdmin.getId() + "/projects")
                .header("Authorization", bearer(generalToken)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.projectIds[0]", is(first.toString())));

        mvc.perform(get("/api/admin/auth/me").header("Authorization", bearer(projectToken)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.projectIds", hasSize(1)))
            .andExpect(jsonPath("$.projectIds[0]", is(first.toString())));
        jdbc.update("update admin_user set email='current-role@example.com',is_general_admin=true where id=?",
            projectAdmin.getId());
        mvc.perform(get("/api/admin/auth/me").header("Authorization", bearer(projectToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email", is("current-role@example.com")))
            .andExpect(jsonPath("$.generalAdmin", is(true)))
            .andExpect(jsonPath("$.projectIds", hasSize(0)));
        jdbc.update("update admin_user set is_general_admin=false where id=?", projectAdmin.getId());
        jdbc.update("update project set status='DISABLED' where id=?", first);
        mvc.perform(get("/api/admin/auth/me").header("Authorization", bearer(projectToken)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.projectIds", hasSize(0)));
        jdbc.update("update project set status='ACTIVE' where id=?", first);
        mvc.perform(put("/api/admin/users/" + projectAdmin.getId() + "/projects")
                .header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectIds\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.projectIds", hasSize(0)));
        mvc.perform(get("/api/admin/auth/me").header("Authorization", bearer(projectToken)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.projectIds", hasSize(0)));
        mvc.perform(get("/api/admin/auth/me").header("Authorization", bearer(generalToken)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.projectIds", hasSize(0)));
    }

    @Test
    void assignmentContractIsClosedCanonicalAndDoesNotEnumerateForUnauthorizedActor() throws Exception {
        UUID project = UUID.randomUUID();
        jdbc.update("insert into project(id,name,status) values (?,?,?)", project, "Project", "ACTIVE");
        String generalToken = login("general-users@example.com");
        String projectToken = login("project-users@example.com");
        String path = "/api/admin/users/" + projectAdmin.getId() + "/projects";

        mvc.perform(get(path).header("Authorization", bearer(projectToken))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users/" + UUID.randomUUID() + "/projects")
            .header("Authorization", bearer(projectToken))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users/" + general.getId() + "/projects")
            .header("Authorization", bearer(generalToken))).andExpect(status().isConflict());
        String upperTarget = projectAdmin.getId().toString().toUpperCase(java.util.Locale.ROOT);
        mvc.perform(get("/api/admin/users/" + upperTarget + "/projects")
            .header("Authorization", bearer(generalToken))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/users/" + upperTarget + "/projects")
            .header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectIds\":[]}" )).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users/1-1-1-1-1/projects")
            .header("Authorization", bearer(generalToken))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/users/ " + projectAdmin.getId() + "/projects")
            .header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectIds\":[]}" )).andExpect(status().isBadRequest());
        mvc.perform(put(path).header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
            .content("{}" )).andExpect(status().isBadRequest());
        mvc.perform(put(path).header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectIds\":[\""+project+"\"],\"extra\":true}" )).andExpect(status().isBadRequest());
        mvc.perform(put(path).header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectIds\":[\""+project+"\"],\"projectIds\":[]}" )).andExpect(status().isBadRequest());
        mvc.perform(put(path).header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectIds\":[\""+project+"\",\""+project+"\"]}" )).andExpect(status().isBadRequest());
        mvc.perform(put(path).header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectIds\":[null]}" )).andExpect(status().isBadRequest());
        mvc.perform(put(path).header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectIds\":[\""+project.toString().toUpperCase(java.util.Locale.ROOT)+"\"]}" ))
            .andExpect(status().isBadRequest());

        mvc.perform(put(path).header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectIds\":[\""+project+"\"]}" )).andExpect(status().isOk());
        mvc.perform(put(path).header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON)
            .content("{\"projectIds\":[\""+project+"\",\""+UUID.randomUUID()+"\"]}" ))
            .andExpect(status().isNotFound());
        assertEquals(1, jdbc.queryForObject("select count(*) from user_project_role where user_id=? and project_id=?",
            Integer.class, projectAdmin.getId(), project));

        jdbc.update("update admin_user set status='DISABLED' where id=?", general.getId());
        mvc.perform(get(path).header("Authorization", bearer(generalToken))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users/" + UUID.randomUUID() + "/projects")
            .header("Authorization", bearer(generalToken))).andExpect(status().isForbidden());
    }

    @Test
    void putAssignmentsClassifiesActorAndTargetsAndAcceptsEveryManagedProjectAdminStatus() throws Exception {
        UUID project = UUID.randomUUID();
        jdbc.update("insert into project(id,name,status) values (?,?,?)", project, "Matrix", "ACTIVE");
        String generalToken = login("general-users@example.com");
        String projectToken = login("project-users@example.com");
        String body = "{\"projectIds\":[\"" + project + "\"]}";

        mvc.perform(put("/api/admin/users/" + UUID.randomUUID() + "/projects")
            .header("Authorization", bearer(projectToken)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/users/" + UUID.randomUUID() + "/projects")
            .header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound());
        mvc.perform(put("/api/admin/users/" + general.getId() + "/projects")
            .header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict());

        for (String managedStatus : new String[]{"ACTIVE", "DISABLED", "PASSWORD_RESET_REQUIRED"}) {
            jdbc.update("update admin_user set status=? where id=?", managedStatus, projectAdmin.getId());
            mvc.perform(put("/api/admin/users/" + projectAdmin.getId() + "/projects")
                .header("Authorization", bearer(generalToken)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.projectIds[0]", is(project.toString())));
        }
    }

    @Test
    void putAssignmentsAcceptsExactlyOneThousandAndRejectsOneThousandOne() throws Exception {
        String token = login("general-users@example.com");
        List<UUID> ids = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            UUID id = UUID.randomUUID(); ids.add(id);
            rows.add(new Object[]{id, "Boundary-" + i, "ACTIVE"});
        }
        jdbc.batchUpdate("insert into project(id,name,status) values (?,?,?)", rows);
        String path = "/api/admin/users/" + projectAdmin.getId() + "/projects";
        String thousand = json.writeValueAsString(java.util.Collections.singletonMap("projectIds", ids));
        mvc.perform(put(path).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
            .content(thousand)).andExpect(status().isOk()).andExpect(jsonPath("$.projectIds", hasSize(1000)));

        ids.add(UUID.randomUUID());
        mvc.perform(put(path).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Collections.singletonMap("projectIds", ids))))
            .andExpect(status().isBadRequest());
        assertEquals(1000, jdbc.queryForObject(
            "select count(*) from user_project_role where user_id=?", Integer.class, projectAdmin.getId()));
    }

    private AdminUser active(String email, boolean generalAdmin) {
        AdminUser user = createUser.execute(email, "Correct12345", generalAdmin);
        jdbc.update("update admin_user set status='ACTIVE' where id=?", user.getId());
        return user;
    }
    private String login(String email) throws Exception {
        return loginResponse(email, "Correct12345").get("accessToken").asText();
    }
    private JsonNode loginResponse(String email, String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private String bearer(String token) { return "Bearer " + token; }
}
