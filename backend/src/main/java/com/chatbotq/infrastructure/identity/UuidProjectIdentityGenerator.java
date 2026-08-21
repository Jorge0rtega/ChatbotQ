package com.chatbotq.infrastructure.identity;

import com.chatbotq.projects.application.port.AllowedOriginIdentityGenerator;
import com.chatbotq.projects.application.port.ProjectIdentityGenerator;

import java.util.UUID;

public final class UuidProjectIdentityGenerator
    implements ProjectIdentityGenerator, AllowedOriginIdentityGenerator {
    @Override
    public UUID newProjectId() {
        return UUID.randomUUID();
    }

    @Override
    public UUID newSiteKey() {
        return UUID.randomUUID();
    }

    @Override
    public UUID newAllowedOriginId() {
        return UUID.randomUUID();
    }
}
