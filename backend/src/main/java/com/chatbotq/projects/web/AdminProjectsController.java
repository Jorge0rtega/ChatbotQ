package com.chatbotq.projects.web;

import com.chatbotq.identityaccess.infrastructure.security.AdminAccessPrincipal;
import com.chatbotq.projects.application.model.ManagedProject;
import com.chatbotq.projects.application.model.ManagedProjectPage;
import com.chatbotq.projects.application.usecase.AdministerProjectsUseCase;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/projects")
public class AdminProjectsController {
    private final AdministerProjectsUseCase projects;

    public AdminProjectsController(AdministerProjectsUseCase projects) {
        this.projects = projects;
    }

    @PostMapping
    ResponseEntity<ProjectResponse> create(Authentication authentication, @RequestBody ProjectNameRequest request) {
        ManagedProject created = projects.create(actor(authentication), request.name);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
            .buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(ProjectResponse.from(created));
    }

    @GetMapping("/{projectId}")
    ProjectResponse get(Authentication authentication, @PathVariable UUID projectId) {
        return ProjectResponse.from(projects.get(actor(authentication), projectId));
    }

    @GetMapping
    ProjectPageResponse list(Authentication authentication,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return ProjectPageResponse.from(projects.list(actor(authentication), page, size));
    }

    @PutMapping("/{projectId}")
    ProjectResponse update(Authentication authentication, @PathVariable UUID projectId,
                           @RequestBody ProjectNameRequest request) {
        return ProjectResponse.from(projects.updateName(actor(authentication), projectId, request.name));
    }

    @PostMapping("/{projectId}/activate")
    ResponseEntity<Void> activate(Authentication authentication, @PathVariable UUID projectId) {
        projects.activate(actor(authentication), projectId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{projectId}/deactivate")
    ResponseEntity<Void> deactivate(Authentication authentication, @PathVariable UUID projectId) {
        projects.deactivate(actor(authentication), projectId);
        return ResponseEntity.noContent().build();
    }

    private UUID actor(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AdminAccessPrincipal)) {
            throw new IllegalArgumentException("authenticated admin principal required");
        }
        return ((AdminAccessPrincipal) authentication.getPrincipal()).getUserId();
    }

    static final class ProjectNameRequest {
        private String name;
        private boolean nameSeen;

        @JsonProperty("name")
        public void setName(String name) {
            if (nameSeen) throw new IllegalArgumentException("duplicate project property: name");
            nameSeen = true;
            this.name = name;
        }

        @JsonAnySetter
        public void rejectUnknown(String property, Object ignored) {
            throw new IllegalArgumentException("unknown project property: " + property);
        }
    }

    static final class ProjectResponse {
        private final UUID id;
        private final String name;
        private final String status;
        private final Instant createdAt;
        private final Instant updatedAt;

        private ProjectResponse(UUID id, String name, String status, Instant createdAt, Instant updatedAt) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        static ProjectResponse from(ManagedProject project) {
            return new ProjectResponse(project.getId(), project.getName(), project.getStatus(),
                project.getCreatedAt(), project.getUpdatedAt());
        }

        public UUID getId() { return id; }
        public String getName() { return name; }
        public String getStatus() { return status; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
    }

    static final class ProjectPageResponse {
        private final List<ProjectResponse> items;
        private final int page;
        private final int size;
        private final long totalElements;
        private final long totalPages;

        private ProjectPageResponse(List<ProjectResponse> items, int page, int size,
                                    long totalElements, long totalPages) {
            this.items = items;
            this.page = page;
            this.size = size;
            this.totalElements = totalElements;
            this.totalPages = totalPages;
        }

        static ProjectPageResponse from(ManagedProjectPage source) {
            List<ProjectResponse> responses = new ArrayList<>();
            for (ManagedProject project : source.getItems()) responses.add(ProjectResponse.from(project));
            return new ProjectPageResponse(responses, source.getPage(), source.getSize(),
                source.getTotalElements(), source.getTotalPages());
        }

        public List<ProjectResponse> getItems() { return items; }
        public int getPage() { return page; }
        public int getSize() { return size; }
        public long getTotalElements() { return totalElements; }
        public long getTotalPages() { return totalPages; }
    }
}
