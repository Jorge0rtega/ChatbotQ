package com.chatbotq.infrastructure.configuration;

import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.port.PasswordHasher;
import com.chatbotq.identityaccess.application.port.PasswordVerifier;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.application.port.RefreshTokenManager;
import com.chatbotq.identityaccess.application.usecase.CompleteAdminPasswordResetUseCase;
import com.chatbotq.identityaccess.application.usecase.LoginAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.LogoutAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.RefreshAdminSessionUseCase;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcRefreshSessionRepository;
import com.chatbotq.identityaccess.infrastructure.security.BCryptPasswordHasher;
import com.chatbotq.identityaccess.infrastructure.security.JwtAccessTokenService;
import com.chatbotq.identityaccess.infrastructure.security.SecureRefreshTokenManager;
import com.chatbotq.infrastructure.transaction.SpringApplicationTransaction;
import com.fasterxml.jackson.core.JsonParser;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
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
    Jackson2ObjectMapperBuilderCustomizer rejectDuplicateJsonFields() {
        return builder -> builder.featuresToEnable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Bean
    ApplicationTransaction applicationTransaction(PlatformTransactionManager transactionManager) {
        return new SpringApplicationTransaction(new TransactionTemplate(transactionManager));
    }

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
    JdbcRefreshSessionRepository refreshSessionRepository(JdbcTemplate jdbc) {
        return new JdbcRefreshSessionRepository(jdbc);
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
                                         RefreshSessionRepository sessions, ApplicationTransaction transactions,
                                         Clock clock,
                                         @Value("${chatbotq.security.jwt.refresh-ttl-seconds}") long refreshTtlSeconds) {
        return new LoginAdminUseCase(users, passwords, dummyPasswordHash, accessTokens, refreshTokens, sessions,
            transactions, clock, refreshDuration(refreshTtlSeconds));
    }

    @Bean
    RefreshAdminSessionUseCase refreshAdminSessionUseCase(AdminUserRepository users,
                                                           JwtAccessTokenService accessTokens,
                                                           RefreshTokenManager refreshTokens,
                                                           RefreshSessionRepository sessions,
                                                           ApplicationTransaction transactions, Clock clock,
                                                           @Value("${chatbotq.security.jwt.refresh-ttl-seconds}") long refreshTtlSeconds) {
        return new RefreshAdminSessionUseCase(users, accessTokens, refreshTokens, sessions,
            transactions, clock, refreshDuration(refreshTtlSeconds));
    }

    @Bean
    CompleteAdminPasswordResetUseCase completeAdminPasswordResetUseCase(
            AdminUserRepository users, PasswordVerifier verifier, PasswordHasher hasher,
            @Qualifier("adminDummyPasswordHash") String dummyPasswordHash,
            RefreshSessionRepository sessions, ApplicationTransaction transactions, Clock clock) {
        return new CompleteAdminPasswordResetUseCase(users, verifier, hasher, dummyPasswordHash,
            sessions, transactions, clock);
    }

    @Bean
    LogoutAdminUseCase logoutAdminUseCase(RefreshTokenManager tokens, AdminUserRepository users,
                                           RefreshSessionRepository sessions,
                                           ApplicationTransaction transactions, Clock clock) {
        return new LogoutAdminUseCase(tokens, users, sessions, transactions, clock);
    }

    private Duration refreshDuration(long seconds) {
        Duration value = Duration.ofSeconds(seconds);
        if (value.compareTo(Duration.ofHours(1)) < 0 || value.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("refresh TTL must be between one hour and 30 days");
        }
        return value;
    }
}
