package com.chatbotq.rag.infrastructure.command;

import com.chatbotq.knowledge.application.port.KnowledgeEmbeddingProcessingPort;
import com.chatbotq.knowledge.application.usecase.ProcessOneKnowledgeEmbeddingUseCase;
import com.chatbotq.rag.application.port.EmbeddingProvider;
import com.chatbotq.rag.infrastructure.configuration.EmbeddingProcessingLimits;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the explicit one-shot CLI trigger only when both manual execution and
 * the outbound OpenAI provider are independently opted in.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "chatbotq.embedding.manual", name = "run-once", havingValue = "true")
public class ManualEmbeddingProcessingConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "chatbotq.embedding.openai", name = "enabled", havingValue = "true")
    ProcessOneKnowledgeEmbeddingUseCase processOneKnowledgeEmbeddingUseCase(
            KnowledgeEmbeddingProcessingPort processing, EmbeddingProvider provider) {
        return new ProcessOneKnowledgeEmbeddingUseCase(processing, provider);
    }

    @Bean
    @ConditionalOnProperty(prefix = "chatbotq.embedding.openai", name = "enabled", havingValue = "true")
    ProcessOneKnowledgeEmbeddingCommand processOneKnowledgeEmbeddingCommand(
            ProcessOneKnowledgeEmbeddingUseCase useCase, EmbeddingProcessingLimits limits) {
        return new ProcessOneKnowledgeEmbeddingCommand(useCase, limits);
    }

    @Bean
    @ConditionalOnProperty(prefix = "chatbotq.embedding.openai", name = "enabled", havingValue = "true")
    CommandLineRunner processOneKnowledgeEmbeddingRunner(ProcessOneKnowledgeEmbeddingCommand command) {
        return args -> command.runOnce();
    }
}
