package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.model.AuthenticationTokens;
import com.chatbotq.identityaccess.application.port.AccessTokenIssuer;
import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.port.PasswordVerifier;
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


public final class LoginAdminUseCase {
    private final AdminUserRepository users;
    private final PasswordVerifier passwords;
    private final String dummyPasswordHash;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenManager refreshTokens;
    private final RefreshSessionRepository sessions;
    private final ApplicationTransaction transactions;
    private final Clock clock;
    private final Duration refreshTtl;


    public LoginAdminUseCase(AdminUserRepository users, PasswordVerifier passwords,
                             String dummyPasswordHash, AccessTokenIssuer accessTokens,
                             RefreshTokenManager refreshTokens, RefreshSessionRepository sessions,
                             ApplicationTransaction transactions, Clock clock, Duration refreshTtl) {
        this.users = users;
        this.passwords = passwords;
        this.dummyPasswordHash = dummyPasswordHash;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.transactions = transactions;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
    }

    public AuthenticationTokens execute(final String email, final String password) {
        return transactions.execute(() -> executeLocked(email, password));
    }

    private AuthenticationTokens executeLocked(String email, String password) {
        Optional<AdminUser> found = users.findByEmailForUpdate(email == null ? "" : email.trim());
        AdminUser user = found.orElse(null);
        String passwordHash = user == null ? dummyPasswordHash : user.getPasswordHash();
        boolean passwordMatches = passwords.matches(password, passwordHash);
        if (user == null || !passwordMatches || user.getStatus() != AdminUserStatus.ACTIVE
                || user.isLockedAt(clock.instant())) {
            throw InvalidAuthenticationException.credentials();
        }
        return issue(user, UUID.randomUUID());
    }

    private AuthenticationTokens issue(AdminUser user, UUID familyId) {
        Instant now = clock.instant();
        String refreshToken = refreshTokens.generate();
        RefreshSession session = RefreshSession.issue(UUID.randomUUID(), user.getId(), familyId,
            refreshTokens.hash(refreshToken), now, now.plus(refreshTtl));
        String accessToken = accessTokens.issue(user, now);
        AuthenticationTokens result = new AuthenticationTokens(accessToken, refreshToken,
            accessTokens.getExpiresInSeconds());
        sessions.save(session);
        return result;
    }

}
