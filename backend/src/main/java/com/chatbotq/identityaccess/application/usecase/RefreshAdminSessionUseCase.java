package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.model.AuthenticationTokens;
import com.chatbotq.identityaccess.application.port.AccessTokenIssuer;
import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.application.port.RefreshTokenManager;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.AdminUserStatus;
import com.chatbotq.identityaccess.domain.RefreshSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;


public final class RefreshAdminSessionUseCase {
    private final AdminUserRepository users;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenManager refreshTokens;
    private final RefreshSessionRepository sessions;
    private final ApplicationTransaction transactions;
    private final Clock clock;
    private final Duration refreshTtl;


    public RefreshAdminSessionUseCase(AdminUserRepository users, AccessTokenIssuer accessTokens,
                                      RefreshTokenManager refreshTokens,
                                      RefreshSessionRepository sessions, ApplicationTransaction transactions,
                                      Clock clock, Duration refreshTtl) {
        this.users = users;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.transactions = transactions;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
    }

    public AuthenticationTokens execute(String rawRefreshToken) {
        final String hash = safeHash(rawRefreshToken);
        RefreshOutcome outcome = transactions.execute(() -> executeLocked(hash));
        if (outcome.tokens == null) throw InvalidAuthenticationException.refreshToken();
        return outcome.tokens;
    }

    private RefreshOutcome executeLocked(String hash) {
        UUID userId = sessions.findUserIdByTokenHash(hash)
            .orElseThrow(InvalidAuthenticationException::refreshToken);
        AdminUser user = users.findByIdForUpdate(userId).orElse(null);
        RefreshSession current = sessions.findByTokenHashAndLockFamily(hash)
            .orElseThrow(InvalidAuthenticationException::refreshToken);
        Instant now = clock.instant();
        if (current.getRotatedAt() != null) {
            sessions.revokeFamilyOrdered(current.getFamilyId(), now);
            return RefreshOutcome.rejected();
        }
        if (!current.isUsableAt(now)) throw InvalidAuthenticationException.refreshToken();
        if (user == null || user.getStatus() != AdminUserStatus.ACTIVE || user.isLockedAt(now)) {
            sessions.revokeFamilyOrdered(current.getFamilyId(), now);
            return RefreshOutcome.rejected();
        }
        String replacementToken = refreshTokens.generate();
        RefreshSession replacement = RefreshSession.issue(UUID.randomUUID(), current.getUserId(),
            current.getFamilyId(), refreshTokens.hash(replacementToken), now, now.plus(refreshTtl));
        String accessToken = accessTokens.issue(user, now);
        AuthenticationTokens result = new AuthenticationTokens(accessToken, replacementToken,
            accessTokens.getExpiresInSeconds());
        if (!sessions.replaceIfUsable(current, replacement, now)) {
            sessions.revokeFamilyOrdered(current.getFamilyId(), now);
            return RefreshOutcome.rejected();
        }
        return RefreshOutcome.accepted(result);
    }

    private String safeHash(String token) {
        if (token == null || token.trim().isEmpty()) throw InvalidAuthenticationException.refreshToken();
        return refreshTokens.hash(token);
    }


    private static final class RefreshOutcome {
        final AuthenticationTokens tokens;

        private RefreshOutcome(AuthenticationTokens tokens) { this.tokens = tokens; }
        static RefreshOutcome accepted(AuthenticationTokens tokens) { return new RefreshOutcome(tokens); }
        static RefreshOutcome rejected() { return new RefreshOutcome(null); }
    }
}
