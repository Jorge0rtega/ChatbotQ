package com.chatbotq.projects.application.port;

import com.chatbotq.projects.application.model.ManagedProject;
import com.chatbotq.projects.application.model.ManagedProjectPage;

import java.time.Instant;
import java.util.UUID;

public interface ProjectAdministrationPort {
    ManagedProject createAsGeneralAdmin(UUID actorId, UUID projectId, String name, UUID siteKey, Instant now);
    ManagedProject findVisibleById(UUID actorId, UUID projectId);
    ManagedProjectPage listAsGeneralAdmin(UUID actorId, int page, int size, long offset);
    ManagedProject updateName(UUID actorId, UUID projectId, String name, Instant now);
    void setActiveAsGeneralAdmin(UUID actorId, UUID projectId, boolean active, Instant now);
}
