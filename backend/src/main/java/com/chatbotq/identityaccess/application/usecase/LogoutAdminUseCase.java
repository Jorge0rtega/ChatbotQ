package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.application.port.RefreshTokenManager;
import java.time.Clock;

public final class LogoutAdminUseCase {
    private final RefreshTokenManager tokens;
    private final RefreshSessionRepository sessions;
    private final Clock clock;

    public LogoutAdminUseCase(RefreshTokenManager tokens, RefreshSessionRepository sessions, Clock clock) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.clock = clock;
    }

    public void execute(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.trim().isEmpty()) {
            return;
        }
        sessions.findByTokenHash(tokens.hash(rawRefreshToken))
            .ifPresent(session -> sessions.revokeFamily(session.getFamilyId(), clock.instant()));
    }
}
