package com.chatbotq.projects.application.model;

import java.time.Instant;
import java.util.UUID;

public final class ManagedSiteKey {
    private final UUID siteKey;
    private final long version;
    private final Instant rotatedAt;

    public ManagedSiteKey(UUID siteKey, long version, Instant rotatedAt) {
        if (siteKey == null) throw new IllegalArgumentException("siteKey must not be null");
        if (version < 1L) throw new IllegalArgumentException("version must be positive");
        if (rotatedAt == null) throw new IllegalArgumentException("rotatedAt must not be null");
        this.siteKey = siteKey;
        this.version = version;
        this.rotatedAt = rotatedAt;
    }

    public UUID getSiteKey() { return siteKey; }
    public long getVersion() { return version; }
    public Instant getRotatedAt() { return rotatedAt; }
}
