package com.chatbotq.identityaccess.infrastructure.security;

import com.chatbotq.identityaccess.application.usecase.CanAdministerProjectUseCase;
import org.springframework.security.core.Authentication;

import java.util.UUID;

public final class ProjectAuthorization {
    private final CanAdministerProjectUseCase authorization;

    public ProjectAuthorization(CanAdministerProjectUseCase authorization) {
        if (authorization == null) {
            throw new IllegalArgumentException("authorization must not be null");
        }
        this.authorization = authorization;
    }

    public boolean canAdminister(Authentication authentication, String projectId) {
        if (authentication == null || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof AdminAccessPrincipal)) {
            return false;
        }
        if (projectId == null || projectId.length() != 36) {
            return false;
        }
        try {
            UUID parsedProjectId = UUID.fromString(projectId);
            if (!parsedProjectId.toString().equalsIgnoreCase(projectId)) {
                return false;
            }
            AdminAccessPrincipal principal = (AdminAccessPrincipal) authentication.getPrincipal();
            return authorization.isAllowed(principal.getUserId(), parsedProjectId);
        } catch (IllegalArgumentException invalidProjectId) {
            return false;
        }
    }
}
