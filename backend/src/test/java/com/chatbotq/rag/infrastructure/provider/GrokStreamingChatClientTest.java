package com.chatbotq.rag.infrastructure.provider;

import com.chatbotq.rag.application.port.ChatCompletionProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrokStreamingChatClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void consumesSseDeltasAndCompletes() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(StreamUtils.copyToString(exchange.getRequestBody(), StandardCharsets.UTF_8));
            String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hola\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\" colega\"}}]}\n\n"
                + "data: [DONE]\n\n";
            respondSse(exchange, sse);
        });
        server.start();

        GrokStreamingChatClient client = client(1000);
        List<String> deltas = new ArrayList<>();

        ChatCompletionProvider.StreamResult result = client.stream(
            "Responde solo con evidencia", "Saluda", delta -> {
                deltas.add(delta);
                return true;
            });

        assertEquals(ChatCompletionProvider.StreamResult.COMPLETED, result);
        assertEquals("Hola colega", String.join("", deltas));
        assertTrue(body.get().contains("\"stream\":true"));
        assertTrue(body.get().contains("\"model\":\"grok-4.3\""));
    }

    @Test
    void assemblesMultilineSseDataEvent() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> respondSse(exchange,
            "data: {\"choices\":[{\"delta\":\n"
                + "data: {\"content\":\"Hola\"}}]}\n\n"
                + "data: [DONE]\n\n"));
        server.start();

        List<String> deltas = new ArrayList<>();
        ChatCompletionProvider.StreamResult result = client(1000).stream(
            "sistema", "usuario", delta -> {
                deltas.add(delta);
                return true;
            });

        assertEquals(ChatCompletionProvider.StreamResult.COMPLETED, result);
        assertEquals("Hola", String.join("", deltas));
    }

    @Test
    void reportsPrematureEofAsErrorInsteadOfCompleted() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> respondSse(exchange,
            "data: {\"choices\":[{\"delta\":{\"content\":\"parcial\"}}]}\n\n"));
        server.start();

        GrokStreamingChatClient client = client(1000);

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
            () -> client.stream("sistema", "usuario", delta -> true));
    }

    @Test
    void cancelsStreamWhenConsumerStops() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"primero\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"segundo\"}}]}\n\n";
            respondSse(exchange, sse);
        });
        server.start();

        GrokStreamingChatClient client = client(1000);
        List<String> deltas = new ArrayList<>();

        ChatCompletionProvider.StreamResult result = client.stream("sistema", "usuario", delta -> {
            deltas.add(delta);
            return false;
        });

        assertEquals(ChatCompletionProvider.StreamResult.CANCELLED, result);
        assertEquals(1, deltas.size());
    }

    private GrokStreamingChatClient client(int readTimeoutMs) throws IOException {
        return new GrokStreamingChatClient(
            "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions",
            "test-key", "grok-4.3", 1000, readTimeoutMs);
    }

    private static void respondSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
