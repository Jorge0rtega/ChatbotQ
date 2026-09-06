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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "chatbotq.security.bcrypt-strength=4",
    "chatbotq.security.jwt.secret=test-only-signing-key-at-least-thirty-two-bytes-long",
    "chatbotq.security.jwt.issuer=chatbotq-knowledge-crud-test"
})
@AutoConfigureMockMvc
class AdminKnowledgeHttpIntegrationTest {
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

    private UUID projectId;
    private UUID otherProjectId;
    private AdminUser generalAdmin;
    private AdminUser projectAdmin;

    @BeforeEach
    void seed() {
        jdbc.update("delete from knowledge_entry");
        jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user");
        jdbc.update("delete from project");
        projectId = UUID.randomUUID();
        jdbc.update("insert into project (id,name,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())",
            projectId, "Knowledge project");
        otherProjectId = UUID.randomUUID();
        jdbc.update("insert into project (id,name,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())",
            otherProjectId, "Other project");
        generalAdmin = createUser.execute("general-knowledge@example.com", "correct", true);
        projectAdmin = createUser.execute("project-knowledge@example.com", "correct", false);
        jdbc.update("update admin_user set status='ACTIVE' where id in (?,?)", generalAdmin.getId(), projectAdmin.getId());
        jdbc.update("insert into user_project_role (user_id,project_id,role) values (?,?,'PROJECT_ADMIN')",
            projectAdmin.getId(), projectId);
    }

    @Test
    void generalAdminCreatesPendingKnowledgeWithoutEmbeddingProviderCall() throws Exception {
        String token = login("general-knowledge@example.com");
        String body = mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"  ¿Cuál es el horario?  \",\"answer\":\"  De lunes a viernes.  \",\"externalId\":\"faq-hours\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                "http://localhost/api/admin/projects/" + projectId + "/knowledge/[0-9a-f-]{36}")))
            .andExpect(jsonPath("$.projectId").value(projectId.toString()))
            .andExpect(jsonPath("$.question").value("¿Cuál es el horario?"))
            .andExpect(jsonPath("$.answer").value("De lunes a viernes."))
            .andExpect(jsonPath("$.externalId").value("faq-hours"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.embeddingStatus").value("PENDING"))
            .andExpect(jsonPath("$.embeddingRevision").value(1))
            .andReturn().getResponse().getContentAsString();

        JsonNode response = json.readTree(body);
        Map<String, Object> row = jdbc.queryForMap("select project_id,question,answer,external_id,active,"
                + "embedding_status,embedding_revision,embedding,embedded_at from knowledge_entry where id=?",
            UUID.fromString(response.get("id").asText()));
        assertEquals(projectId, row.get("project_id"));
        assertEquals("¿Cuál es el horario?", row.get("question"));
        assertEquals("De lunes a viernes.", row.get("answer"));
        assertEquals("faq-hours", row.get("external_id"));
        assertEquals(Boolean.TRUE, row.get("active"));
        assertEquals("PENDING", row.get("embedding_status"));
        assertEquals(1L, ((Number) row.get("embedding_revision")).longValue());
        assertEquals(null, row.get("embedding"));
        assertEquals(null, row.get("embedded_at"));
        assertEquals(22, ((Number) jdbc.queryForObject("select embedding_input_token_upper_bound from knowledge_entry where id=?", Number.class,
            UUID.fromString(response.get("id").asText()))).intValue());
    }

    @Test
    void rejectsQuestionOverUtf8EmbeddingLimitBeforeKnowledgeInsert() throws Exception {
        String token = login("general-knowledge@example.com");

        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"" + repeat("🙂", 1001) + "\",\"answer\":\"Answer\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));

        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_entry", Integer.class));
    }

    @Test
    void rejectsNonCanonicalProjectIdsBeforeKnowledgeCreation() throws Exception {
        String token = login("general-knowledge@example.com");

        mvc.perform(post("/api/admin/projects/" + projectId.toString().toUpperCase() + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Uppercase?\",\"answer\":\"Rejected\"}"))
            .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_entry", Integer.class));
    }

    @Test
    void projectAdminCreatesOnlyInsideCurrentAssignmentWithoutCrossProjectWrite() throws Exception {
        String token = login("project-knowledge@example.com");

        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Assigned?\",\"answer\":\"Yes\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.projectId").value(projectId.toString()));
        mvc.perform(post("/api/admin/projects/" + otherProjectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Other?\",\"answer\":\"No\"}"))
            .andExpect(status().isForbidden());
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_entry where project_id=?",
            Integer.class, projectId));
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_entry where project_id=?",
            Integer.class, otherProjectId));
    }

    @Test
    void rejectsDuplicateExternalIdWithinProjectWithoutSecondRow() throws Exception {
        String token = login("general-knowledge@example.com");
        String path = "/api/admin/projects/" + projectId + "/knowledge";

        mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"First\",\"answer\":\"Answer\",\"externalId\":\"faq-duplicate\"}"))
            .andExpect(status().isCreated());
        mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Second\",\"answer\":\"Answer\",\"externalId\":\"faq-duplicate\"}"))
            .andExpect(status().isConflict());

        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_entry where project_id=? and external_id=?",
            Integer.class, projectId, "faq-duplicate"));
    }

    @Test
    void doesNotExposeNonExternalIdUniqueConstraintAsExternalIdConflict() throws Exception {
        String token = login("general-knowledge@example.com");
        String path = "/api/admin/projects/" + projectId + "/knowledge";
        mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Unique question\",\"answer\":\"Answer\",\"externalId\":\"first\"}"))
            .andExpect(status().isCreated());
        jdbc.execute("create unique index uq_test_knowledge_question on knowledge_entry(project_id, question)");

        mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Unique question\",\"answer\":\"Answer\",\"externalId\":\"second\"}"))
            .andExpect(status().is5xxServerError())
            .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("knowledge_external_id_conflict")));
    }

    @Test
    void allowsSameExternalIdInDifferentProjects() throws Exception {
        String token = login("general-knowledge@example.com");
        String body = "{\"question\":\"Question\",\"answer\":\"Answer\",\"externalId\":\"shared-id\"}";

        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/admin/projects/" + otherProjectId + "/knowledge").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());

        assertEquals(2, jdbc.queryForObject("select count(*) from knowledge_entry where external_id=?",
            Integer.class, "shared-id"));
    }

    @Test
    void generalAdminGetsNotFoundForUnknownProject() throws Exception {
        String token = login("general-knowledge@example.com");

        mvc.perform(post("/api/admin/projects/" + UUID.randomUUID() + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Question\",\"answer\":\"Answer\"}"))
            .andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_entry", Integer.class));
    }

    @Test
    void projectAdminWithoutAssignmentGetsForbiddenWithoutInsert() throws Exception {
        String token = login("project-knowledge@example.com");
        jdbc.update("delete from user_project_role where user_id=? and project_id=?", projectAdmin.getId(), projectId);

        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Question\",\"answer\":\"Answer\"}"))
            .andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_entry", Integer.class));
    }

    @Test
    void inactiveProjectGetsForbiddenWithoutInsert() throws Exception {
        String token = login("general-knowledge@example.com");
        jdbc.update("update project set status='DISABLED' where id=?", projectId);

        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Question\",\"answer\":\"Answer\"}"))
            .andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_entry", Integer.class));
    }

    @Test
    void disabledIdentityUsingExistingJwtGetsForbiddenWithoutInsert() throws Exception {
        String token = login("project-knowledge@example.com");
        jdbc.update("update admin_user set status='DISABLED' where id=?", projectAdmin.getId());

        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Question\",\"answer\":\"Answer\"}"))
            .andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_entry", Integer.class));
    }

    @Test
    void rejectsUnknownJsonFieldsWithoutInsert() throws Exception {
        String token = login("general-knowledge@example.com");

        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Question\",\"answer\":\"Answer\",\"unknown\":true}"))
            .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_entry", Integer.class));
    }

    @Test
    void rejectsLiteralNullJsonWithoutInsert() throws Exception {
        String token = login("general-knowledge@example.com");

        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("null"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_entry", Integer.class));
    }

    @Test
    void normalizesQuestionAndAnswerAndEnforcesTheirLimits() throws Exception {
        String token = login("general-knowledge@example.com");
        String maximumQuestion = repeat('q', 2000);
        String maximumAnswer = repeat('a', 8000);

        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"  " + maximumQuestion + "  \",\"answer\":\"  " + maximumAnswer + "  \"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.question").value(maximumQuestion))
            .andExpect(jsonPath("$.answer").value(maximumAnswer));
        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"" + repeat('q', 2001) + "\",\"answer\":\"Answer\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Question\",\"answer\":\"" + repeat('a', 8001) + "\"}"))
            .andExpect(status().isBadRequest());
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_entry", Integer.class));
    }

    @Test
    void getAndListExposeNonSecretEmbeddingFailureDiagnostics() throws Exception {
        String token = login("general-knowledge@example.com");
        JsonNode created = createKnowledge(token, projectId, "Question", "Answer", "external-id", true);
        UUID entryId = UUID.fromString(created.get("id").asText());
        jdbc.update("update knowledge_entry set embedding_status='FAILED',embedding_attempt_count=4,"
                + "embedding_last_attempt_at=?,embedding_last_error_code='TRANSIENT',"
                + "embedding_last_error_message='provider timed out' where id=?",
            java.sql.Timestamp.from(java.time.Instant.parse("2025-01-02T03:04:05Z")), entryId);

        mvc.perform(get(knowledgePath(projectId, entryId)).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.embeddingAttemptCount").value(4))
            .andExpect(jsonPath("$.embeddingLastAttemptAt").value("2025-01-02T03:04:05Z"))
            .andExpect(jsonPath("$.embeddingLastErrorCode").value("TRANSIENT"))
            .andExpect(jsonPath("$.embeddingLastErrorMessage").value("provider timed out"));
        mvc.perform(get(knowledgeListPath(projectId)).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].embeddingAttemptCount").value(4))
            .andExpect(jsonPath("$.items[0].embeddingLastAttemptAt").value("2025-01-02T03:04:05Z"))
            .andExpect(jsonPath("$.items[0].embeddingLastErrorCode").value("TRANSIENT"))
            .andExpect(jsonPath("$.items[0].embeddingLastErrorMessage").value("provider timed out"));
    }

    @Test
    void generalAdminReadsExistingKnowledgeWithAllPublicLifecycleFields() throws Exception {
        String token = login("general-knowledge@example.com");
        JsonNode created = createKnowledge(token, projectId, "Question", "Answer", "external-id", false);

        mvc.perform(get(knowledgePath(projectId, created.get("id").asText()))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(created.get("id").asText()))
            .andExpect(jsonPath("$.projectId").value(projectId.toString()))
            .andExpect(jsonPath("$.question").value("Question"))
            .andExpect(jsonPath("$.answer").value("Answer"))
            .andExpect(jsonPath("$.externalId").value("external-id"))
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.embeddingStatus").value("PENDING"))
            .andExpect(jsonPath("$.embeddingRevision").value(1))
            .andExpect(jsonPath("$.createdAt").value(created.get("createdAt").asText()))
            .andExpect(jsonPath("$.updatedAt").value(created.get("updatedAt").asText()));
    }

    @Test
    void assignedProjectAdminReadsOnlyKnowledgeInCurrentAssignment() throws Exception {
        String generalToken = login("general-knowledge@example.com");
        JsonNode assigned = createKnowledge(generalToken, projectId, "Assigned", "Answer", null, true);
        JsonNode other = createKnowledge(generalToken, otherProjectId, "Other", "Answer", null, true);
        String projectToken = login("project-knowledge@example.com");

        mvc.perform(get(knowledgePath(projectId, assigned.get("id").asText()))
                .header("Authorization", "Bearer " + projectToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(assigned.get("id").asText()));
        mvc.perform(get(knowledgePath(otherProjectId, other.get("id").asText()))
                .header("Authorization", "Bearer " + projectToken))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void projectAdminWithoutCurrentAssignmentGetsForbiddenWithoutEntryLeak() throws Exception {
        String generalToken = login("general-knowledge@example.com");
        JsonNode entry = createKnowledge(generalToken, projectId, "Question", "Answer", null, true);
        String projectToken = login("project-knowledge@example.com");
        jdbc.update("delete from user_project_role where user_id=? and project_id=?", projectAdmin.getId(), projectId);

        mvc.perform(get(knowledgePath(projectId, entry.get("id").asText()))
                .header("Authorization", "Bearer " + projectToken))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void visibleGeneralAdminGetsNotFoundForNonexistentKnowledgeEntry() throws Exception {
        String token = login("general-knowledge@example.com");

        mvc.perform(get(knowledgePath(projectId, UUID.randomUUID()))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
    }

    @Test
    void mismatchedKnowledgeEntryDoesNotLeakAcrossProjectPath() throws Exception {
        String generalToken = login("general-knowledge@example.com");
        JsonNode otherEntry = createKnowledge(generalToken, otherProjectId, "Other", "Answer", null, true);
        String projectToken = login("project-knowledge@example.com");

        mvc.perform(get(knowledgePath(projectId, otherEntry.get("id").asText()))
                .header("Authorization", "Bearer " + generalToken))
            .andExpect(status().isNotFound());
        mvc.perform(get(knowledgePath(otherProjectId, otherEntry.get("id").asText()))
                .header("Authorization", "Bearer " + projectToken))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void inactiveProjectGetsForbiddenBeforeKnowledgeLookup() throws Exception {
        String token = login("general-knowledge@example.com");
        JsonNode entry = createKnowledge(token, projectId, "Question", "Answer", null, true);
        jdbc.update("update project set status='DISABLED' where id=?", projectId);

        mvc.perform(get(knowledgePath(projectId, entry.get("id").asText()))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
        mvc.perform(get(knowledgePath(projectId, UUID.randomUUID()))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void rejectsNonCanonicalProjectAndKnowledgeEntryIdsBeforeLookup() throws Exception {
        String token = login("general-knowledge@example.com");
        JsonNode entry = createKnowledge(token, projectId, "Question", "Answer", null, true);

        mvc.perform(get(knowledgePath(projectId.toString().toUpperCase(), entry.get("id").asText()))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(get(knowledgePath(projectId, entry.get("id").asText().toUpperCase()))
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void generalAndAssignedAdminsListOnlyTheCurrentProject() throws Exception {
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000001", "Assigned", "Answer", "assigned-id", "2025-01-01T00:00:00Z");
        insertKnowledge(otherProjectId, "00000000-0000-0000-0000-000000000002", "Other", "Answer", "other-id", "2025-01-01T00:00:00Z");
        String generalToken = login("general-knowledge@example.com");
        String projectToken = login("project-knowledge@example.com");

        mvc.perform(get(knowledgeListPath(projectId) + "?page=0&size=20").header("Authorization", "Bearer " + generalToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].projectId").value(projectId.toString()));
        mvc.perform(get(knowledgeListPath(projectId) + "?page=0&size=20").header("Authorization", "Bearer " + projectToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000001"));
        mvc.perform(get(knowledgeListPath(otherProjectId) + "?page=0&size=20").header("Authorization", "Bearer " + projectToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void listFailsClosedForUnassignedInactiveAndUnknownProjects() throws Exception {
        String generalToken = login("general-knowledge@example.com");
        String projectToken = login("project-knowledge@example.com");
        jdbc.update("delete from user_project_role where user_id=? and project_id=?", projectAdmin.getId(), projectId);
        mvc.perform(get(knowledgeListPath(projectId)).header("Authorization", "Bearer " + projectToken))
            .andExpect(status().isForbidden());
        jdbc.update("update project set status='DISABLED' where id=?", projectId);
        mvc.perform(get(knowledgeListPath(projectId)).header("Authorization", "Bearer " + generalToken))
            .andExpect(status().isForbidden());
        mvc.perform(get(knowledgeListPath(UUID.randomUUID())).header("Authorization", "Bearer " + generalToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void listUsesStableUpdatedAtThenIdPagesAndReturnsEmptyOutOfRangePage() throws Exception {
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000003", "Older", "Answer", null, "2025-01-01T00:00:00Z");
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000002", "Tie lower", "Answer", null, "2025-01-02T00:00:00Z");
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000004", "Tie higher", "Answer", null, "2025-01-02T00:00:00Z");
        String token = login("general-knowledge@example.com");

        mvc.perform(get(knowledgeListPath(projectId) + "?page=0&size=2").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000002"))
            .andExpect(jsonPath("$.items[1].id").value("00000000-0000-0000-0000-000000000004"))
            .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get(knowledgeListPath(projectId) + "?page=1&size=2").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000003"));
        mvc.perform(get(knowledgeListPath(projectId) + "?page=9&size=2").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listNormalizesCaseInsensitiveQuestionAndExternalIdSearchOnly() throws Exception {
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000011", "Alpha question", "Secret", null, "2025-01-01T00:00:00Z");
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000012", "Other", "Alpha answer only", "other-id", "2025-01-01T00:00:00Z");
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000013", "Different", "Secret", "external-alpha", "2025-01-01T00:00:00Z");
        String token = login("general-knowledge@example.com");

        mvc.perform(get(knowledgeListPath(projectId) + "?page=0&size=20").param("q", " ALPHA ").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000011"))
            .andExpect(jsonPath("$.items[1].id").value("00000000-0000-0000-0000-000000000013"));
        mvc.perform(get(knowledgeListPath(projectId) + "?page=0&size=20&q=").header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
        mvc.perform(get(knowledgeListPath(projectId) + "?page=0&size=20&q=" + repeat('q', 201)).header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listTreatsPercentUnderscoreAndBackslashQueriesAsLiteralText() throws Exception {
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000021", "Percent % marker", "Answer", null, "2025-01-01T00:00:00Z");
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000022", "Percent marker", "Answer", null, "2025-01-01T00:00:00Z");
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000023", "Underscore _ marker", "Answer", null, "2025-01-01T00:00:00Z");
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000024", "Underscore x marker", "Answer", null, "2025-01-01T00:00:00Z");
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000025", "Backslash \\ marker", "Answer", null, "2025-01-01T00:00:00Z");
        insertKnowledge(projectId, "00000000-0000-0000-0000-000000000026", "Backslash marker", "Answer", null, "2025-01-01T00:00:00Z");
        String token = login("general-knowledge@example.com");

        mvc.perform(get(knowledgeListPath(projectId) + "?page=0&size=20").param("q", "%").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000021"));
        mvc.perform(get(knowledgeListPath(projectId) + "?page=0&size=20").param("q", "_").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000023"));
        mvc.perform(get(knowledgeListPath(projectId) + "?page=0&size=20").param("q", "\\").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value("00000000-0000-0000-0000-000000000025"));
    }

    @Test
    void listRejectsInvalidBoundsAndNonCanonicalProjectId() throws Exception {
        String token = login("general-knowledge@example.com");
        mvc.perform(get(knowledgeListPath(projectId) + "?page=-1&size=20").header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
        mvc.perform(get(knowledgeListPath(projectId) + "?page=0&size=0").header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
        mvc.perform(get(knowledgeListPath(projectId) + "?page=" + Integer.MAX_VALUE + "&size=100").header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
        mvc.perform(get(knowledgeListPath(projectId.toString().toUpperCase())).header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
    }

    private static String repeat(String value, int count) {
        StringBuilder output = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) output.append(value);
        return output.toString();
    }

    private static String repeat(char character, int count) {
        StringBuilder value = new StringBuilder(count);
        for (int index = 0; index < count; index++) value.append(character);
        return value.toString();
    }

    private void insertKnowledge(UUID targetProjectId, String id, String question, String answer, String externalId,
                                 String updatedAt) {
        jdbc.update("insert into knowledge_entry (id,project_id,question,answer,external_id,active,embedding_input_token_upper_bound,created_at,updated_at) "
                + "values (?,?,?,?,?,true,1,?,?)", UUID.fromString(id), targetProjectId, question, answer, externalId,
            java.sql.Timestamp.from(java.time.Instant.parse(updatedAt)), java.sql.Timestamp.from(java.time.Instant.parse(updatedAt)));
    }

    private static String knowledgeListPath(Object targetProjectId) {
        return "/api/admin/projects/" + targetProjectId + "/knowledge";
    }

    private JsonNode createKnowledge(String token, UUID targetProjectId, String question, String answer,
                                     String externalId, boolean active) throws Exception {
        String externalIdField = externalId == null ? "" : ",\"externalId\":\"" + externalId + "\"";
        return json.readTree(mvc.perform(post("/api/admin/projects/" + targetProjectId + "/knowledge")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"" + question + "\",\"answer\":\"" + answer + "\""
                    + externalIdField + ",\"active\":" + active + "}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static String knowledgePath(Object targetProjectId, Object entryId) {
        return "/api/admin/projects/" + targetProjectId + "/knowledge/" + entryId;
    }

    private String login(String email) throws Exception {
        JsonNode response = json.readTree(mvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"correct\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return response.get("accessToken").asText();
    }
}
