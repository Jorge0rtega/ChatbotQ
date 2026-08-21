package com.chatbotq.rag.infrastructure.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiEmbeddingClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsJava8CompatibleEmbeddingRequestAndParsesVector() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(org.springframework.util.StreamUtils.copyToString(
                exchange.getRequestBody(), StandardCharsets.UTF_8));
            respond(exchange, 200, embeddingResponse(1536));
        });
        server.start();

        OpenAiEmbeddingClient client = client(1000);
        float[] embedding = client.embed("¿Cuál es el horario?");

        assertEquals(1536, embedding.length);
        assertEquals(0.1f, embedding[0]);
        assertEquals("Bearer test-key", authorization.get());
        assertTrue(requestBody.get().contains("\"model\":\"text-embedding-3-small\""));
        assertTrue(requestBody.get().contains("¿Cuál es el horario?"));
    }

    @Test
    void rejectsEmbeddingWithUnexpectedDimensions() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> respond(exchange, 200, embeddingResponse(3)));
        server.start();

        assertThrows(IOException.class, () -> client(1000).embed("dimensión inválida"));
    }

    @Test
    void rejectsNonNumericEmbeddingValues() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> respond(exchange, 200,
            embeddingResponse(1536).replaceFirst("0.1", "\\\"no-numérico\\\"")));
        server.start();

        assertThrows(IOException.class, () -> client(1000).embed("valor inválido"));
    }

    @Test
    void disconnectsWhenWritingRequestFails() throws Exception {
        AtomicBoolean disconnected = new AtomicBoolean();
        URL endpoint = new URL(null, "test://embeddings", new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
                    @Override
                    public OutputStream getOutputStream() throws IOException {
                        throw new IOException("fallo de escritura");
                    }

                    @Override
                    public void disconnect() {
                        disconnected.set(true);
                    }

                    @Override
                    public boolean usingProxy() {
                        return false;
                    }

                    @Override
                    public void connect() {
                    }
                };
            }
        });
        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
            endpoint, "test-key", "text-embedding-3-small", 1000, 1000);

        assertThrows(IOException.class, () -> client.embed("falla"));
        assertTrue(disconnected.get());
    }

    @Test
    void enforcesReadTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                Thread.sleep(300);
                respond(exchange, 200, embeddingResponse(1536));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        assertThrows(SocketTimeoutException.class, () -> client(50).embed("timeout"));
    }

    private OpenAiEmbeddingClient client(int readTimeoutMs) throws IOException {
        return new OpenAiEmbeddingClient(
            "http://localhost:" + server.getAddress().getPort() + "/v1/embeddings",
            "test-key", "text-embedding-3-small", 1000, readTimeoutMs);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String embeddingResponse(int dimensions) {
        StringBuilder builder = new StringBuilder("{\"data\":[{\"embedding\":[");
        for (int index = 0; index < dimensions; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(index == 0 ? "0.1" : "0.0");
        }
        return builder.append("]}]}").toString();
    }
}
