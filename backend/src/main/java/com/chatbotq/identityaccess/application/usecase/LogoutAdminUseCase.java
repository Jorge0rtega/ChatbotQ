package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.application.port.RefreshTokenManager;
import java.time.Clock;
import java.util.UUID;

public final class LogoutAdminUseCase {
    private final RefreshTokenManager tokens;
    private final AdminUserRepository users;
    private final RefreshSessionRepository sessions;
    private final ApplicationTransaction transactions;
    private final Clock clock;

    public LogoutAdminUseCase(RefreshTokenManager tokens, AdminUserRepository users,
                              RefreshSessionRepository sessions, ApplicationTransaction transactions,
                              Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.sessions = sessions;
        this.transactions = transactions;
        this.clock = clock;
    }

    public void execute(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.trim().isEmpty()) {
            return;
        }
        final String hash = tokens.hash(rawRefreshToken);
        transactions.execute(() -> {
            UUID userId = sessions.findUserIdByTokenHash(hash).orElse(null);
            if (userId == null) return null;
            if (!users.findByIdForUpdate(userId).isPresent()) return null;
            sessions.findByTokenHashAndLockFamily(hash)
                .ifPresent(session -> sessions.revokeFamilyOrdered(session.getFamilyId(), clock.instant()));
            return null;
        });
    }
}
