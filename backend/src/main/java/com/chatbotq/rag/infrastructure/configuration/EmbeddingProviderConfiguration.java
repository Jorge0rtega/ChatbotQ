package com.chatbotq.rag.infrastructure.configuration;

import com.chatbotq.rag.application.port.EmbeddingProvider;
import com.chatbotq.rag.infrastructure.provider.OpenAiEmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Creates outbound embedding infrastructure only through explicit opt-in.
 * Scheduling and invocation are intentionally configured separately.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "chatbotq.embedding.openai", name = "enabled", havingValue = "true")
public class EmbeddingProviderConfiguration {

    @Bean("openAiEmbeddingProvider")
    EmbeddingProvider openAiEmbeddingProvider(
            @Value("${chatbotq.embedding.openai.endpoint:https://api.openai.com/v1/embeddings}") String endpoint,
            @Value("${chatbotq.embedding.openai.api-key}") String apiKey,
            @Value("${chatbotq.embedding.openai.model:text-embedding-3-small}") String model,
            @Value("${chatbotq.embedding.openai.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${chatbotq.embedding.openai.read-timeout-ms:10000}") int readTimeoutMs) throws IOException {
        return new OpenAiEmbeddingClient(endpoint, apiKey, model, connectTimeoutMs, readTimeoutMs);
    }
}
