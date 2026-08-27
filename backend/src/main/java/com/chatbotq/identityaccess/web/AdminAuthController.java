package com.chatbotq.identityaccess.web;

import com.chatbotq.identityaccess.application.model.AuthenticationTokens;
import com.chatbotq.identityaccess.application.model.CurrentAdminView;
import com.chatbotq.identityaccess.application.usecase.CompleteAdminPasswordResetUseCase;
import com.chatbotq.identityaccess.application.usecase.LoginAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.LogoutAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.RefreshAdminSessionUseCase;
import com.chatbotq.identityaccess.application.usecase.GetCurrentAdminViewUseCase;
import com.chatbotq.identityaccess.infrastructure.security.AdminAccessPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    private final LoginAdminUseCase login;
    private final RefreshAdminSessionUseCase refresh;
    private final LogoutAdminUseCase logout;
    private final CompleteAdminPasswordResetUseCase completePasswordReset;
    private final GetCurrentAdminViewUseCase currentAdmin;

    public AdminAuthController(LoginAdminUseCase login, RefreshAdminSessionUseCase refresh,
                               LogoutAdminUseCase logout, CompleteAdminPasswordResetUseCase completePasswordReset,
                               GetCurrentAdminViewUseCase currentAdmin) {
        this.login = login;
        this.refresh = refresh;
        this.logout = logout;
        this.completePasswordReset = completePasswordReset;
        this.currentAdmin = currentAdmin;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) {
        requireText(request.email, "email");
        requireText(request.password, "password");
        return tokenResponse(login.execute(request.email, request.password));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest request) {
        requireText(request.refreshToken, "refreshToken");
        return tokenResponse(refresh.execute(request.refreshToken));
    }

    @PostMapping("/complete-password-reset")
    public ResponseEntity<Void> completePasswordReset(@RequestBody CompletePasswordResetRequest request) {
        requireText(request.email, "email");
        requireText(request.temporaryPassword, "temporaryPassword");
        requireText(request.newPassword, "newPassword");
        completePasswordReset.execute(request.email, request.temporaryPassword, request.newPassword);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest request) {
        requireText(request.refreshToken, "refreshToken");
        logout.execute(request.refreshToken);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal AdminAccessPrincipal principal) {
        CurrentAdminView view = currentAdmin.execute(principal.getUserId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MeResponse(
            view.getUserId().toString(), view.getEmail(), view.isGeneralAdmin(), view.getProjectIds()));
    }

    private ResponseEntity<TokenResponse> tokenResponse(AuthenticationTokens tokens) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new TokenResponse(tokens.getAccessToken(), tokens.getRefreshToken(),
                "Bearer", tokens.getAccessExpiresInSeconds()));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }

    public static final class LoginRequest {
        public String email;
        public String password;
    }

    public static final class CompletePasswordResetRequest {
        public String email;
        public String temporaryPassword;
        public String newPassword;
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown property");
        }
    }

    public static final class RefreshRequest {
        public String refreshToken;
    }

    public static final class TokenResponse {
        private final String accessToken;
        private final String refreshToken;
        private final String tokenType;
        private final long expiresIn;
        TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
            this.accessToken = accessToken; this.refreshToken = refreshToken;
            this.tokenType = tokenType; this.expiresIn = expiresIn;
        }
        public String getAccessToken() { return accessToken; }
        public String getRefreshToken() { return refreshToken; }
        public String getTokenType() { return tokenType; }
        public long getExpiresIn() { return expiresIn; }
    }

    public static final class MeResponse {
        private final String userId;
        private final String email;
        private final boolean generalAdmin;
        private final List<UUID> projectIds;
        MeResponse(String userId, String email, boolean generalAdmin, List<UUID> projectIds) {
            this.userId = userId; this.email = email; this.generalAdmin = generalAdmin; this.projectIds = projectIds;
        }
        public String getUserId() { return userId; }
        public String getEmail() { return email; }
        public boolean isGeneralAdmin() { return generalAdmin; }
        public List<UUID> getProjectIds() { return projectIds; }
    }
}
