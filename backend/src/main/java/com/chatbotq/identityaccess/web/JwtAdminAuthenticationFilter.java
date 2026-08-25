package com.chatbotq.identityaccess.web;

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
    private final Clock clock;

    public JwtAdminAuthenticationFilter(JwtAccessTokenService tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean adminPath = "/api/admin".equals(path) || path.startsWith("/api/admin/");
        return !adminPath
            || "/api/admin/auth/login".equals(path)
            || "/api/admin/auth/refresh".equals(path)
            || "/api/admin/auth/logout".equals(path);
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
            AdminAccessPrincipal principal = tokens.validate(authorization.substring(7), clock.instant());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (InvalidAccessTokenException invalid) {
            SecurityContextHolder.clearContext();
            unauthorized(response);
        }
    }

    static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"invalid_access_token\"}");
    }
}
