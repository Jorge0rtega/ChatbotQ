package com.chatbotq.projects.application.port;

import java.util.UUID;

public interface ProjectIdentityGenerator {
    UUID newProjectId();

    UUID newSiteKey();
}
