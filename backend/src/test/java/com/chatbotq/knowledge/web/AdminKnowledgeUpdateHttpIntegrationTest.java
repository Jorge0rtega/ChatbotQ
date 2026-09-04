package com.chatbotq.knowledge.web;

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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "chatbotq.security.bcrypt-strength=4",
    "chatbotq.security.jwt.secret=test-only-signing-key-at-least-thirty-two-bytes-long",
    "chatbotq.security.jwt.issuer=chatbotq-knowledge-update-test"
})
@AutoConfigureMockMvc
class AdminKnowledgeUpdateHttpIntegrationTest {
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
    @Autowired CreateAdminUserUseCase createUser;
    private UUID projectId;
    private UUID otherProjectId;
    private AdminUser projectAdmin;

    @BeforeEach void seed() {
        jdbc.update("delete from knowledge_entry");
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        projectId = UUID.randomUUID(); otherProjectId = UUID.randomUUID();
        jdbc.update("insert into project (id,name,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())", projectId, "Project");
        jdbc.update("insert into project (id,name,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())", otherProjectId, "Other");
        AdminUser general = createUser.execute("general-update@example.com", "correct", true);
        projectAdmin = createUser.execute("project-update@example.com", "correct", false);
        jdbc.update("update admin_user set status='ACTIVE' where id in (?,?)", general.getId(), projectAdmin.getId());
        jdbc.update("insert into user_project_role (user_id,project_id,role) values (?,?,'PROJECT_ADMIN')", projectAdmin.getId(), projectId);
    }

    @Test void authorizedUpdateReturnsVersionAndQuestionInvalidatesOnlyEmbeddingLifecycle() throws Exception {
        String token = login("general-update@example.com"); JsonNode entry = create(token, projectId, "Question", "Answer", "external", true); UUID id = UUID.fromString(entry.get("id").asText());
        setReady(id, 7);
        mvc.perform(put(path(projectId, id)).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\" Changed \",\"answer\":\" New answer \",\"externalId\":\" changed-id \",\"active\":false,\"version\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.question").value("Changed")).andExpect(jsonPath("$.answer").value("New answer"))
            .andExpect(jsonPath("$.externalId").value("changed-id")).andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.embeddingStatus").value("PENDING")).andExpect(jsonPath("$.embeddingRevision").value(8));
        Map<String,Object> row = jdbc.queryForMap("select version,embedding,embedded_at,embedding_attempt_count,embedding_last_attempt_at,embedding_last_error_code,embedding_last_error_message from knowledge_entry where id=?", id);
        assertEquals(1L, ((Number) row.get("version")).longValue()); assertEquals(null, row.get("embedding")); assertEquals(null, row.get("embedded_at"));
        assertEquals(0, ((Number) row.get("embedding_attempt_count")).intValue()); assertEquals(null, row.get("embedding_last_attempt_at"));
        assertEquals(null, row.get("embedding_last_error_code")); assertEquals(null, row.get("embedding_last_error_message"));
    }

    @Test void answerOnlyAndActivationOnlyPreserveVectorLifecycleRevisionAndIncrementVersion() throws Exception {
        String token = login("general-update@example.com"); JsonNode entry = create(token, projectId, "Question", "Answer", "external", true); UUID id = UUID.fromString(entry.get("id").asText()); setReady(id, 7);
        mvc.perform(update(token, id, "Question", "New answer", "external", true, 0)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.embeddingRevision").value(7));
        mvc.perform(update(token, id, "Question", "New answer", "external", false, 1)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.embeddingStatus").value("READY"));
        Map<String,Object> row = jdbc.queryForMap("select vector_dims(embedding) dimensions,embedded_at,embedding_attempt_count,embedding_last_attempt_at,embedding_last_error_code,embedding_last_error_message,embedding_revision from knowledge_entry where id=?", id);
        assertEquals(1536, ((Number) row.get("dimensions")).intValue()); assertEquals(3, ((Number) row.get("embedding_attempt_count")).intValue()); assertEquals("TRANSIENT", row.get("embedding_last_error_code")); assertEquals(7L, ((Number) row.get("embedding_revision")).longValue());
    }

    @Test void noOpDoesNotBumpVersionAndStaleVersionConflictsWithoutWrite() throws Exception {
        String token = login("general-update@example.com"); JsonNode entry = create(token, projectId, "Question", "Answer", null, true); UUID id = UUID.fromString(entry.get("id").asText()); jdbc.update("update knowledge_entry set version=4 where id=?", id);
        mvc.perform(update(token, id, "Question", "Answer", null, true, 4)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4));
        mvc.perform(update(token, id, "Stale", "Answer", null, true, 3)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("knowledge_version_conflict"));
        Map<String,Object> row = jdbc.queryForMap("select question,version from knowledge_entry where id=?", id); assertEquals("Question", row.get("question")); assertEquals(4L, ((Number) row.get("version")).longValue());
    }

    @Test void crossProjectNoRoleAndInactiveProjectDenyWithoutWriteOrLeak() throws Exception {
        String general = login("general-update@example.com"); JsonNode entry = create(general, projectId, "Question", "Answer", null, true); UUID id = UUID.fromString(entry.get("id").asText()); String project = login("project-update@example.com");
        mvc.perform(put(path(otherProjectId, id)).header("Authorization", "Bearer " + general).contentType(MediaType.APPLICATION_JSON).content(body("Changed", "Answer", null, true, 0))).andExpect(status().isNotFound());
        mvc.perform(put(path(otherProjectId, id)).header("Authorization", "Bearer " + project).contentType(MediaType.APPLICATION_JSON).content(body("Changed", "Answer", null, true, 0))).andExpect(status().isForbidden());
        jdbc.update("delete from user_project_role where user_id=? and project_id=?", projectAdmin.getId(), projectId);
        mvc.perform(put(path(projectId, id)).header("Authorization", "Bearer " + project).contentType(MediaType.APPLICATION_JSON).content(body("Changed", "Answer", null, true, 0))).andExpect(status().isForbidden());
        jdbc.update("update project set status='DISABLED' where id=?", projectId);
        mvc.perform(put(path(projectId, id)).header("Authorization", "Bearer " + general).contentType(MediaType.APPLICATION_JSON).content(body("Changed", "Answer", null, true, 0))).andExpect(status().isForbidden());
        assertEquals("Question", jdbc.queryForObject("select question from knowledge_entry where id=?", String.class, id));
    }

    @Test void duplicateExternalIdConflictsWithoutPartialPutWrite() throws Exception {
        String token = login("general-update@example.com");
        create(token, projectId, "First", "Answer", "duplicate", true);
        JsonNode entry = create(token, projectId, "Question", "Answer", "original", true);
        UUID id = UUID.fromString(entry.get("id").asText());
        mvc.perform(update(token, id, "Changed", "Changed answer", "duplicate", false, 0))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("knowledge_external_id_conflict"));
        Map<String,Object> row = jdbc.queryForMap("select question,answer,external_id,active,version from knowledge_entry where id=?", id);
        assertEquals("Question", row.get("question")); assertEquals("Answer", row.get("answer"));
        assertEquals("original", row.get("external_id")); assertEquals(true, row.get("active"));
        assertEquals(0L, ((Number) row.get("version")).longValue());
    }

    @Test void putBodyIsClosedAndRequiresNonNullBoundedFieldsAndNonNegativeVersion() throws Exception {
        String token = login("general-update@example.com"); JsonNode entry = create(token, projectId, "Question", "Answer", null, true); String target = path(projectId, entry.get("id").asText());
        String[] invalid = { "null", "{\"question\":\"Question\",\"answer\":\"Answer\",\"active\":true}", "{\"question\":null,\"answer\":\"Answer\",\"active\":true,\"version\":1}", "{\"question\":\"Question\",\"answer\":\"Answer\",\"active\":null,\"version\":1}", "{\"question\":\"Question\",\"question\":\"Overridden\",\"answer\":\"Answer\",\"active\":true,\"version\":1}", "{\"question\":\"Question\",\"answer\":\"Answer\",\"active\":true,\"version\":-1}", "{\"question\":\"Question\",\"answer\":\"Answer\",\"active\":true,\"version\":1,\"unknown\":true}", body(repeat('q', 2001), "Answer", null, true, 1), body("Question", repeat('a', 8001), null, true, 1), body("Question", "Answer", repeat('e', 256), true, 1) };
        for (String invalidBody : invalid) mvc.perform(put(target).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(invalidBody)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder update(String token, UUID id, String question, String answer, String externalId, boolean active, long version) {
        return put(path(projectId, id)).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body(question, answer, externalId, active, version));
    }
    private JsonNode create(String token, UUID targetProject, String question, String answer, String externalId, boolean active) throws Exception {
        return json.readTree(mvc.perform(post("/api/admin/projects/" + targetProject + "/knowledge").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(createBody(question, answer, externalId, active))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private static String body(String q, String a, String external, boolean active, long version) { return "{\"question\":\"" + q + "\",\"answer\":\"" + a + "\"" + (external == null ? "" : ",\"externalId\":\"" + external + "\"") + ",\"active\":" + active + ",\"version\":" + version + "}"; }
    private static String createBody(String q, String a, String external, boolean active) { return "{\"question\":\"" + q + "\",\"answer\":\"" + a + "\"" + (external == null ? "" : ",\"externalId\":\"" + external + "\"") + ",\"active\":" + active + "}"; }
    private void setReady(UUID id, long revision) { jdbc.update("update knowledge_entry set embedding_status='READY',embedding_revision=?,embedding=('[1,' || repeat('0,',1534) || '0]')::vector,embedded_at=now(),embedding_attempt_count=3,embedding_last_attempt_at=now(),embedding_last_error_code='TRANSIENT',embedding_last_error_message='retry me' where id=?", revision, id); }
    private static String path(Object p, Object id) { return "/api/admin/projects/" + p + "/knowledge/" + id; }
    private String login(String email) throws Exception { return json.readTree(mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"correct\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText(); }
    private static String repeat(char value, int count) { StringBuilder output = new StringBuilder(count); for (int i = 0; i < count; i++) output.append(value); return output.toString(); }
}
