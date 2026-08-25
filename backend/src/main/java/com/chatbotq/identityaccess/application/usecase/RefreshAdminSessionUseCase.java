package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.model.AuthenticationTokens;
import com.chatbotq.identityaccess.application.port.AccessTokenIssuer;
import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.application.port.RefreshTokenManager;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.AdminUserStatus;
import com.chatbotq.identityaccess.domain.RefreshSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class RefreshAdminSessionUseCase {
    private final AdminUserRepository users;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenManager refreshTokens;
    private final RefreshSessionRepository sessions;
    private final Clock clock;
    private final Duration refreshTtl;

    public RefreshAdminSessionUseCase(AdminUserRepository users, AccessTokenIssuer accessTokens,
                                      RefreshTokenManager refreshTokens,
                                      RefreshSessionRepository sessions, Clock clock,
                                      Duration refreshTtl) {
        this.users = users;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
    }

    public AuthenticationTokens execute(String rawRefreshToken) {
        String hash = safeHash(rawRefreshToken);
        RefreshSession current = sessions.findByTokenHash(hash)
            .orElseThrow(InvalidAuthenticationException::refreshToken);
        Instant now = clock.instant();
        if (current.getRotatedAt() != null) {
            sessions.revokeFamily(current.getFamilyId(), now);
            throw InvalidAuthenticationException.refreshToken();
        }
        if (!current.isUsableAt(now)) {
            throw InvalidAuthenticationException.refreshToken();
        }
        Optional<AdminUser> foundUser = users.findById(current.getUserId());
        AdminUser user = foundUser.orElse(null);
        if (user == null || user.getStatus() != AdminUserStatus.ACTIVE || user.isLockedAt(now)) {
            sessions.revokeFamily(current.getFamilyId(), now);
            throw InvalidAuthenticationException.refreshToken();
        }
        String replacementToken = refreshTokens.generate();
        RefreshSession replacement = RefreshSession.issue(UUID.randomUUID(), current.getUserId(),
            current.getFamilyId(), refreshTokens.hash(replacementToken), now, now.plus(refreshTtl));
        String accessToken = accessTokens.issue(user, now);
        AuthenticationTokens result = new AuthenticationTokens(accessToken, replacementToken,
            accessTokens.getExpiresInSeconds());
        if (!sessions.replaceIfUsable(current, replacement, now)) {
            sessions.revokeFamily(current.getFamilyId(), now);
            throw InvalidAuthenticationException.refreshToken();
        }
        return result;
    }

    private String safeHash(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw InvalidAuthenticationException.refreshToken();
        }
        return refreshTokens.hash(token);
    }
}
