package com.chatbotq.identityaccess.application.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Immutable identity-and-authorizations view produced by one database statement snapshot. */
public final class CurrentAdminView {
    private final UUID userId;
    private final String email;
    private final boolean generalAdmin;
    private final List<UUID> projectIds;

    public CurrentAdminView(UUID userId, String email, boolean generalAdmin, List<UUID> projectIds) {
        if (userId == null || email == null || projectIds == null) {
            throw new IllegalArgumentException("current admin view fields are required");
        }
        this.userId = userId;
        this.email = email;
        this.generalAdmin = generalAdmin;
        this.projectIds = Collections.unmodifiableList(new ArrayList<>(projectIds));
    }

    public UUID getUserId() { return userId; }
    public String getEmail() { return email; }
    public boolean isGeneralAdmin() { return generalAdmin; }
    public List<UUID> getProjectIds() { return projectIds; }
}
