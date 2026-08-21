package com.chatbotq.identityaccess.application.port;

import com.chatbotq.identityaccess.domain.UserProjectAssignment;

import java.util.UUID;

public interface UserProjectAssignmentRepository {
    boolean exists(UUID userId, UUID projectId);

    UserProjectAssignment save(UserProjectAssignment assignment);
}
