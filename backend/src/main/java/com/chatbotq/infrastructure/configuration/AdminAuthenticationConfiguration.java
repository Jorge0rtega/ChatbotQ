package com.chatbotq.infrastructure.configuration;

import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.PasswordVerifier;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.application.port.RefreshTokenManager;
import com.chatbotq.identityaccess.application.usecase.LoginAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.LogoutAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.RefreshAdminSessionUseCase;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcRefreshSessionRepository;
import com.chatbotq.identityaccess.infrastructure.security.BCryptPasswordHasher;
import com.chatbotq.identityaccess.infrastructure.security.JwtAccessTokenService;
import com.chatbotq.identityaccess.infrastructure.security.SecureRefreshTokenManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class AdminAuthenticationConfiguration {
    @Bean
    RefreshTokenManager refreshTokenManager() {
        return new SecureRefreshTokenManager();
    }

    @Bean
    JwtAccessTokenService jwtAccessTokenService(
        @Value("${chatbotq.security.jwt.secret}") String secret,
        @Value("${chatbotq.security.jwt.issuer}") String issuer,
        @Value("${chatbotq.security.jwt.access-ttl-seconds}") long accessTtlSeconds) {
        return new JwtAccessTokenService(secret, issuer, Duration.ofSeconds(accessTtlSeconds));
    }

    @Bean
    JdbcRefreshSessionRepository refreshSessionRepository(JdbcTemplate jdbc,
                                                           PlatformTransactionManager transactionManager) {
        return new JdbcRefreshSessionRepository(jdbc, new TransactionTemplate(transactionManager));
    }

    @Bean
    String adminDummyPasswordHash(BCryptPasswordHasher passwordHasher) {
        return passwordHasher.hash(UUID.randomUUID().toString());
    }

    @Bean
    LoginAdminUseCase loginAdminUseCase(AdminUserRepository users, PasswordVerifier passwords,
                                         @Qualifier("adminDummyPasswordHash") String dummyPasswordHash,
                                         JwtAccessTokenService accessTokens,
                                         RefreshTokenManager refreshTokens,
                                         RefreshSessionRepository sessions, Clock clock,
                                         @Value("${chatbotq.security.jwt.refresh-ttl-seconds}") long refreshTtlSeconds) {
        return new LoginAdminUseCase(users, passwords, dummyPasswordHash, accessTokens, refreshTokens, sessions,
            clock, refreshDuration(refreshTtlSeconds));
    }

    @Bean
    RefreshAdminSessionUseCase refreshAdminSessionUseCase(AdminUserRepository users,
                                                           JwtAccessTokenService accessTokens,
                                                           RefreshTokenManager refreshTokens,
                                                           RefreshSessionRepository sessions, Clock clock,
                                                           @Value("${chatbotq.security.jwt.refresh-ttl-seconds}") long refreshTtlSeconds) {
        return new RefreshAdminSessionUseCase(users, accessTokens, refreshTokens, sessions,
            clock, refreshDuration(refreshTtlSeconds));
    }

    @Bean
    LogoutAdminUseCase logoutAdminUseCase(RefreshTokenManager tokens,
                                           RefreshSessionRepository sessions, Clock clock) {
        return new LogoutAdminUseCase(tokens, sessions, clock);
    }

    private Duration refreshDuration(long seconds) {
        Duration value = Duration.ofSeconds(seconds);
        if (value.compareTo(Duration.ofHours(1)) < 0 || value.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("refresh TTL must be between one hour and 30 days");
        }
        return value;
    }
}
