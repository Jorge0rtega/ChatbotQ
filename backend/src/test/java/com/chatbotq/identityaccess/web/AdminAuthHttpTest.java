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

        TestBeans() {
            AdminUser user = AdminUser.create(UUID.randomUUID(), "admin@example.com", "hash:correct", true,
                clock.instant().minusSeconds(60));
            user.activate(clock.instant().minusSeconds(30));
            store.save(user);
        }

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
