package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.model.CurrentAdminView;
import com.chatbotq.identityaccess.application.port.CurrentAdminViewPort;

import java.util.UUID;

public final class GetCurrentAdminViewUseCase {
    private final CurrentAdminViewPort views;

    public GetCurrentAdminViewUseCase(CurrentAdminViewPort views) {
        if (views == null) throw new IllegalArgumentException("views must not be null");
        this.views = views;
    }

    public CurrentAdminView execute(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("userId must not be null");
        return views.findAvailable(userId).orElseThrow(ForbiddenAdminUserAdministrationException::new);
    }
}
