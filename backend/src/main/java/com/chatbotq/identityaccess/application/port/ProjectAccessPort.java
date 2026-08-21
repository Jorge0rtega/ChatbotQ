package com.chatbotq.identityaccess.application.port;

import java.util.UUID;

public interface ProjectAccessPort {
    boolean existsActiveProject(UUID projectId);
}
