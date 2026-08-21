package com.chatbotq.projects.domain;

import java.time.Instant;
import java.util.UUID;

public final class Project {
    private static final int MAX_NAME_LENGTH = 160;

    private final UUID id;
    private final String name;
    private UUID siteKey;
    private boolean active;
    private final int conversationInactivitySeconds;
    private final int retentionDays;
    private final int historyMessageLimit;
    private final int historyTokenLimit;
    private final int responseTokenLimit;
    private final double similarityThreshold;
    private final int retrievalTopK;
    private final boolean handoffEnabled;
    private final int handoffAfterQuestions;
    private final Instant createdAt;
    private Instant updatedAt;

    private Project(UUID id, String name, UUID siteKey, Instant now) {
        this.id = require(id, "id");
        this.name = requireName(name);
        this.siteKey = require(siteKey, "siteKey");
        this.active = true;
        this.conversationInactivitySeconds = 600;
        this.retentionDays = 90;
        this.historyMessageLimit = 6;
        this.historyTokenLimit = 4000;
        this.responseTokenLimit = 600;
        this.similarityThreshold = 0.700d;
        this.retrievalTopK = 5;
        this.handoffEnabled = false;
        this.handoffAfterQuestions = 3;
        this.createdAt = require(now, "now");
        this.updatedAt = now;
    }

    public static Project create(UUID id, String name, UUID siteKey, Instant now) {
        return new Project(id, name, siteKey, now);
    }

    public void rotateSiteKey(UUID replacement, Instant changedAt) {
        this.siteKey = require(replacement, "replacement");
        this.updatedAt = require(changedAt, "changedAt");
    }

    public void disable(Instant changedAt) {
        this.active = false;
        this.updatedAt = require(changedAt, "changedAt");
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public UUID getSiteKey() {
        return siteKey;
    }

    public boolean isActive() {
        return active;
    }

    public int getConversationInactivitySeconds() {
        return conversationInactivitySeconds;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public int getHistoryMessageLimit() {
        return historyMessageLimit;
    }

    public int getHistoryTokenLimit() {
        return historyTokenLimit;
    }

    public int getResponseTokenLimit() {
        return responseTokenLimit;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public int getRetrievalTopK() {
        return retrievalTopK;
    }

    public boolean isHandoffEnabled() {
        return handoffEnabled;
    }

    public int getHandoffAfterQuestions() {
        return handoffAfterQuestions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String requireName(String value) {
        if (value == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must contain between 1 and 160 characters");
        }
        return normalized;
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
