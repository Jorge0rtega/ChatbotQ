package com.chatbotq.identityaccess.infrastructure.security;

import java.util.UUID;

public final class AdminAccessPrincipal {
    private final UUID userId;
    private final String email;
    private final boolean generalAdmin;

    public AdminAccessPrincipal(UUID userId, String email, boolean generalAdmin) {
        this.userId = userId;
        this.email = email;
        this.generalAdmin = generalAdmin;
    }

    public UUID getUserId() { return userId; }
    public String getEmail() { return email; }
    public boolean isGeneralAdmin() { return generalAdmin; }
}
