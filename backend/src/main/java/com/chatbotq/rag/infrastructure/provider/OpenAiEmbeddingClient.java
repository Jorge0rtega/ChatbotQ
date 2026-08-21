package com.chatbotq.rag.infrastructure.provider;

import com.chatbotq.rag.application.port.EmbeddingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OpenAiEmbeddingClient implements EmbeddingProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EXPECTED_DIMENSIONS = 1536;

    private final URL endpoint;
    private final String apiKey;
    private final String model;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public OpenAiEmbeddingClient(String endpoint, String apiKey, String model,
                                 int connectTimeoutMs, int readTimeoutMs) throws IOException {
        this(new URL(endpoint), apiKey, model, connectTimeoutMs, readTimeoutMs);
    }

    OpenAiEmbeddingClient(URL endpoint, String apiKey, String model,
                          int connectTimeoutMs, int readTimeoutMs) {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        this.endpoint = endpoint;
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.connectTimeoutMs = requirePositive(connectTimeoutMs, "connectTimeoutMs");
        this.readTimeoutMs = requirePositive(readTimeoutMs, "readTimeoutMs");
    }

    @Override
    public float[] embed(String input) throws IOException {
        requireText(input, "input");
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("input", input);
            payload.put("encoding_format", "float");
            byte[] request = JSON.writeValueAsBytes(payload);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("OpenAI embeddings returned HTTP " + status + ": "
                    + readBody(connection.getErrorStream()));
            }

            try (InputStream response = connection.getInputStream()) {
                JsonNode embedding = JSON.readTree(response).path("data").path(0).path("embedding");
                if (!embedding.isArray() || embedding.size() != EXPECTED_DIMENSIONS) {
                    throw new IOException("OpenAI embeddings response must contain exactly "
                        + EXPECTED_DIMENSIONS + " dimensions");
                }
                float[] values = new float[embedding.size()];
                for (int index = 0; index < embedding.size(); index++) {
                    JsonNode value = embedding.get(index);
                    if (!value.isNumber()) {
                        throw new IOException("OpenAI embeddings response contains a non-numeric value");
                    }
                    double numeric = value.asDouble();
                    if (Double.isNaN(numeric) || Double.isInfinite(numeric)
                        || numeric > Float.MAX_VALUE || numeric < -Float.MAX_VALUE) {
                        throw new IOException("OpenAI embeddings response contains a non-finite value");
                    }
                    values[index] = (float) numeric;
                }
                return values;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream) {
            return new String(org.springframework.util.StreamUtils.copyToByteArray(input), StandardCharsets.UTF_8);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
