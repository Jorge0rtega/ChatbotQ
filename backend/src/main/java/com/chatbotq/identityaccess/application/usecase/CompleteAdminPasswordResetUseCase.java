package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.port.PasswordHasher;
import com.chatbotq.identityaccess.application.port.PasswordVerifier;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.domain.AdminUserStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/** Completes the only operation allowed to a PASSWORD_RESET_REQUIRED account. */
public final class CompleteAdminPasswordResetUseCase {
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private final AdminUserRepository users;
    private final PasswordVerifier verifier;
    private final PasswordHasher hasher;
    private final String dummyPasswordHash;
    private final RefreshSessionRepository sessions;
    private final ApplicationTransaction transactions;
    private final Clock clock;

    public CompleteAdminPasswordResetUseCase(AdminUserRepository users, PasswordVerifier verifier,
                                              PasswordHasher hasher, RefreshSessionRepository sessions,
                                              ApplicationTransaction transactions, Clock clock) {
        this(users, verifier, hasher, "invalid-dummy-hash", sessions, transactions, clock);
    }

    public CompleteAdminPasswordResetUseCase(AdminUserRepository users, PasswordVerifier verifier,
                                              PasswordHasher hasher, String dummyPasswordHash,
                                              RefreshSessionRepository sessions,
                                              ApplicationTransaction transactions, Clock clock) {
        this.users = require(users, "users");
        this.verifier = require(verifier, "verifier");
        this.hasher = require(hasher, "hasher");
        this.dummyPasswordHash = require(dummyPasswordHash, "dummyPasswordHash");
        this.sessions = require(sessions, "sessions");
        this.transactions = require(transactions, "transactions");
        this.clock = require(clock, "clock");
    }

    public void execute(String email, String temporaryPassword, String newPassword) {
        final String normalizedEmail = normalizeEmail(email);
        final String validatedNewPassword = validatePassword(newPassword);
        if (validatedNewPassword.equals(temporaryPassword)) {
            throw new IllegalArgumentException("new password must differ from temporary password");
        }
        transactions.execute(() -> {
            AdminUser user = users.findByEmailForUpdate(normalizedEmail).orElse(null);
            String currentHash = user == null ? dummyPasswordHash : user.getPasswordHash();
            boolean temporaryMatches = verifier.matches(temporaryPassword, currentHash);
            if (user == null || user.getStatus() != AdminUserStatus.PASSWORD_RESET_REQUIRED
                    || !temporaryMatches) {
                throw InvalidAuthenticationException.credentials();
            }
            String newHash = hasher.hash(validatedNewPassword);
            Instant now = clock.instant();
            users.completePasswordReset(user.getId(), newHash, now);
            sessions.revokeAllByUserOrdered(user.getId(), now);
            return null;
        });
    }

    private static String normalizeEmail(String value) {
        if (value == null) throw new IllegalArgumentException("email is required");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int at = normalized.indexOf('@');
        if (normalized.length() < 3 || normalized.length() > 320 || at < 1
                || at != normalized.lastIndexOf('@') || at == normalized.length() - 1) {
            throw new IllegalArgumentException("email is invalid");
        }
        return normalized;
    }

    private static String validatePassword(String value) {
        if (value == null || value.length() < MIN_PASSWORD_LENGTH || value.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("new password length is invalid");
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) throw new IllegalArgumentException("new password is invalid");
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        if (!letter || !digit) throw new IllegalArgumentException("new password is invalid");
        return value;
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
