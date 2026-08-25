package com.chatbotq.infrastructure.configuration;

import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.infrastructure.security.BCryptPasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AdminAuthenticationConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(AdminAuthenticationConfiguration.class)
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(AdminUserRepository.class, () -> mock(AdminUserRepository.class))
        .withBean(BCryptPasswordHasher.class, () -> new BCryptPasswordHasher(4))
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withBean(Clock.class, Clock::systemUTC)
        .withPropertyValues(
            "chatbotq.security.jwt.issuer=chatbotq-test",
            "chatbotq.security.jwt.access-ttl-seconds=300",
            "chatbotq.security.jwt.refresh-ttl-seconds=604800");

    @Test
    void startupFailsWhenJwtSecretIsAbsent() {
        context.run(result -> assertThat(result).hasFailed());
    }

    @Test
    void startupFailsWhenJwtSecretIsInvalid() {
        context.withPropertyValues("chatbotq.security.jwt.secret=short")
            .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void startupSucceedsWithExplicitValidJwtMaterial() {
        context.withPropertyValues(
                "chatbotq.security.jwt.secret=test-fixture-signing-material-with-thirty-two-bytes")
            .run(result -> assertThat(result).hasNotFailed());
    }

    @Test
    void dummyPasswordHashUsesTheConfiguredBcryptCost() {
        String dummyHash = new AdminAuthenticationConfiguration()
            .adminDummyPasswordHash(new BCryptPasswordHasher(4));

        assertEquals("04", dummyHash.substring(4, 6));
    }
}
