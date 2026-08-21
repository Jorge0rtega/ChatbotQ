package com.chatbotq.identityaccess.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public final class AdminUser {
    private static final int MAX_EMAIL_LENGTH = 320;

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final boolean generalAdmin;
    private AdminUserStatus status;
    private int failedLoginCount;
    private Instant lockedUntil;
    private final Instant createdAt;
    private Instant updatedAt;

    private AdminUser(UUID id, String email, String passwordHash,
                      boolean generalAdmin, Instant now) {
        this.id = require(id, "id");
        this.email = normalizeEmail(email);
        this.passwordHash = requireText(passwordHash, "passwordHash");
        this.generalAdmin = generalAdmin;
        this.status = AdminUserStatus.PASSWORD_RESET_REQUIRED;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.createdAt = require(now, "now");
        this.updatedAt = now;
    }

    public static AdminUser create(UUID id, String email, String passwordHash,
                                   boolean generalAdmin, Instant now) {
        return new AdminUser(id, email, passwordHash, generalAdmin, now);
    }

    public static AdminUser restore(UUID id, String email, String passwordHash,
                                    boolean generalAdmin, AdminUserStatus status,
                                    int failedLoginCount, Instant lockedUntil,
                                    Instant createdAt, Instant updatedAt) {
        if (failedLoginCount < 0) {
            throw new IllegalArgumentException("failedLoginCount must not be negative");
        }
        AdminUser user = new AdminUser(id, email, passwordHash, generalAdmin, createdAt);
        user.status = require(status, "status");
        user.failedLoginCount = failedLoginCount;
        user.lockedUntil = lockedUntil;
        user.updatedAt = require(updatedAt, "updatedAt");
        return user;
    }

    public void activate(Instant changedAt) {
        this.status = AdminUserStatus.ACTIVE;
        this.updatedAt = require(changedAt, "changedAt");
    }

    public void disable(Instant changedAt) {
        this.status = AdminUserStatus.DISABLED;
        this.updatedAt = require(changedAt, "changedAt");
    }

    public boolean isLockedAt(Instant instant) {
        require(instant, "instant");
        return lockedUntil != null && lockedUntil.isAfter(instant);
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isGeneralAdmin() {
        return generalAdmin;
    }

    public AdminUserStatus getStatus() {
        return status;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String normalizeEmail(String value) {
        String normalized = requireText(value, "email").toLowerCase(Locale.ROOT);
        int at = normalized.indexOf('@');
        if (normalized.length() > MAX_EMAIL_LENGTH || at <= 0
            || at != normalized.lastIndexOf('@') || at == normalized.length() - 1) {
            throw new IllegalArgumentException("email is invalid");
        }
        return normalized;
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
