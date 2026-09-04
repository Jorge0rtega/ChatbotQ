package com.chatbotq.knowledge.web;

import com.chatbotq.identityaccess.infrastructure.security.AdminAccessPrincipal;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;
import com.chatbotq.knowledge.application.usecase.AdministerKnowledgeUseCase;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/projects/{projectId}/knowledge")
public final class AdminKnowledgeController {
    private final AdministerKnowledgeUseCase knowledge;

    public AdminKnowledgeController(AdministerKnowledgeUseCase knowledge) {
        this.knowledge = knowledge;
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

    private static UUID canonicalProjectId(String raw) {
        if (raw == null) throw new IllegalArgumentException("projectId is required");
        final UUID parsed;
        try {
            parsed = UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("projectId must be a canonical UUID", invalid);
        }
        if (!parsed.toString().equals(raw)) {
            throw new IllegalArgumentException("projectId must be a canonical UUID");
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

    static final class KnowledgeResponse {
        private final UUID id;
        private final UUID projectId;
        private final String question;
        private final String answer;
        private final String externalId;
        private final boolean active;
        private final String embeddingStatus;
        private final long embeddingRevision;
        private final Instant createdAt;
        private final Instant updatedAt;

        private KnowledgeResponse(UUID id, UUID projectId, String question, String answer, String externalId,
                                  boolean active, String embeddingStatus, long embeddingRevision,
                                  Instant createdAt, Instant updatedAt) {
            this.id = id;
            this.projectId = projectId;
            this.question = question;
            this.answer = answer;
            this.externalId = externalId;
            this.active = active;
            this.embeddingStatus = embeddingStatus;
            this.embeddingRevision = embeddingRevision;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        static KnowledgeResponse from(ManagedKnowledgeEntry entry) {
            return new KnowledgeResponse(entry.getId(), entry.getProjectId(), entry.getQuestion(), entry.getAnswer(),
                entry.getExternalId(), entry.isActive(), entry.getEmbeddingStatus(), entry.getEmbeddingRevision(),
                entry.getCreatedAt(), entry.getUpdatedAt());
        }

        public UUID getId() { return id; }
        public UUID getProjectId() { return projectId; }
        public String getQuestion() { return question; }
        public String getAnswer() { return answer; }
        public String getExternalId() { return externalId; }
        public boolean isActive() { return active; }
        public String getEmbeddingStatus() { return embeddingStatus; }
        public long getEmbeddingRevision() { return embeddingRevision; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
    }
}
