package com.chatbotq.projects.application.port;

import com.chatbotq.projects.domain.AllowedOrigin;

import java.util.UUID;

public interface AllowedOriginRepository {
    boolean exists(UUID projectId, String canonicalOrigin);

    AllowedOrigin save(AllowedOrigin origin);
}
