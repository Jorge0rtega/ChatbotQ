package com.chatbotq.rag.infrastructure.command;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;
import com.chatbotq.knowledge.application.port.EmbeddingBudgetReservationPort;
import com.chatbotq.knowledge.application.port.KnowledgeEmbeddingProcessingPort;
import com.chatbotq.rag.application.port.EmbeddingProvider;
import com.chatbotq.rag.infrastructure.configuration.EmbeddingProcessingPolicyConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ManualEmbeddingProcessingConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(EmbeddingProcessingPolicyConfiguration.class,
            ManualEmbeddingProcessingConfiguration.class, TestInfrastructureConfiguration.class);

    @Test
    void doesNotRegisterRunnerWithoutBothExplicitOptIns() {
        context.withPropertyValues(
            "chatbotq.embedding.openai.enabled=true"
        ).run(application -> {
            assertNull(application.getStartupFailure());
            assertEquals(0, application.getBeansOfType(CommandLineRunner.class).size());
        });

        context.withPropertyValues(
            "chatbotq.embedding.manual.run-once=true"
        ).run(application -> {
            assertNull(application.getStartupFailure());
            assertEquals(0, application.getBeansOfType(CommandLineRunner.class).size());
        });
    }

    @Test
    void registersOneRunnerThatProcessesExactlyOneEntryWhenBothOptInsAreEnabled() throws Exception {
        context.withPropertyValues(
            "chatbotq.embedding.openai.enabled=true",
            "chatbotq.embedding.manual.run-once=true"
        ).run(application -> {
            assertNull(application.getStartupFailure());
            assertEquals(1, application.getBeansOfType(CommandLineRunner.class).size());
            RecordingProcessingPort processing = (RecordingProcessingPort) application.getBean(KnowledgeEmbeddingProcessingPort.class);

            try {
                application.getBean(CommandLineRunner.class).run();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }

            assertEquals(1, processing.claims);
            assertEquals(1, processing.readyCalls);
        });
    }

    @Test
    void refusesManualRunnerWhenTheConfiguredManualLimitIsNotExactlyOne() {
        context.withPropertyValues(
            "chatbotq.embedding.openai.enabled=true",
            "chatbotq.embedding.manual.run-once=true",
            "chatbotq.embedding.limits.max-entries-per-manual-run=2"
        ).run(application -> assertNotNull(application.getStartupFailure()));
    }

    @Test
    void springApplicationInvokesTheRunnerExactlyOnceWhenBothOptInsAreEnabled() {
        ConfigurableApplicationContext application = new SpringApplicationBuilder(
            EmbeddingProcessingPolicyConfiguration.class,
            ManualEmbeddingProcessingConfiguration.class,
            TestInfrastructureConfiguration.class
        ).web(WebApplicationType.NONE).properties(
            "chatbotq.embedding.openai.enabled=true",
            "chatbotq.embedding.manual.run-once=true",
            "spring.main.banner-mode=off",
            "logging.level.root=OFF"
        ).run();

        try {
            RecordingProcessingPort processing = (RecordingProcessingPort) application
                .getBean(KnowledgeEmbeddingProcessingPort.class);
            assertEquals(1, processing.claims);
            assertEquals(1, processing.readyCalls);
        } finally {
            application.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestInfrastructureConfiguration {
        @Bean
        KnowledgeEmbeddingProcessingPort knowledgeEmbeddingProcessingPort() {
            return new RecordingProcessingPort();
        }

        @Bean
        EmbeddingBudgetReservationPort testEmbeddingBudgetReservationPort() {
            return claim -> EmbeddingBudgetReservationPort.Decision.RESERVED;
        }

        @Bean
        EmbeddingProvider embeddingProvider() {
            return input -> new float[1536];
        }
    }

    static final class RecordingProcessingPort implements KnowledgeEmbeddingProcessingPort {
        private int claims;
        private int readyCalls;

        @Override
        public Optional<ClaimedKnowledgeEmbedding> claimOnePending() {
            claims++;
            return Optional.of(new ClaimedKnowledgeEmbedding(java.util.UUID.randomUUID(), 1L, "question"));
        }

        @Override
        public boolean recordProviderAttempt(ClaimedKnowledgeEmbedding claim) {
            return true;
        }

        @Override
        public boolean markReady(ClaimedKnowledgeEmbedding claim, float[] embedding) {
            readyCalls++;
            return true;
        }

        @Override
        public boolean markFailed(ClaimedKnowledgeEmbedding claim, String errorCode, String errorMessage) {
            return true;
        }
    }
}
