package com.chatbotq.rag.infrastructure.configuration;

import com.chatbotq.knowledge.application.usecase.ProcessOneKnowledgeEmbeddingUseCase;
import com.chatbotq.rag.application.port.EmbeddingProvider;
import com.chatbotq.rag.infrastructure.provider.RetryingEmbeddingProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingProviderConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
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
            assertTrue(application.getBean(EmbeddingProvider.class) instanceof RetryingEmbeddingProvider);
        });
    }

    @Test
    void retriesARealServerFailureThroughTheConfiguredProvider() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            int attempt = calls.incrementAndGet();
            respond(exchange, attempt == 1 ? 503 : 200,
                attempt == 1 ? "temporary" : embeddingResponse());
        });
        server.start();

        try {
            context.withPropertyValues(
                "chatbotq.embedding.openai.enabled=true",
                "chatbotq.embedding.openai.api-key=test-fixture-key",
                "chatbotq.embedding.openai.endpoint=http://localhost:" + server.getAddress().getPort() + "/v1/embeddings",
                "chatbotq.embedding.openai.retry.max-attempts=2",
                "chatbotq.embedding.openai.retry.initial-backoff-ms=1",
                "chatbotq.embedding.openai.retry.max-backoff-ms=1"
            ).run(application -> {
                try {
                    assertArrayEquals(vector(), application.getBean(EmbeddingProvider.class).embed("hours"));
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
            });
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesToInstantiateAProviderWithInvalidRetryLimits() {
        context.withPropertyValues(
            "chatbotq.embedding.openai.enabled=true",
            "chatbotq.embedding.openai.api-key=test-fixture-key",
            "chatbotq.embedding.openai.retry.max-attempts=0"
        ).run(application -> assertNotNull(application.getStartupFailure()));
        context.withPropertyValues(
            "chatbotq.embedding.openai.enabled=true",
            "chatbotq.embedding.openai.api-key=test-fixture-key",
            "chatbotq.embedding.openai.retry.initial-backoff-ms=0"
        ).run(application -> assertNotNull(application.getStartupFailure()));
        context.withPropertyValues(
            "chatbotq.embedding.openai.enabled=true",
            "chatbotq.embedding.openai.api-key=test-fixture-key",
            "chatbotq.embedding.openai.retry.initial-backoff-ms=2",
            "chatbotq.embedding.openai.retry.max-backoff-ms=1"
        ).run(application -> assertNotNull(application.getStartupFailure()));
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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static float[] vector() {
        float[] values = new float[1536];
        values[0] = 0.1f;
        return values;
    }

    private static String embeddingResponse() {
        StringBuilder response = new StringBuilder("{\"data\":[{\"embedding\":[0.1");
        for (int index = 1; index < 1536; index++) {
            response.append(",0.0");
        }
        return response.append("]}]}" ).toString();
    }
}
