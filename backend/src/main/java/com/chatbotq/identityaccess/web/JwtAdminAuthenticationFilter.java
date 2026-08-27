package com.chatbotq.identityaccess.web;

import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.AdminUserStatus;
import com.chatbotq.identityaccess.infrastructure.security.AdminAccessPrincipal;
import com.chatbotq.identityaccess.infrastructure.security.InvalidAccessTokenException;
import com.chatbotq.identityaccess.infrastructure.security.JwtAccessTokenService;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Collections;

public final class JwtAdminAuthenticationFilter extends OncePerRequestFilter {
    private final JwtAccessTokenService tokens;
    private final AdminUserRepository users;
    private final Clock clock;

    public JwtAdminAuthenticationFilter(JwtAccessTokenService tokens, AdminUserRepository users, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean adminPath = "/api/admin".equals(path) || path.startsWith("/api/admin/");
        return !adminPath
            || "/api/admin/auth/login".equals(path)
            || "/api/admin/auth/refresh".equals(path)
            || "/api/admin/auth/logout".equals(path)
            || "/api/admin/auth/complete-password-reset".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            unauthorized(response);
            return;
        }
        try {
            AdminAccessPrincipal tokenPrincipal = tokens.validate(authorization.substring(7), clock.instant());
            AdminUser current = users.findById(tokenPrincipal.getUserId()).orElse(null);
            if (current == null || current.getStatus() != AdminUserStatus.ACTIVE
                    || current.isLockedAt(clock.instant())) {
                SecurityContextHolder.clearContext();
                forbidden(response);
                return;
            }
            AdminAccessPrincipal principal = new AdminAccessPrincipal(
                current.getId(), current.getEmail(), current.isGeneralAdmin());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (InvalidAccessTokenException invalid) {
            SecurityContextHolder.clearContext();
            unauthorized(response);
        } catch (RuntimeException infrastructureOrApplicationFailure) {
            SecurityContextHolder.clearContext();
            throw infrastructureOrApplicationFailure;
        }
    }

    static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"invalid_access_token\"}");
    }

    static void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"admin_access_revoked\"}");
    }
}
