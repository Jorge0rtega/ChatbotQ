package com.chatbotq.identityaccess.web;

import com.chatbotq.identityaccess.application.port.AccessTokenIssuer;
import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.PasswordHasher;
import com.chatbotq.identityaccess.application.port.PasswordVerifier;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.application.port.RefreshTokenManager;
import com.chatbotq.identityaccess.application.usecase.CompleteAdminPasswordResetUseCase;
import com.chatbotq.identityaccess.application.usecase.LoginAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.LogoutAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.RefreshAdminSessionUseCase;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.RefreshSession;
import com.chatbotq.identityaccess.infrastructure.security.JwtAccessTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminAuthController.class)
@Import({AdminSecurityConfiguration.class, AdminAuthExceptionHandler.class, AdminAuthHttpTest.TestBeans.class})
class AdminAuthHttpTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtAccessTokenService jwt;
    @Autowired private Clock clock;
    @Autowired private AdminUserRepository users;
    @Autowired private MemoryCurrentViews currentViews;

    @BeforeEach void resetUsers() {
        MemoryStore store = (MemoryStore) users;
        store.users.clear();
        store.sessions.clear();
        AdminUser user = AdminUser.create(UUID.randomUUID(), "admin@example.com", "hash:correct", true,
            clock.instant().minusSeconds(60));
        user.activate(clock.instant().minusSeconds(30));
        store.save(user);
        store.save(AdminUser.create(UUID.randomUUID(), "reset@example.com", "hash:temporary", false,
            clock.instant().minusSeconds(60)));
        currentViews.calls.set(0);
        currentViews.override = null;
    }

    @Test
    void loginReturnsTokensInJsonBodyAndGenericFailureNeverEchoesCredentials() throws Exception {
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@example.com\",\"password\":\"correct\"}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.refreshToken").isString())
            .andExpect(jsonPath("$.tokenType", is("Bearer")))
            .andExpect(jsonPath("$.expiresIn", is(300)));

        String failure = mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"missing@example.com\",\"password\":\"super-secret-input\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code", is("invalid_credentials")))
            .andReturn().getResponse().getContentAsString();
        assertFalse(failure.contains("missing@example.com"));
        assertFalse(failure.contains("super-secret-input"));
    }

    @Test
    void refreshRotatesAndReuseRevokesReplacementWithoutEchoingToken() throws Exception {
        JsonNode login = response(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"admin@example.com\",\"password\":\"correct\"}"));
        String original = login.get("refreshToken").asText();
        JsonNode rotated = response(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(singleToken(original))));
        String replacement = rotated.get("refreshToken").asText();

        String reuseBody = mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(singleToken(original))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code", is("invalid_refresh_token")))
            .andReturn().getResponse().getContentAsString();
        assertFalse(reuseBody.contains(original));
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(singleToken(replacement))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesSessionAndUsesBodyOnly() throws Exception {
        JsonNode login = response(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"admin@example.com\",\"password\":\"correct\"}"));
        String refresh = login.get("refreshToken").asText();

        mvc.perform(post("/api/admin/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(singleToken(refresh))))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        mvc.perform(post("/api/admin/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(singleToken(refresh))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointRequiresValidBearerJwt() throws Exception {
        JsonNode login = response(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"admin@example.com\",\"password\":\"correct\"}"));

        mvc.perform(get("/api/admin/auth/me"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/auth/me")
                .header("Authorization", "Bearer " + login.get("accessToken").asText()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email", is("admin@example.com")))
            .andExpect(jsonPath("$.generalAdmin", is(true)));
        mvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code", is("invalid_access_token")));
    }

    @Test
    void validSignedTokenUsesCurrentDatabaseIdentityInsteadOfEmailAndRoleClaims() throws Exception {
        MemoryStore store = (MemoryStore) users;
        AdminUser current = store.findByEmail("admin@example.com").get();
        AdminUser staleClaims = AdminUser.restore(current.getId(), "stale@example.com", "hash", false,
            com.chatbotq.identityaccess.domain.AdminUserStatus.ACTIVE, 0, null,
            clock.instant().minusSeconds(120), clock.instant().minusSeconds(60));
        String token = jwt.issue(staleClaims, clock.instant());

        mvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email", is("admin@example.com")))
            .andExpect(jsonPath("$.generalAdmin", is(true)));
    }

    @Test
    void meUsesOnlyAtomicViewWhenIdentityChangesAfterFilterAndIgnoresClaims() throws Exception {
        MemoryStore store = (MemoryStore) users;
        AdminUser current = store.findByEmail("admin@example.com").get();
        AdminUser staleClaims = AdminUser.restore(current.getId(), "claim@example.com", "hash", true,
            com.chatbotq.identityaccess.domain.AdminUserStatus.ACTIVE, 0, null,
            clock.instant().minusSeconds(120), clock.instant().minusSeconds(60));
        UUID project = UUID.fromString("00000000-0000-0000-0000-000000000123");
        currentViews.override = new com.chatbotq.identityaccess.application.model.CurrentAdminView(
            current.getId(), "changed-after-filter@example.com", false,
            java.util.Collections.singletonList(project));

        mvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + jwt.issue(staleClaims, clock.instant())))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.email", is("changed-after-filter@example.com")))
            .andExpect(jsonPath("$.generalAdmin", is(false)))
            .andExpect(jsonPath("$.projectIds[0]", is(project.toString())));
        org.junit.jupiter.api.Assertions.assertEquals(1, currentViews.calls.get());
    }

    @Test
    void validSignedTokenForRevokedAdminIsForbiddenBeforeController() throws Exception {
        MemoryStore store = (MemoryStore) users;
        AdminUser current = store.findByEmail("admin@example.com").get();
        String token = jwt.issue(current, clock.instant());

        current.disable(clock.instant());
        assertForbiddenWithoutController(token);

        AdminUser locked = AdminUser.restore(current.getId(), current.getEmail(), current.getPasswordHash(), true,
            com.chatbotq.identityaccess.domain.AdminUserStatus.ACTIVE, 5, clock.instant().plusSeconds(60),
            current.getCreatedAt(), clock.instant());
        store.save(locked);
        assertForbiddenWithoutController(token);

        store.users.remove(current.getId());
        assertForbiddenWithoutController(token);
    }

    private void assertForbiddenWithoutController(String token) throws Exception {
        int before = currentViews.calls.get();
        mvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code", is("admin_access_revoked")));
        org.junit.jupiter.api.Assertions.assertEquals(before, currentViews.calls.get());
    }

    @Test
    void repositoryFailureInFilterPropagatesForSanitized500AndAlwaysClearsContext() throws Exception {
        AdminUser current = ((MemoryStore) users).findByEmail("admin@example.com").get();
        String token = jwt.issue(current, clock.instant());
        AdminUserRepository failing = new AdminUserRepository() {
            public boolean existsByEmail(String email) { return false; }
            public Optional<AdminUser> findById(UUID id) {
                throw new org.springframework.dao.DataAccessResourceFailureException(
                    "select password_hash from admin_user -- secret SQL");
            }
            public AdminUser save(AdminUser user) { return user; }
        };
        org.springframework.mock.web.MockHttpServletRequest request =
            new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/admin/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.TestingAuthenticationToken("stale", null));

        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
            () -> new JwtAdminAuthenticationFilter(jwt, failing, clock).doFilter(request, response,
                (req, res) -> chained.set(true)));

        org.junit.jupiter.api.Assertions.assertNull(
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
        assertFalse(chained.get());
        assertFalse(response.getContentAsString().contains("admin_user"));
        assertFalse(response.getContentAsString().contains("password_hash"));
    }

    @Test
    void publicAuthRoutesIgnoreInvalidAndExpiredBearerWhileMeRemainsProtected() throws Exception {
        AdminUser tokenUser = AdminUser.create(UUID.randomUUID(), "token@example.com", "hash", true,
            clock.instant().minusSeconds(1200));
        tokenUser.activate(clock.instant().minusSeconds(1100));
        String expired = jwt.issue(tokenUser, clock.instant().minusSeconds(600));

        JsonNode firstLogin = response(post("/api/admin/auth/login")
            .header("Authorization", "Bearer invalid")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"admin@example.com\",\"password\":\"correct\"}"));
        response(post("/api/admin/auth/login")
            .header("Authorization", "Bearer " + expired)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"admin@example.com\",\"password\":\"correct\"}"));

        JsonNode rotated = response(post("/api/admin/auth/refresh")
            .header("Authorization", "Bearer invalid")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(singleToken(firstLogin.get("refreshToken").asText()))));
        JsonNode rotatedAgain = response(post("/api/admin/auth/refresh")
            .header("Authorization", "Bearer " + expired)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(singleToken(rotated.get("refreshToken").asText()))));

        mvc.perform(post("/api/admin/auth/logout")
                .header("Authorization", "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(singleToken(rotatedAgain.get("refreshToken").asText()))))
            .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/auth/logout")
                .header("Authorization", "Bearer " + expired)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(singleToken(rotatedAgain.get("refreshToken").asText()))))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer " + expired))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code", is("invalid_access_token")));
    }

    @Test
    void completePasswordResetRemainsPublicWhenCallerSuppliesAnInvalidOptionalAuthorizationHeader()
            throws Exception {
        mvc.perform(post("/api/admin/auth/complete-password-reset")
                .header("Authorization", "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"reset@example.com\",\"temporaryPassword\":\"temporary\","
                    + "\"newPassword\":\"NewPassword123\"}"))
            .andExpect(status().isNoContent())
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
        throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private Map<String, String> singleToken(String token) {
        Map<String, String> body = new HashMap<>(); body.put("refreshToken", token); return body;
    }

    @TestConfiguration
    static class TestBeans {
        private final Clock clock = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);
        private final MemoryStore store = new MemoryStore();
        private final SequenceTokens refreshTokens = new SequenceTokens();

        @Bean Clock authClock() { return clock; }
        @Bean JwtAccessTokenService jwt() {
            return new JwtAccessTokenService("test-only-signing-key-at-least-thirty-two-bytes-long",
                "chatbotq-test", Duration.ofMinutes(5));
        }
        @Bean ApplicationTransaction transaction() {
            return new ApplicationTransaction() {
                public <T> T execute(java.util.function.Supplier<T> work) { return work.get(); }
            };
        }
        @Bean AdminUserRepository authUsers() { return store; }
        @Bean MemoryCurrentViews currentViews() { return new MemoryCurrentViews(store); }
        @Bean com.chatbotq.identityaccess.application.usecase.GetCurrentAdminViewUseCase currentAdminView(
                MemoryCurrentViews port) {
            return new com.chatbotq.identityaccess.application.usecase.GetCurrentAdminViewUseCase(port);
        }
        @Bean LoginAdminUseCase login(JwtAccessTokenService jwt, ApplicationTransaction transaction) {
            PasswordVerifier passwords = (raw, encoded) -> ("hash:" + raw).equals(encoded);
            return new LoginAdminUseCase(store, passwords, "test-dummy-hash", jwt,
                refreshTokens, store, transaction, clock, Duration.ofDays(7));
        }
        @Bean RefreshAdminSessionUseCase refresh(JwtAccessTokenService jwt, ApplicationTransaction transaction) {
            return new RefreshAdminSessionUseCase(
                store, jwt, refreshTokens, store, transaction, clock, Duration.ofDays(7));
        }
        @Bean LogoutAdminUseCase logout(ApplicationTransaction transaction) {
            return new LogoutAdminUseCase(refreshTokens, store, store, transaction, clock);
        }
        @Bean CompleteAdminPasswordResetUseCase complete(ApplicationTransaction transaction) {
            PasswordVerifier verifier = (raw, encoded) -> ("hash:" + raw).equals(encoded);
            PasswordHasher hasher = raw -> "hash:" + raw;
            return new CompleteAdminPasswordResetUseCase(store, verifier, hasher, store, transaction, clock);
        }
    }

    static final class MemoryCurrentViews implements
            com.chatbotq.identityaccess.application.port.CurrentAdminViewPort {
        final AtomicInteger calls = new AtomicInteger();
        final MemoryStore store;
        volatile com.chatbotq.identityaccess.application.model.CurrentAdminView override;
        MemoryCurrentViews(MemoryStore store) { this.store = store; }
        public Optional<com.chatbotq.identityaccess.application.model.CurrentAdminView> findAvailable(UUID userId) {
            calls.incrementAndGet();
            if (override != null) return Optional.of(override);
            AdminUser user = store.users.get(userId);
            if (user == null || user.getStatus() != com.chatbotq.identityaccess.domain.AdminUserStatus.ACTIVE) {
                return Optional.empty();
            }
            return Optional.of(new com.chatbotq.identityaccess.application.model.CurrentAdminView(
                user.getId(), user.getEmail(), user.isGeneralAdmin(), java.util.Collections.<UUID>emptyList()));
        }
    }

    static final class SequenceTokens implements RefreshTokenManager {
        int next;
        public String generate() { return "opaque-" + (++next); }
        public String hash(String token) { return "hash:" + token; }
    }

    static final class MemoryStore implements AdminUserRepository, RefreshSessionRepository {
        final Map<UUID, AdminUser> users = new HashMap<>();
        final Map<String, RefreshSession> sessions = new HashMap<>();
        public boolean existsByEmail(String email) { return findByEmail(email).isPresent(); }
        public Optional<AdminUser> findById(UUID id) { return Optional.ofNullable(users.get(id)); }
        public Optional<AdminUser> findByEmail(String email) {
            return users.values().stream().filter(u -> u.getEmail().equalsIgnoreCase(email)).findFirst();
        }
        public AdminUser save(AdminUser user) { users.put(user.getId(), user); return user; }
        public void completePasswordReset(UUID id, String hash, Instant now) { }
        public void save(RefreshSession session) { sessions.put(session.getTokenHash(), session); }
        public Optional<RefreshSession> findByTokenHash(String hash) { return Optional.ofNullable(sessions.get(hash)); }
        public boolean replaceIfUsable(RefreshSession current, RefreshSession replacement, Instant now) {
            if (!current.isUsableAt(now)) return false;
            current.rotate(replacement.getId(), replacement.getTokenHash(), now, replacement.getExpiresAt());
            sessions.put(replacement.getTokenHash(), replacement); return true;
        }
        public void revokeFamilyOrdered(UUID family, Instant now) {
            sessions.values().stream().filter(s -> family.equals(s.getFamilyId())).forEach(s -> s.revoke(now));
        }
        public void revokeAllByUserOrdered(UUID userId, Instant now) {
            sessions.values().stream().filter(s -> userId.equals(s.getUserId())).forEach(s -> s.revoke(now));
        }
    }
}
