package com.chatbotq.identityaccess.web;

import com.chatbotq.identityaccess.application.model.ManagedAdminUser;
import com.chatbotq.identityaccess.application.model.ManagedAdminUserPage;
import com.chatbotq.identityaccess.application.usecase.AdministerAdminUsersUseCase;
import com.chatbotq.identityaccess.application.usecase.AdministerUserProjectAssignmentsUseCase;
import com.chatbotq.identityaccess.infrastructure.security.AdminAccessPrincipal;
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
@RequestMapping("/api/admin/users")
public class AdminUsersController {
    private final AdministerAdminUsersUseCase users;
    private final AdministerUserProjectAssignmentsUseCase assignments;

    public AdminUsersController(AdministerAdminUsersUseCase users,
                                AdministerUserProjectAssignmentsUseCase assignments) {
        this.users = users; this.assignments = assignments;
    }

    @GetMapping("/{userId}/projects")
    ProjectIdsResponse projects(Authentication authentication, @PathVariable String userId) {
        return new ProjectIdsResponse(assignments.list(actor(authentication), canonicalUuid(userId)));
    }

    @PutMapping("/{userId}/projects")
    ProjectIdsResponse replaceProjects(Authentication authentication, @PathVariable String userId,
                                       @RequestBody ProjectIdsRequest request) {
        UUID targetId = canonicalUuid(userId);
        if (request == null || !request.seen || request.projectIds == null) {
            throw new IllegalArgumentException("projectIds is required");
        }
        if (request.projectIds.size() > AdministerUserProjectAssignmentsUseCase.MAX_PROJECT_IDS) {
            throw new IllegalArgumentException("too many projectIds");
        }
        List<UUID> ids = new ArrayList<>();
        for (String value : request.projectIds) {
            if (value == null) throw new IllegalArgumentException("null projectId");
            UUID parsed;
            try { parsed = UUID.fromString(value); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid projectId", invalid); }
            if (!parsed.toString().equals(value)) throw new IllegalArgumentException("projectId must be canonical");
            ids.add(parsed);
        }
        return new ProjectIdsResponse(assignments.replace(actor(authentication), targetId, ids));
    }

    @PostMapping
    ResponseEntity<UserResponse> create(Authentication authentication, @RequestBody CreateRequest request) {
        if (request == null) throw new IllegalArgumentException("request required");
        ManagedAdminUser created = users.create(actor(authentication), request.email,
            request.temporaryPassword, request.role);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
            .buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(UserResponse.from(created));
    }

    @GetMapping("/{userId}")
    UserResponse get(Authentication authentication, @PathVariable UUID userId) {
        return UserResponse.from(users.get(actor(authentication), userId));
    }

    @GetMapping
    PageResponse list(Authentication authentication, @RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(users.list(actor(authentication), page, size));
    }

    @PutMapping("/{userId}")
    UserResponse update(Authentication authentication, @PathVariable UUID userId,
                        @RequestBody EmailRequest request) {
        if (request == null) throw new IllegalArgumentException("request required");
        return UserResponse.from(users.updateEmail(actor(authentication), userId, request.email));
    }

    @PostMapping("/{userId}/activate")
    ResponseEntity<Void> activate(Authentication authentication, @PathVariable UUID userId) {
        users.activate(actor(authentication), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/deactivate")
    ResponseEntity<Void> deactivate(Authentication authentication, @PathVariable UUID userId) {
        users.deactivate(actor(authentication), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/reset-password")
    ResponseEntity<Void> resetPassword(Authentication authentication, @PathVariable UUID userId,
                                       @RequestBody ResetPasswordRequest request) {
        if (request == null) throw new IllegalArgumentException("request required");
        users.resetPassword(actor(authentication), userId, request.temporaryPassword);
        return ResponseEntity.noContent().build();
    }

    private UUID actor(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AdminAccessPrincipal)) {
            throw new IllegalArgumentException("authenticated admin principal required");
        }
        return ((AdminAccessPrincipal) authentication.getPrincipal()).getUserId();
    }

    private static UUID canonicalUuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("userId must be canonical");
            }
            return UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("userId must be canonical", invalid);
        }
    }

    static final class CreateRequest {
        String email;
        String temporaryPassword;
        String role;
        boolean emailSeen;
        boolean passwordSeen;
        boolean roleSeen;
        @JsonProperty("email") public void setEmail(String value) {
            if (emailSeen) throw new IllegalArgumentException("duplicate property"); emailSeen = true; email = value;
        }
        @JsonProperty("temporaryPassword") public void setTemporaryPassword(String value) {
            if (passwordSeen) throw new IllegalArgumentException("duplicate property"); passwordSeen = true; temporaryPassword = value;
        }
        @JsonProperty("role") public void setRole(String value) {
            if (roleSeen) throw new IllegalArgumentException("duplicate property"); roleSeen = true; role = value;
        }
        @JsonAnySetter public void rejectUnknown(String property, Object ignored) {
            throw new IllegalArgumentException("unknown property: " + property);
        }
    }

    static final class EmailRequest {
        String email;
        boolean seen;
        @JsonProperty("email") public void setEmail(String value) {
            if (seen) throw new IllegalArgumentException("duplicate property"); seen = true; email = value;
        }
        @JsonAnySetter public void rejectUnknown(String property, Object ignored) {
            throw new IllegalArgumentException("unknown property: " + property);
        }
    }

    static final class ResetPasswordRequest {
        String temporaryPassword;
        boolean seen;
        @JsonProperty("temporaryPassword") public void setTemporaryPassword(String value) {
            if (seen) throw new IllegalArgumentException("duplicate property"); seen = true; temporaryPassword = value;
        }
        @JsonAnySetter public void rejectUnknown(String property, Object ignored) {
            throw new IllegalArgumentException("unknown property: " + property);
        }
    }

    static final class ProjectIdsRequest {
        List<String> projectIds;
        boolean seen;
        @JsonProperty("projectIds") public void setProjectIds(List<String> value) {
            if (seen) throw new IllegalArgumentException("duplicate property"); seen = true; projectIds = value;
        }
        @JsonAnySetter public void rejectUnknown(String property, Object ignored) {
            throw new IllegalArgumentException("unknown property: " + property);
        }
    }

    static final class ProjectIdsResponse {
        private final List<UUID> projectIds;
        ProjectIdsResponse(List<UUID> projectIds) { this.projectIds = projectIds; }
        public List<UUID> getProjectIds() { return projectIds; }
    }

    static final class UserResponse {
        private final UUID id;
        private final String email;
        private final String role;
        private final String status;
        private final Instant createdAt;
        private final Instant updatedAt;
        private UserResponse(UUID id, String email, String role, String status, Instant createdAt, Instant updatedAt) {
            this.id = id; this.email = email; this.role = role; this.status = status;
            this.createdAt = createdAt; this.updatedAt = updatedAt;
        }
        static UserResponse from(ManagedAdminUser user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getStatus(),
                user.getCreatedAt(), user.getUpdatedAt());
        }
        public UUID getId() { return id; }
        public String getEmail() { return email; }
        public String getRole() { return role; }
        public String getStatus() { return status; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
    }

    static final class PageResponse {
        private final List<UserResponse> items;
        private final int page;
        private final int size;
        private final long totalElements;
        private final long totalPages;
        private PageResponse(List<UserResponse> items, int page, int size, long totalElements, long totalPages) {
            this.items = items; this.page = page; this.size = size;
            this.totalElements = totalElements; this.totalPages = totalPages;
        }
        static PageResponse from(ManagedAdminUserPage page) {
            List<UserResponse> items = new ArrayList<>();
            for (ManagedAdminUser user : page.getItems()) items.add(UserResponse.from(user));
            return new PageResponse(items, page.getPage(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
        public List<UserResponse> getItems() { return items; }
        public int getPage() { return page; }
        public int getSize() { return size; }
        public long getTotalElements() { return totalElements; }
        public long getTotalPages() { return totalPages; }
    }
}
