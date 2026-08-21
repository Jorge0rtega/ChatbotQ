package com.chatbotq.identityaccess.domain;

import java.time.Instant;
import java.util.UUID;

public final class RefreshSession {
    private final UUID id;
    private final UUID userId;
    private final UUID familyId;
    private final String tokenHash;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private Instant rotatedAt;
    private Instant revokedAt;
    private UUID replacedById;

    private RefreshSession(UUID id, UUID userId, UUID familyId, String tokenHash,
                           Instant issuedAt, Instant expiresAt) {
        this.id = require(id, "id");
        this.userId = require(userId, "userId");
        this.familyId = require(familyId, "familyId");
        this.tokenHash = requireText(tokenHash, "tokenHash");
        this.issuedAt = require(issuedAt, "issuedAt");
        this.expiresAt = require(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    public static RefreshSession issue(UUID id, UUID userId, UUID familyId,
                                       String tokenHash, Instant issuedAt,
                                       Instant expiresAt) {
        return new RefreshSession(id, userId, familyId, tokenHash, issuedAt, expiresAt);
    }

    public boolean isUsableAt(Instant instant) {
        require(instant, "instant");
        return rotatedAt == null && revokedAt == null && instant.isBefore(expiresAt);
    }

    public RefreshSession rotate(UUID replacementId, String replacementTokenHash,
                                 Instant rotatedAt, Instant replacementExpiresAt) {
        require(rotatedAt, "rotatedAt");
        if (!isUsableAt(rotatedAt)) {
            throw new IllegalStateException("refresh session is not usable");
        }
        this.rotatedAt = rotatedAt;
        this.replacedById = require(replacementId, "replacementId");
        return new RefreshSession(replacementId, userId, familyId,
            replacementTokenHash, rotatedAt, replacementExpiresAt);
    }

    public void revoke(Instant revokedAt) {
        require(revokedAt, "revokedAt");
        if (this.revokedAt == null) {
            this.revokedAt = revokedAt;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedById() {
        return replacedById;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
