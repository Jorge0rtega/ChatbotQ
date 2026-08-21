package com.chatbotq.infrastructure.identity;

import com.chatbotq.projects.application.port.ProjectIdentityGenerator;

import java.util.UUID;

public final class UuidProjectIdentityGenerator implements ProjectIdentityGenerator {
    @Override
    public UUID newProjectId() {
        return UUID.randomUUID();
    }

    @Override
    public UUID newSiteKey() {
        return UUID.randomUUID();
    }
}
