package com.chatbotq.identityaccess.infrastructure.security;

import com.chatbotq.identityaccess.application.port.AccessTokenIssuer;
import com.chatbotq.identityaccess.domain.AdminUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

public final class JwtAccessTokenService implements AccessTokenIssuer {
    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;

    public JwtAccessTokenService(String secret, String issuer, Duration ttl) {
        if (secret == null || secret.trim().isEmpty()
            || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        }
        if (issuer == null || issuer.trim().isEmpty()) {
            throw new IllegalArgumentException("JWT issuer must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("JWT TTL must be between one second and 30 minutes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.ttl = ttl;
    }

    @Override
    public String issue(AdminUser user, Instant issuedAt) {
        return Jwts.builder()
            .setIssuer(issuer)
            .setSubject(user.getId().toString())
            .setId(UUID.randomUUID().toString())
            .setIssuedAt(Date.from(issuedAt))
            .setExpiration(Date.from(issuedAt.plus(ttl)))
            .claim("email", user.getEmail())
            .claim("general_admin", user.isGeneralAdmin())
            .signWith(key, SignatureAlgorithm.HS256)
            .compact();
    }

    @Override
    public long getExpiresInSeconds() {
        return ttl.getSeconds();
    }

    public AdminAccessPrincipal validate(String token, Instant now) {
        if (token == null || token.trim().isEmpty()) {
            throw new InvalidAccessTokenException();
        }
        try {
            Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .requireIssuer(issuer)
                .setClock(() -> Date.from(now))
                .build()
                .parseClaimsJws(token)
                .getBody();
            return new AdminAccessPrincipal(UUID.fromString(claims.getSubject()),
                claims.get("email", String.class),
                Boolean.TRUE.equals(claims.get("general_admin", Boolean.class)));
        } catch (JwtException | IllegalArgumentException failure) {
            throw new InvalidAccessTokenException();
        }
    }
}
