package com.chatbotq.identityaccess.application.port;

import java.util.UUID;

public interface AdminUserIdentityGenerator {
    UUID newUserId();
}
