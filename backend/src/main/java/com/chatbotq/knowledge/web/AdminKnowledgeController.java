package com.chatbotq.knowledge.web;

import com.chatbotq.identityaccess.infrastructure.security.AdminAccessPrincipal;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntryPage;
import com.chatbotq.knowledge.application.model.KnowledgeCsvImportStrategy;
import com.chatbotq.knowledge.application.model.PersistedKnowledgeImportJob;
import com.chatbotq.knowledge.application.model.PersistedKnowledgeImportRow;
import com.chatbotq.knowledge.application.usecase.AdministerKnowledgeUseCase;
import com.chatbotq.knowledge.application.usecase.AdministerKnowledgeImportsUseCase;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/admin/projects/{projectId}/knowledge")
public final class AdminKnowledgeController {
    private final AdministerKnowledgeUseCase knowledge;
    private final AdministerKnowledgeImportsUseCase imports;

    public AdminKnowledgeController(AdministerKnowledgeUseCase knowledge, AdministerKnowledgeImportsUseCase imports) {
        this.knowledge = knowledge;
        this.imports = imports;
    }

    @PostMapping
    ResponseEntity<KnowledgeResponse> create(Authentication authentication, @PathVariable String projectId,
                                             @RequestBody CreateKnowledgeRequest request) {
        if (request == null) throw new IllegalArgumentException("knowledge request is required");
        ManagedKnowledgeEntry created = knowledge.create(actor(authentication), canonicalProjectId(projectId), request.question,
            request.answer, request.externalId, request.active);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
            .buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(KnowledgeResponse.from(created));
    }

    @GetMapping
    KnowledgePageResponse list(Authentication authentication, @PathVariable String projectId,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @RequestParam(required = false, name = "q") String query) {
        return KnowledgePageResponse.from(knowledge.list(actor(authentication), canonicalProjectId(projectId), query, page, size));
    }

    @GetMapping("/{entryId}")
    KnowledgeResponse get(Authentication authentication, @PathVariable String projectId, @PathVariable String entryId) {
        return KnowledgeResponse.from(knowledge.get(actor(authentication), canonicalProjectId(projectId),
            canonicalEntryId(entryId)));
    }

    @PutMapping("/{entryId}")
    KnowledgeResponse update(Authentication authentication, @PathVariable String projectId, @PathVariable String entryId,
                             @RequestBody UpdateKnowledgeRequest request) {
        if (request == null) throw new IllegalArgumentException("knowledge request is required");
        return KnowledgeResponse.from(knowledge.update(actor(authentication), canonicalProjectId(projectId), canonicalEntryId(entryId),
            request.requiredQuestion(), request.requiredAnswer(), request.externalId, request.requiredActive(), request.requiredVersion()));
    }

    @PostMapping("/{entryId}/embedding-retry")
    KnowledgeResponse retryEmbedding(Authentication authentication, @PathVariable String projectId, @PathVariable String entryId,
                                    @RequestBody RetryEmbeddingRequest request) {
        if (request == null) throw new IllegalArgumentException("retry request is required");
        return KnowledgeResponse.from(knowledge.retryEmbedding(actor(authentication), canonicalProjectId(projectId),
            canonicalEntryId(entryId), request.requiredVersion()));
    }

    @PostMapping(path = "/imports", consumes = "multipart/form-data")
    ResponseEntity<KnowledgeImportSummaryResponse> createImport(Authentication authentication, @PathVariable String projectId,
                                                                 @RequestPart("file") MultipartFile file,
                                                                 @RequestParam KnowledgeCsvImportStrategy strategy) throws java.io.IOException {
        if (file == null || file.getOriginalFilename() == null) throw new IllegalArgumentException("file is required");
        PersistedKnowledgeImportJob job = imports.create(actor(authentication), canonicalProjectId(projectId), file.getOriginalFilename(), file.getBytes(), strategy);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{jobId}").buildAndExpand(job.getId()).toUri();
        return ResponseEntity.created(location).body(KnowledgeImportSummaryResponse.from(job));
    }

    @GetMapping("/imports/{jobId}")
    KnowledgeImportDetailResponse getImport(Authentication authentication, @PathVariable String projectId, @PathVariable String jobId,
                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        PersistedKnowledgeImportJob job = imports.get(actor(authentication), canonicalProjectId(projectId), canonicalUuid(jobId, "jobId"), page, size);
        return KnowledgeImportDetailResponse.from(job, page, size);
    }

    private static UUID canonicalProjectId(String raw) {
        return canonicalUuid(raw, "projectId");
    }

    private static UUID canonicalEntryId(String raw) {
        return canonicalUuid(raw, "entryId");
    }

    private static UUID canonicalUuid(String raw, String name) {
        if (raw == null) throw new IllegalArgumentException(name + " is required");
        final UUID parsed;
        try {
            parsed = UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(name + " must be a canonical UUID", invalid);
        }
        if (!parsed.toString().equals(raw)) {
            throw new IllegalArgumentException(name + " must be a canonical UUID");
        }
        return parsed;
    }

    private static UUID actor(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AdminAccessPrincipal)) {
            throw new IllegalArgumentException("authenticated admin principal required");
        }
        return ((AdminAccessPrincipal) authentication.getPrincipal()).getUserId();
    }

    static final class CreateKnowledgeRequest {
        private String question;
        private String answer;
        private String externalId;
        private boolean active = true;
        private boolean questionSeen;
        private boolean answerSeen;
        private boolean externalIdSeen;
        private boolean activeSeen;

        @JsonProperty("question")
        public void setQuestion(String value) {
            if (questionSeen) throw new IllegalArgumentException("duplicate knowledge property: question");
            questionSeen = true;
            question = value;
        }

        @JsonProperty("answer")
        public void setAnswer(String value) {
            if (answerSeen) throw new IllegalArgumentException("duplicate knowledge property: answer");
            answerSeen = true;
            answer = value;
        }

        @JsonProperty("externalId")
        public void setExternalId(String value) {
            if (externalIdSeen) throw new IllegalArgumentException("duplicate knowledge property: externalId");
            externalIdSeen = true;
            externalId = value;
        }

        @JsonProperty("active")
        public void setActive(Boolean value) {
            if (activeSeen) throw new IllegalArgumentException("duplicate knowledge property: active");
            activeSeen = true;
            if (value == null) throw new IllegalArgumentException("active must be a boolean");
            active = value.booleanValue();
        }

        @JsonAnySetter
        public void rejectUnknown(String property, Object ignored) {
            throw new IllegalArgumentException("unknown knowledge property: " + property);
        }
    }

    static final class UpdateKnowledgeRequest {
        private String question, answer, externalId;
        private Boolean active;
        private Long version;
        private boolean questionSeen, answerSeen, externalIdSeen, activeSeen, versionSeen;
        @JsonProperty("question") public void setQuestion(String value) { duplicate(questionSeen, "question"); questionSeen = true; question = value; }
        @JsonProperty("answer") public void setAnswer(String value) { duplicate(answerSeen, "answer"); answerSeen = true; answer = value; }
        @JsonProperty("externalId") public void setExternalId(String value) { duplicate(externalIdSeen, "externalId"); externalIdSeen = true; externalId = value; }
        @JsonProperty("active") public void setActive(Boolean value) { duplicate(activeSeen, "active"); activeSeen = true; active = value; }
        @JsonProperty("version") public void setVersion(Long value) { duplicate(versionSeen, "version"); versionSeen = true; version = value; }
        @JsonAnySetter public void rejectUnknown(String property, Object ignored) { throw new IllegalArgumentException("unknown knowledge property: " + property); }
        String requiredQuestion() { if (!questionSeen || question == null) throw new IllegalArgumentException("question is required"); return question; }
        String requiredAnswer() { if (!answerSeen || answer == null) throw new IllegalArgumentException("answer is required"); return answer; }
        boolean requiredActive() { if (!activeSeen || active == null) throw new IllegalArgumentException("active is required"); return active.booleanValue(); }
        long requiredVersion() { if (!versionSeen || version == null) throw new IllegalArgumentException("version is required"); return version.longValue(); }
        private static void duplicate(boolean seen, String property) { if (seen) throw new IllegalArgumentException("duplicate knowledge property: " + property); }
    }

    static final class RetryEmbeddingRequest {
        private Long version;
        private boolean versionSeen;
        @JsonProperty("version") public void setVersion(Long value) { if (versionSeen) throw new IllegalArgumentException("duplicate retry property: version"); versionSeen = true; version = value; }
        @JsonAnySetter public void rejectUnknown(String property, Object ignored) { throw new IllegalArgumentException("unknown retry property: " + property); }
        long requiredVersion() { if (!versionSeen || version == null) throw new IllegalArgumentException("version is required"); return version.longValue(); }
    }

    static class KnowledgeImportSummaryResponse {
        private final UUID id, projectId; private final String fileName, strategy, status; private final int totalRows, validRows, invalidRows, importedRows; private final List<String> errorSummary; private final Instant createdAt;
        private KnowledgeImportSummaryResponse(PersistedKnowledgeImportJob job) { id=job.getId(); projectId=job.getProjectId(); fileName=job.getFileName(); strategy=job.getStrategy(); status=job.getStatus(); totalRows=job.getTotalRows(); validRows=job.getValidRows(); invalidRows=job.getInvalidRows(); importedRows=job.getImportedRows(); errorSummary=job.getErrorSummary(); createdAt=job.getCreatedAt(); }
        static KnowledgeImportSummaryResponse from(PersistedKnowledgeImportJob job) { return new KnowledgeImportSummaryResponse(job); }
        public UUID getId(){return id;} public UUID getProjectId(){return projectId;} public String getFileName(){return fileName;} public String getStrategy(){return strategy;} public String getStatus(){return status;} public int getTotalRows(){return totalRows;} public int getValidRows(){return validRows;} public int getInvalidRows(){return invalidRows;} public int getImportedRows(){return importedRows;} public List<String> getErrorSummary(){return errorSummary;} public Instant getCreatedAt(){return createdAt;}
    }
    static final class KnowledgeImportDetailResponse extends KnowledgeImportSummaryResponse {
        private final List<KnowledgeImportRowResponse> rows; private final int page, size; private final long totalRowElements;
        private KnowledgeImportDetailResponse(PersistedKnowledgeImportJob job, int page, int size) { super(job); this.page=page; this.size=size; totalRowElements=job.getTotalRows(); rows=new ArrayList<KnowledgeImportRowResponse>(); for(PersistedKnowledgeImportRow row:job.getRows()) rows.add(new KnowledgeImportRowResponse(row)); }
        static KnowledgeImportDetailResponse from(PersistedKnowledgeImportJob job, int page, int size) { return new KnowledgeImportDetailResponse(job,page,size); }
        public List<KnowledgeImportRowResponse> getRows(){return rows;} public int getPage(){return page;} public int getSize(){return size;} public long getTotalRowElements(){return totalRowElements;}
    }
    static final class KnowledgeImportRowResponse {
        private final int rowNumber; private final String question, answer, externalId, status; private final boolean active; private final List<String> errors;
        private KnowledgeImportRowResponse(PersistedKnowledgeImportRow row) { rowNumber=row.getRowNumber(); question=row.getQuestion(); answer=row.getAnswer(); externalId=row.getExternalId(); active=row.isActive(); status=row.getStatus(); errors=row.getErrors(); }
        public int getRowNumber(){return rowNumber;} public String getQuestion(){return question;} public String getAnswer(){return answer;} public String getExternalId(){return externalId;} public boolean isActive(){return active;} public String getStatus(){return status;} public List<String> getErrors(){return errors;}
    }

    static final class KnowledgeResponse {
        private final UUID id;
        private final UUID projectId;
        private final String question;
        private final String answer;
        private final String externalId;
        private final boolean active;
        private final String embeddingStatus;
        private final long embeddingRevision;
        private final int embeddingAttemptCount;
        private final Instant embeddingLastAttemptAt;
        private final String embeddingLastErrorCode;
        private final String embeddingLastErrorMessage;
        private final long version;
        private final Instant createdAt;
        private final Instant updatedAt;

        private KnowledgeResponse(UUID id, UUID projectId, String question, String answer, String externalId,
                                  boolean active, String embeddingStatus, long embeddingRevision, int embeddingAttemptCount,
                                  Instant embeddingLastAttemptAt, String embeddingLastErrorCode,
                                  String embeddingLastErrorMessage, long version,
                                  Instant createdAt, Instant updatedAt) {
            this.id = id;
            this.projectId = projectId;
            this.question = question;
            this.answer = answer;
            this.externalId = externalId;
            this.active = active;
            this.embeddingStatus = embeddingStatus;
            this.embeddingRevision = embeddingRevision;
            this.embeddingAttemptCount = embeddingAttemptCount;
            this.embeddingLastAttemptAt = embeddingLastAttemptAt;
            this.embeddingLastErrorCode = embeddingLastErrorCode;
            this.embeddingLastErrorMessage = embeddingLastErrorMessage;
            this.version = version;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        static KnowledgeResponse from(ManagedKnowledgeEntry entry) {
            return new KnowledgeResponse(entry.getId(), entry.getProjectId(), entry.getQuestion(), entry.getAnswer(),
                entry.getExternalId(), entry.isActive(), entry.getEmbeddingStatus(), entry.getEmbeddingRevision(),
                entry.getEmbeddingAttemptCount(), entry.getEmbeddingLastAttemptAt(), entry.getEmbeddingLastErrorCode(),
                entry.getEmbeddingLastErrorMessage(), entry.getVersion(), entry.getCreatedAt(), entry.getUpdatedAt());
        }

        public UUID getId() { return id; }
        public UUID getProjectId() { return projectId; }
        public String getQuestion() { return question; }
        public String getAnswer() { return answer; }
        public String getExternalId() { return externalId; }
        public boolean isActive() { return active; }
        public String getEmbeddingStatus() { return embeddingStatus; }
        public long getEmbeddingRevision() { return embeddingRevision; }
        public int getEmbeddingAttemptCount() { return embeddingAttemptCount; }
        public Instant getEmbeddingLastAttemptAt() { return embeddingLastAttemptAt; }
        public String getEmbeddingLastErrorCode() { return embeddingLastErrorCode; }
        public String getEmbeddingLastErrorMessage() { return embeddingLastErrorMessage; }
        public long getVersion() { return version; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
    }

    static final class KnowledgePageResponse {
        private final List<KnowledgeResponse> items;
        private final int page;
        private final int size;
        private final long totalElements;
        private final long totalPages;

        private KnowledgePageResponse(List<KnowledgeResponse> items, int page, int size, long totalElements,
                                      long totalPages) {
            this.items = items; this.page = page; this.size = size;
            this.totalElements = totalElements; this.totalPages = totalPages;
        }

        static KnowledgePageResponse from(ManagedKnowledgeEntryPage source) {
            List<KnowledgeResponse> items = new ArrayList<>();
            for (ManagedKnowledgeEntry entry : source.getItems()) items.add(KnowledgeResponse.from(entry));
            return new KnowledgePageResponse(items, source.getPage(), source.getSize(), source.getTotalElements(),
                source.getTotalPages());
        }

        public List<KnowledgeResponse> getItems() { return items; }
        public int getPage() { return page; }
        public int getSize() { return size; }
        public long getTotalElements() { return totalElements; }
        public long getTotalPages() { return totalPages; }
    }
}
