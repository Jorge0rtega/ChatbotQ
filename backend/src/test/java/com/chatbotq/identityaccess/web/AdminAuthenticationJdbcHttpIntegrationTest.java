package com.chatbotq.identityaccess.web;

import com.chatbotq.identityaccess.application.usecase.CreateAdminUserUseCase;
import com.chatbotq.identityaccess.domain.AdminUser;
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

import java.sql.Timestamp;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "chatbotq.security.bcrypt-strength=4",
    "chatbotq.security.jwt.secret=test-only-signing-key-at-least-thirty-two-bytes-long",
    "chatbotq.security.jwt.issuer=chatbotq-integration"
})
@AutoConfigureMockMvc
class AdminAuthenticationJdbcHttpIntegrationTest {
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

    @BeforeEach
    void seedActiveUser() {
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        AdminUser user = createUser.execute("admin@example.com", "correct", true);
        jdbc.update("update admin_user set status='ACTIVE' where id=?", user.getId());
    }

    @Test
    void loginAndRotationPersistOnlyHashesAndReuseRevokesDatabaseFamily() throws Exception {
        JsonNode login = ok("/api/admin/auth/login",
            Collections.singletonMap("email", "admin@example.com"), "correct");
        String original = login.get("refreshToken").asText();
        String storedHash = jdbc.queryForObject("select token_hash from admin_refresh_session", String.class);
        assertNotEquals(original, storedHash);
        assertEquals(64, storedHash.length());

        JsonNode rotated = okToken("/api/admin/auth/refresh", original);
        String replacement = rotated.get("refreshToken").asText();
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Collections.singletonMap("refreshToken", original))))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Collections.singletonMap("refreshToken", replacement))))
            .andExpect(status().isUnauthorized());
        assertEquals(2, jdbc.queryForObject(
            "select count(*) from admin_refresh_session where revoked_at is not null", Integer.class));
    }

    @Test
    void passwordResetRequiredCompletesWithoutSessionThenOnlyNewPasswordLogsIn() throws Exception {
        jdbc.update("update admin_user set status='PASSWORD_RESET_REQUIRED',failed_login_count=3,"
            + "locked_until=now()+interval '1 hour' where email='admin@example.com'");
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@example.com\",\"password\":\"correct\"}"))
            .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/admin/auth/complete-password-reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ADMIN@example.com\",\"temporaryPassword\":\"correct\","
                    + "\"newPassword\":\"Permanent456\"}"))
            .andExpect(status().isNoContent());

        assertEquals("ACTIVE", jdbc.queryForObject("select status from admin_user", String.class));
        assertEquals(0, jdbc.queryForObject("select failed_login_count from admin_user", Integer.class));
        assertEquals(null, jdbc.queryForObject("select locked_until from admin_user", Timestamp.class));
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@example.com\",\"password\":\"correct\"}"))
            .andExpect(status().isUnauthorized());
        ok("/api/admin/auth/login", Collections.singletonMap("email", "admin@example.com"), "Permanent456");
    }

    @Test
    void completePasswordResetUsesClosedGenericContract() throws Exception {
        String unknown = mvc.perform(post("/api/admin/auth/complete-password-reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"missing@example.com\",\"temporaryPassword\":\"Temporary123\","
                    + "\"newPassword\":\"Permanent456\"}"))
            .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String wrong = mvc.perform(post("/api/admin/auth/complete-password-reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@example.com\",\"temporaryPassword\":\"wrong\","
                    + "\"newPassword\":\"Permanent456\"}"))
            .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals(unknown, wrong);
        mvc.perform(post("/api/admin/auth/complete-password-reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@example.com\",\"email\":\"other@example.com\","
                    + "\"temporaryPassword\":\"correct\",\"newPassword\":\"Permanent456\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/auth/complete-password-reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@example.com\",\"temporaryPassword\":\"correct\","
                    + "\"newPassword\":\"Permanent456\",\"admin\":true}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void logoutRevokesDatabaseFamily() throws Exception {
        String refresh = ok("/api/admin/auth/login",
            Collections.singletonMap("email", "admin@example.com"), "correct")
            .get("refreshToken").asText();
        mvc.perform(post("/api/admin/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Collections.singletonMap("refreshToken", refresh))))
            .andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from admin_refresh_session where revoked_at is not null", Integer.class));
    }

    private JsonNode okToken(String path, String token) throws Exception {
        return json.readTree(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Collections.singletonMap("refreshToken", token))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode ok(String path, java.util.Map<String, String> values, String password) throws Exception {
        java.util.Map<String, String> body = new java.util.HashMap<>(values);
        body.put("password", password);
        return json.readTree(mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
