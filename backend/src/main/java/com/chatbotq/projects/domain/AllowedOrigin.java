package com.chatbotq.projects.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public final class AllowedOrigin {
    private final UUID id;
    private final UUID projectId;
    private final String origin;
    private final Instant createdAt;
    private boolean active;

    private AllowedOrigin(UUID id, UUID projectId, String origin, Instant createdAt) {
        this.id = require(id, "id");
        this.projectId = require(projectId, "projectId");
        this.origin = canonicalize(origin);
        this.createdAt = require(createdAt, "createdAt");
        this.active = true;
    }

    public static AllowedOrigin create(UUID id, UUID projectId, String origin, Instant createdAt) {
        return new AllowedOrigin(id, projectId, origin, createdAt);
    }

    public boolean matches(String candidate) {
        if (!active) {
            return false;
        }
        try {
            return origin.equals(canonicalize(candidate));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public void disable() {
        this.active = false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getOrigin() {
        return origin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return active;
    }

    private static String canonicalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("origin must not be blank");
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!("http".equals(scheme) || "https".equals(scheme)) || host == null
                || uri.getUserInfo() != null || hasText(uri.getRawPath())
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("origin must contain only scheme, host and optional port");
            }
            int port = uri.getPort();
            if (port < -1 || port == 0 || port > 65535) {
                throw new IllegalArgumentException("origin port is invalid");
            }
            boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
            return scheme + "://" + host.toLowerCase(Locale.ROOT)
                + (defaultPort ? "" : ":" + port);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("origin is invalid", invalid);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
