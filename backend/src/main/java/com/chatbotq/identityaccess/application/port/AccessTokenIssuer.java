package com.chatbotq.identityaccess.application.port;

import com.chatbotq.identityaccess.domain.AdminUser;
import java.time.Instant;

public interface AccessTokenIssuer {
    String issue(AdminUser user, Instant issuedAt);

    default long getExpiresInSeconds() {
        return 300L;
    }
}
