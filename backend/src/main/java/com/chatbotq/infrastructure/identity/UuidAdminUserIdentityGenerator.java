package com.chatbotq.infrastructure.identity;

import com.chatbotq.identityaccess.application.port.AdminUserIdentityGenerator;

import java.util.UUID;

public final class UuidAdminUserIdentityGenerator implements AdminUserIdentityGenerator {
    @Override
    public UUID newUserId() {
        return UUID.randomUUID();
    }
}
