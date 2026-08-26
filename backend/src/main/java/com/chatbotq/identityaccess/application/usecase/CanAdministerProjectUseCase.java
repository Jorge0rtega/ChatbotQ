package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.ProjectAdministrationDecisionPort;

import java.util.UUID;

public final class CanAdministerProjectUseCase {
    private final ProjectAdministrationDecisionPort decision;

    public CanAdministerProjectUseCase(ProjectAdministrationDecisionPort decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        this.decision = decision;
    }

    public boolean isAllowed(UUID userId, UUID projectId) {
        if (userId == null || projectId == null) {
            return false;
        }
        return decision.canAdminister(userId, projectId);
    }
}
