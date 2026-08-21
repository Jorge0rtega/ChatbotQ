package com.chatbotq.projects.application.port;

import java.util.UUID;

public interface ProjectStatusPort {
    boolean existsActiveProject(UUID projectId);
}
