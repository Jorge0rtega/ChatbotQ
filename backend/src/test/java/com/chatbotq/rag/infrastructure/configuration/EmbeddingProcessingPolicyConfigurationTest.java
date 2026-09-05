package com.chatbotq.rag.infrastructure.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmbeddingProcessingPolicyConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(EmbeddingProcessingPolicyConfiguration.class);

    @Test
    void exposesTheApprovedConservativeDefaults() {
        context.run(application -> {
            assertNull(application.getStartupFailure());
            EmbeddingProcessingLimits limits = application.getBean(EmbeddingProcessingLimits.class);
            assertEquals(1, limits.getMaxEntriesPerManualRun());
            assertEquals(4000, limits.getMaxInputTokensPerEntry());
            assertEquals(100, limits.getMaxEntriesPerDay());
            assertEquals(250000, limits.getMaxInputTokensPerDay());
            assertEquals(10, limits.getMonthlyAlertUsd());
            assertEquals(20, limits.getMonthlyHardLimitUsd());
        });
    }

    @Test
    void acceptsValidLimitOverrides() {
        context.withPropertyValues(
            "chatbotq.embedding.limits.max-entries-per-manual-run=2",
            "chatbotq.embedding.limits.max-input-tokens-per-entry=5000",
            "chatbotq.embedding.limits.max-entries-per-day=101",
            "chatbotq.embedding.limits.max-input-tokens-per-day=250001",
            "chatbotq.embedding.limits.monthly-alert-usd=11",
            "chatbotq.embedding.limits.monthly-hard-limit-usd=21"
        ).run(application -> {
            assertNull(application.getStartupFailure());
            assertEquals(2, application.getBean(EmbeddingProcessingLimits.class).getMaxEntriesPerManualRun());
            assertEquals(21, application.getBean(EmbeddingProcessingLimits.class).getMonthlyHardLimitUsd());
        });
    }

    @Test
    void refusesInvalidBudgetOrVolumeLimits() {
        context.withPropertyValues("chatbotq.embedding.limits.max-entries-per-manual-run=0")
            .run(application -> assertNotNull(application.getStartupFailure()));
        context.withPropertyValues("chatbotq.embedding.limits.max-input-tokens-per-entry=0")
            .run(application -> assertNotNull(application.getStartupFailure()));
        context.withPropertyValues("chatbotq.embedding.limits.max-entries-per-day=0")
            .run(application -> assertNotNull(application.getStartupFailure()));
        context.withPropertyValues("chatbotq.embedding.limits.max-input-tokens-per-day=0")
            .run(application -> assertNotNull(application.getStartupFailure()));
        context.withPropertyValues("chatbotq.embedding.limits.monthly-alert-usd=0")
            .run(application -> assertNotNull(application.getStartupFailure()));
        context.withPropertyValues("chatbotq.embedding.limits.max-entries-per-day=not-a-number")
            .run(application -> assertNotNull(application.getStartupFailure()));
        context.withPropertyValues("chatbotq.embedding.limits.monthly-hard-limit-usd=9")
            .run(application -> assertNotNull(application.getStartupFailure()));
    }
}
