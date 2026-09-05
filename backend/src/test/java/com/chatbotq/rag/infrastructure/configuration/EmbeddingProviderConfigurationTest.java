package com.chatbotq.rag.infrastructure.configuration;

import com.chatbotq.knowledge.application.usecase.ProcessOneKnowledgeEmbeddingUseCase;
import com.chatbotq.rag.application.port.EmbeddingProvider;
import com.chatbotq.rag.infrastructure.provider.OpenAiEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingProviderConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(EmbeddingProviderConfiguration.class);

    @Test
    void doesNotCreateAnEmbeddingProviderUnlessOpenAiIsExplicitlyEnabled() {
        context.run(application -> {
            assertNull(application.getStartupFailure());
            assertEquals(0, application.getBeansOfType(EmbeddingProvider.class).size());
        });
    }

    @Test
    void createsOpenAiProviderOnlyWhenEnabledWithAnApiKey() {
        context.withPropertyValues(
            "chatbotq.embedding.openai.enabled=true",
            "chatbotq.embedding.openai.api-key=test-fixture-key"
        ).run(application -> {
            assertNull(application.getStartupFailure());
            assertEquals(1, application.getBeansOfType(EmbeddingProvider.class).size());
            assertEquals(0, application.getBeansOfType(ProcessOneKnowledgeEmbeddingUseCase.class).size());
            assertTrue(application.getBean(EmbeddingProvider.class) instanceof OpenAiEmbeddingClient);
        });
    }

    @Test
    void refusesToInstantiateAProviderWhenEnabledWithBlankApiKey() {
        context.withPropertyValues(
            "chatbotq.embedding.openai.enabled=true",
            "chatbotq.embedding.openai.api-key= "
        ).run(application -> {
            assertNotNull(application.getStartupFailure());
        });
    }

    @Test
    void refusesToInstantiateAProviderWhenEnabledWithNonPositiveTimeout() {
        context.withPropertyValues(
            "chatbotq.embedding.openai.enabled=true",
            "chatbotq.embedding.openai.api-key=test-fixture-key",
            "chatbotq.embedding.openai.connect-timeout-ms=0"
        ).run(application -> {
            assertNotNull(application.getStartupFailure());
        });
    }
}
