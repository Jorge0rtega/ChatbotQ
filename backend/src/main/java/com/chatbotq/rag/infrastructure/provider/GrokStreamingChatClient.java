package com.chatbotq.rag.infrastructure.provider;

import com.chatbotq.rag.application.port.ChatCompletionProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GrokStreamingChatClient implements ChatCompletionProvider {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URL endpoint;
    private final String apiKey;
    private final String model;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public GrokStreamingChatClient(String endpoint, String apiKey, String model,
                                   int connectTimeoutMs, int readTimeoutMs) throws IOException {
        this.endpoint = new URL(endpoint);
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.connectTimeoutMs = requirePositive(connectTimeoutMs, "connectTimeoutMs");
        this.readTimeoutMs = requirePositive(readTimeoutMs, "readTimeoutMs");
    }

    @Override
    public StreamResult stream(String systemPrompt, String userPrompt, DeltaHandler handler) throws IOException {
        requireText(systemPrompt, "systemPrompt");
        requireText(userPrompt, "userPrompt");
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }

        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "text/event-stream");

        try {
            byte[] request = JSON.writeValueAsBytes(payload(systemPrompt, userPrompt));
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Grok streaming returned HTTP " + status + ": "
                    + readBody(connection.getErrorStream()));
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder eventData = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String value = line.substring("data:".length());
                        if (value.startsWith(" ")) {
                            value = value.substring(1);
                        }
                        if (eventData.length() > 0) {
                            eventData.append('\n');
                        }
                        eventData.append(value);
                        continue;
                    }
                    if (!line.isEmpty() || eventData.length() == 0) {
                        continue;
                    }

                    String data = eventData.toString();
                    eventData.setLength(0);
                    if ("[DONE]".equals(data)) {
                        return StreamResult.COMPLETED;
                    }
                    String delta = extractDelta(data);
                    if (!delta.isEmpty() && !handler.onDelta(delta)) {
                        return StreamResult.CANCELLED;
                    }
                }
            }
            throw new IOException("Grok streaming ended before the [DONE] marker");
        } finally {
            connection.disconnect();
        }
    }

    private Map<String, Object> payload(String systemPrompt, String userPrompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", true);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        payload.put("messages", messages);
        return payload;
    }

    private static Map<String, String> message(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static String extractDelta(String data) throws IOException {
        JsonNode content = JSON.readTree(data).path("choices").path(0).path("delta").path("content");
        return content.isTextual() ? content.asText() : "";
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
