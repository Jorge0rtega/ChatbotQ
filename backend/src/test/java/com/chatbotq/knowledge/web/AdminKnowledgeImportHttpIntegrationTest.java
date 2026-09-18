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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@Testcontainers
@SpringBootTest(properties = {
    "chatbotq.security.bcrypt-strength=4",
    "chatbotq.security.jwt.secret=test-only-signing-key-at-least-thirty-two-bytes-long",
    "chatbotq.security.jwt.issuer=chatbotq-knowledge-import-test"
})
@AutoConfigureMockMvc
class AdminKnowledgeImportHttpIntegrationTest {
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
        jdbc.update("delete from knowledge_import_row"); jdbc.update("delete from knowledge_import_job");
        jdbc.update("delete from knowledge_entry"); jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user"); jdbc.update("delete from project");
        projectId = UUID.randomUUID(); otherProjectId = UUID.randomUUID();
        jdbc.update("insert into project (id,name,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())", projectId, "Project");
        jdbc.update("insert into project (id,name,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())", otherProjectId, "Other project");
        AdminUser general = createUser.execute("general-import@example.com", "correct", true);
        projectAdmin = createUser.execute("project-import@example.com", "correct", false);
        jdbc.update("update admin_user set status='ACTIVE' where id in (?,?)", general.getId(), projectAdmin.getId());
        jdbc.update("insert into user_project_role (user_id,project_id,role) values (?,?,'PROJECT_ADMIN')", projectAdmin.getId(), otherProjectId);
    }

    @Test void authorizedPreviewImportPersistsFailedJobForInvalidRowsAndExposesBoundedDetail() throws Exception {
        String token = login("general-import@example.com");
        MockMultipartFile file = csv("knowledge.csv", "question,answer,external_id,active\n  What is it?  ,  An answer.  , external-1 ,false\n,Missing question,,false\n");
        String body = mvc.perform(multipart(importPath()).file(file).param("strategy", "UPSERT").header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated()).andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("http://localhost" + importPath() + "/[0-9a-f-]{36}")))
            .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.totalRows").value(2))
            .andExpect(jsonPath("$.validRows").value(1)).andExpect(jsonPath("$.invalidRows").value(1))
            .andExpect(jsonPath("$.rows").doesNotExist()).andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(json.readTree(body).get("id").asText());
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_import_job where id=? and status='FAILED' and total_rows=2 and valid_rows=1 and invalid_rows=1", Integer.class, jobId).intValue());
        assertEquals(false, jdbc.queryForObject("select active from knowledge_import_row where import_job_id=? and row_number=2", Boolean.class, jobId));
        mvc.perform(get(importPath() + "/" + jobId).param("page", "0").param("size", "1").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1)).andExpect(jsonPath("$.totalRowElements").value(2))
            .andExpect(jsonPath("$.rows[0].question").value("What is it?")).andExpect(jsonPath("$.rows[0].active").value(false))
            .andExpect(jsonPath("$.rows[0].status").value("VALID"));
        mvc.perform(get(importPath() + "/" + jobId).param("page", "1").param("size", "1").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].status").value("INVALID"))
            .andExpect(jsonPath("$.rows[0].errors[0]").value("required"));
    }

    @Test void fileLevelParseFailurePersistsFailedTraceWithoutRows() throws Exception {
        String token = login("general-import@example.com");
        String body = mvc.perform(multipart(importPath()).file(csv("broken.csv", "question,answer\n\"unterminated")).param("strategy", "CREATE_ONLY").header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.totalRows").value(0)).andExpect(jsonPath("$.errorSummary[0]").value("csv_syntax"))
            .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(json.readTree(body).get("id").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_import_row where import_job_id=?", Integer.class, jobId).intValue());
    }

    @Test void executeDrainsReadyCreateOnlyImportAndCompletesIt() throws Exception {
        String token = login("general-import@example.com");
        String body = mvc.perform(multipart(importPath()).file(csv("ready.csv", "question,answer,external_id,active\nWhat?,Answer,external-1,true\n"))
                .param("strategy", "CREATE_ONLY").header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY"))
            .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(json.readTree(body).get("id").asText());

        mvc.perform(post(importPath() + "/" + jobId + "/execute").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.importedRows").value(1));

        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_entry where project_id=? and external_id='external-1'", Integer.class, projectId).intValue());
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_import_job where id=? and status='COMPLETED' and execution_claim_token is null and execution_lease_expires_at is null and completed_at is not null", Integer.class, jobId).intValue());
    }

    @Test void executeReturnsClosedConflictWhenDrainIsNotReady() throws Exception {
        String token = login("general-import@example.com");
        String body = mvc.perform(multipart(importPath()).file(csv("blocked.csv", "question,answer,external_id,active\nWhat?,Answer,external-1,true\n"))
                .param("strategy", "CREATE_ONLY").header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(json.readTree(body).get("id").asText());
        jdbc.update("update knowledge_import_row set status='PROCESSING' where import_job_id=?", jobId);

        mvc.perform(post(importPath() + "/" + jobId + "/execute").header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("import_execution_not_ready"))
            .andExpect(content().json("{\"code\":\"import_execution_not_ready\"}", true));
    }

    @Test void executeFailureCanBeRetriedAfterConflictIsResolved() throws Exception {
        String token = login("general-import@example.com");
        mvc.perform(post("/api/admin/projects/" + projectId + "/knowledge").header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"question\":\"Existing?\",\"answer\":\"Existing answer\",\"externalId\":\"duplicate\",\"active\":true}"))
            .andExpect(status().isCreated());
        String body = mvc.perform(multipart(importPath()).file(csv("conflict.csv", "question,answer,external_id,active\nNew?,New answer,duplicate,true\n"))
                .param("strategy", "CREATE_ONLY").header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(json.readTree(body).get("id").asText());

        mvc.perform(post(importPath() + "/" + jobId + "/execute").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.errorSummary[0]").value("knowledge_external_id_conflict"));
        assertEquals("import_row_failed", jdbc.queryForObject("select last_execution_error_code from knowledge_import_job where id=?", String.class, jobId));
        jdbc.update("delete from knowledge_entry where project_id=? and external_id='duplicate'", projectId);

        mvc.perform(post(importPath() + "/" + jobId + "/retry").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.importedRows").value(0));
        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_import_row where import_job_id=? and status='VALID' and attempt_count=1 and knowledge_entry_id is null and execution_error_code is null", Integer.class, jobId).intValue());
        assertEquals(1, jdbc.queryForObject("select execution_attempt_count from knowledge_import_job where id=?", Integer.class, jobId).intValue());
    }

    @Test void retryKeepsAuthorizationAndNotFoundPrecedenceWithoutWrites() throws Exception {
        UUID jobId = UUID.randomUUID();
        jdbc.update("insert into knowledge_import_job(id,project_id,file_name,strategy,status,total_rows,valid_rows,invalid_rows,imported_rows,error_summary,created_at) values (?,?,?,?,?,?,?,?,?,?::jsonb,now())",
            jobId, projectId, "failed.csv", "CREATE_ONLY", "FAILED", 1, 1, 0, 0, "[\"import_row_failed\"]");
        String general = login("general-import@example.com");
        String project = login("project-import@example.com");

        mvc.perform(post(importPath() + "/" + jobId + "/retry").header("Authorization", "Bearer " + project))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));
        mvc.perform(post("/api/admin/projects/" + UUID.randomUUID() + "/knowledge/imports/" + jobId + "/retry").header("Authorization", "Bearer " + general))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("project_not_found"));
        mvc.perform(post(importPath() + "/" + UUID.randomUUID() + "/retry").header("Authorization", "Bearer " + general))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("knowledge_import_job_not_found"));

        assertEquals(1, jdbc.queryForObject("select count(*) from knowledge_import_job where id=? and status='FAILED' and imported_rows=0 and error_summary=?::jsonb", Integer.class, jobId, "[\"import_row_failed\"]").intValue());
        assertEquals(0, jdbc.queryForObject("select count(*) from knowledge_import_row where import_job_id=?", Integer.class, jobId).intValue());
    }

    private MockMultipartFile csv(String name, String body) { return new MockMultipartFile("file", name, "text/csv", body.getBytes(StandardCharsets.UTF_8)); }
    private String importPath() { return "/api/admin/projects/" + projectId + "/knowledge/imports"; }
    private String login(String email) throws Exception { return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/auth/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"correct\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText(); }
}
