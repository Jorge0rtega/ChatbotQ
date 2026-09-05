package com.chatbotq.rag.infrastructure.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class EmbeddingProcessingPolicyConfiguration {
    @Bean
    EmbeddingProcessingLimits embeddingProcessingLimits(
            @Value("${chatbotq.embedding.limits.max-entries-per-manual-run:1}") int maxEntriesPerManualRun,
            @Value("${chatbotq.embedding.limits.max-input-tokens-per-entry:4000}") int maxInputTokensPerEntry,
            @Value("${chatbotq.embedding.limits.max-entries-per-day:100}") int maxEntriesPerDay,
            @Value("${chatbotq.embedding.limits.max-input-tokens-per-day:250000}") int maxInputTokensPerDay,
            @Value("${chatbotq.embedding.limits.monthly-alert-usd:10}") int monthlyAlertUsd,
            @Value("${chatbotq.embedding.limits.monthly-hard-limit-usd:20}") int monthlyHardLimitUsd) {
        return new EmbeddingProcessingLimits(maxEntriesPerManualRun, maxInputTokensPerEntry,
            maxEntriesPerDay, maxInputTokensPerDay, monthlyAlertUsd, monthlyHardLimitUsd);
    }
}
