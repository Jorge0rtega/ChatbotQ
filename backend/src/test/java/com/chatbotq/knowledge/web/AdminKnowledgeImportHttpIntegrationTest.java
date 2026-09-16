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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @BeforeEach void seed() {
        jdbc.update("delete from knowledge_import_row"); jdbc.update("delete from knowledge_import_job");
        jdbc.update("delete from knowledge_entry"); jdbc.update("delete from admin_refresh_session");
        jdbc.update("delete from admin_user"); jdbc.update("delete from project");
        projectId = UUID.randomUUID();
        jdbc.update("insert into project (id,name,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())", projectId, "Project");
        AdminUser general = createUser.execute("general-import@example.com", "correct", true);
        jdbc.update("update admin_user set status='ACTIVE' where id=?", general.getId());
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

    private MockMultipartFile csv(String name, String body) { return new MockMultipartFile("file", name, "text/csv", body.getBytes(StandardCharsets.UTF_8)); }
    private String importPath() { return "/api/admin/projects/" + projectId + "/knowledge/imports"; }
    private String login(String email) throws Exception { return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/auth/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"correct\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("accessToken").asText(); }
}
