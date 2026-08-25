package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.model.AuthenticationTokens;
import com.chatbotq.identityaccess.application.port.AccessTokenIssuer;
import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.PasswordVerifier;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.application.port.RefreshTokenManager;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.RefreshSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthenticationUseCasesTest {
    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private InMemoryUsers users;
    private InMemorySessions sessions;
    private SequenceRefreshTokens refreshTokens;
    private LoginAdminUseCase login;
    private RefreshAdminSessionUseCase refresh;
    private LogoutAdminUseCase logout;
    private AdminUser activeUser;

    @BeforeEach
    void setUp() {
        users = new InMemoryUsers();
        sessions = new InMemorySessions();
        refreshTokens = new SequenceRefreshTokens();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AccessTokenIssuer accessTokens = (user, issuedAt) -> "access-for-" + user.getId();
        PasswordVerifier passwords = (raw, encoded) -> ("valid-password:" + raw).equals(encoded);
        login = new LoginAdminUseCase(users, passwords, "configured-dummy-hash", accessTokens,
            refreshTokens, sessions, clock, REFRESH_TTL);
        refresh = new RefreshAdminSessionUseCase(users, accessTokens, refreshTokens,
            sessions, clock, REFRESH_TTL);
        logout = new LogoutAdminUseCase(refreshTokens, sessions, clock);
        activeUser = AdminUser.create(UUID.randomUUID(), "admin@example.com",
            "valid-password:correct", true, NOW.minusSeconds(60));
        activeUser.activate(NOW.minusSeconds(30));
        users.save(activeUser);
    }

    @Test
    void loginIssuesAccessAndOpaqueRefreshAndStoresOnlyItsHash() {
        AuthenticationTokens result = login.execute("ADMIN@example.com", "correct");

        assertEquals("access-for-" + activeUser.getId(), result.getAccessToken());
        assertEquals("opaque-1", result.getRefreshToken());
        assertEquals(300, result.getAccessExpiresInSeconds());
        assertTrue(sessions.byHash.containsKey("hash:opaque-1"));
        assertFalse(sessions.byHash.containsKey("opaque-1"));
        assertEquals(activeUser.getId(), sessions.byHash.get("hash:opaque-1").getUserId());
    }

    @Test
    void loginRejectsMissingWrongPasswordAndInactiveWithSameFailure() {
        assertSameInvalidCredentials("missing@example.com", "whatever");
        assertSameInvalidCredentials("admin@example.com", "wrong");
        activeUser.disable(NOW);
        assertSameInvalidCredentials("admin@example.com", "correct");
    }

    @Test
    void missingUserUsesTheInjectedDummyHashFromTheConfiguredPasswordPolicy() {
        final String[] verifiedHash = new String[1];
        PasswordVerifier verifier = (raw, encoded) -> {
            verifiedHash[0] = encoded;
            return false;
        };
        LoginAdminUseCase configuredLogin = new LoginAdminUseCase(users, verifier,
            "$2a$12$configured-policy-dummy", (user, issuedAt) -> "unused", refreshTokens,
            sessions, Clock.fixed(NOW, ZoneOffset.UTC), REFRESH_TTL);

        assertThrows(InvalidAuthenticationException.class,
            () -> configuredLogin.execute("missing@example.com", "guess"));

        assertEquals("$2a$12$configured-policy-dummy", verifiedHash[0]);
    }

    @Test
    void loginDoesNotPersistRefreshSessionWhenAccessTokenIssuanceFails() {
        AccessTokenIssuer failingIssuer = (user, issuedAt) -> { throw new IllegalStateException("issuer failed"); };
        LoginAdminUseCase failingLogin = new LoginAdminUseCase(users,
            (raw, encoded) -> ("valid-password:" + raw).equals(encoded), "configured-dummy-hash",
            failingIssuer, refreshTokens, sessions, Clock.fixed(NOW, ZoneOffset.UTC), REFRESH_TTL);

        assertThrows(IllegalStateException.class,
            () -> failingLogin.execute("admin@example.com", "correct"));

        assertTrue(sessions.byHash.isEmpty());
    }

    @Test
    void refreshDoesNotConsumeCurrentSessionWhenAccessTokenIssuanceFails() {
        String currentToken = login.execute("admin@example.com", "correct").getRefreshToken();
        AccessTokenIssuer failingIssuer = (user, issuedAt) -> { throw new IllegalStateException("issuer failed"); };
        RefreshAdminSessionUseCase failingRefresh = new RefreshAdminSessionUseCase(users, failingIssuer,
            refreshTokens, sessions, Clock.fixed(NOW, ZoneOffset.UTC), REFRESH_TTL);

        assertThrows(IllegalStateException.class, () -> failingRefresh.execute(currentToken));

        assertTrue(sessions.byHash.get("hash:" + currentToken).isUsableAt(NOW));
        assertEquals(1, sessions.byHash.size());
    }

    @Test
    void loginRejectsPasswordResetRequiredAndTemporarilyLockedUsers() {
        AdminUser resetRequired = AdminUser.create(UUID.randomUUID(), "reset@example.com",
            "valid-password:correct", true, NOW.minusSeconds(60));
        users.save(resetRequired);
        AdminUser locked = AdminUser.restore(UUID.randomUUID(), "locked@example.com",
            "valid-password:correct", true, com.chatbotq.identityaccess.domain.AdminUserStatus.ACTIVE,
            3, NOW.plusSeconds(60), NOW.minusSeconds(120), NOW.minusSeconds(30));
        users.save(locked);

        assertSameInvalidCredentials("reset@example.com", "correct");
        assertSameInvalidCredentials("locked@example.com", "correct");
    }

    @Test
    void refreshRotatesTokenAndPreservesFamily() {
        AuthenticationTokens original = login.execute("admin@example.com", "correct");
        UUID family = sessions.byHash.get("hash:" + original.getRefreshToken()).getFamilyId();

        AuthenticationTokens rotated = refresh.execute(original.getRefreshToken());

        RefreshSession old = sessions.byHash.get("hash:" + original.getRefreshToken());
        RefreshSession replacement = sessions.byHash.get("hash:" + rotated.getRefreshToken());
        assertEquals("opaque-2", rotated.getRefreshToken());
        assertFalse(old.isUsableAt(NOW));
        assertEquals(family, replacement.getFamilyId());
        assertTrue(replacement.isUsableAt(NOW));
    }

    @Test
    void reusingRotatedTokenRevokesWholeFamily() {
        String original = login.execute("admin@example.com", "correct").getRefreshToken();
        String replacement = refresh.execute(original).getRefreshToken();

        assertThrows(InvalidAuthenticationException.class, () -> refresh.execute(original));
        assertThrows(InvalidAuthenticationException.class, () -> refresh.execute(replacement));
        assertTrue(sessions.familyRevoked);
    }

    @Test
    void logoutIsIdempotentForRepeatedAndUnknownToken() {
        String token = login.execute("admin@example.com", "correct").getRefreshToken();

        logout.execute(token);
        logout.execute(token);
        logout.execute("unknown");

        assertThrows(InvalidAuthenticationException.class, () -> refresh.execute(token));
        assertTrue(sessions.familyRevoked);
    }

    private void assertSameInvalidCredentials(String email, String password) {
        InvalidAuthenticationException failure = assertThrows(
            InvalidAuthenticationException.class, () -> login.execute(email, password));
        assertEquals("invalid credentials", failure.getMessage());
    }

    private static final class InMemoryUsers implements AdminUserRepository {
        private final Map<UUID, AdminUser> byId = new HashMap<>();
        private final Map<String, AdminUser> byEmail = new HashMap<>();

        @Override public boolean existsByEmail(String email) { return byEmail.containsKey(email.toLowerCase()); }
        @Override public Optional<AdminUser> findById(UUID id) { return Optional.ofNullable(byId.get(id)); }
        @Override public Optional<AdminUser> findByEmail(String email) { return Optional.ofNullable(byEmail.get(email.toLowerCase())); }
        @Override public AdminUser save(AdminUser user) {
            byId.put(user.getId(), user); byEmail.put(user.getEmail(), user); return user;
        }
    }

    private static final class InMemorySessions implements RefreshSessionRepository {
        private final Map<String, RefreshSession> byHash = new HashMap<>();
        private boolean familyRevoked;

        @Override public void save(RefreshSession session) { byHash.put(session.getTokenHash(), session); }
        @Override public Optional<RefreshSession> findByTokenHash(String hash) { return Optional.ofNullable(byHash.get(hash)); }
        @Override public boolean replaceIfUsable(RefreshSession current, RefreshSession replacement, Instant now) {
            if (!current.isUsableAt(now)) return false;
            RefreshSession actual = byHash.get(current.getTokenHash());
            RefreshSession created = actual.rotate(replacement.getId(), replacement.getTokenHash(), now,
                replacement.getExpiresAt());
            byHash.put(created.getTokenHash(), created);
            return true;
        }
        @Override public void revokeFamily(UUID familyId, Instant now) {
            familyRevoked = true;
            for (RefreshSession session : byHash.values()) {
                if (familyId.equals(session.getFamilyId())) session.revoke(now);
            }
        }
    }

    private static final class SequenceRefreshTokens implements RefreshTokenManager {
        private int sequence;
        @Override public String generate() { sequence++; return "opaque-" + sequence; }
        @Override public String hash(String token) { return "hash:" + token; }
    }
}
