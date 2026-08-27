package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.model.ManagedAdminUser;
import com.chatbotq.identityaccess.application.model.ManagedAdminUserPage;
import com.chatbotq.identityaccess.application.port.AdminUserAdministrationPort;
import com.chatbotq.identityaccess.application.port.AdminUserIdentityGenerator;
import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.port.PasswordHasher;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

public final class AdministerAdminUsersUseCase {
    public static final int MAX_PAGE_SIZE = 100;
    public static final long MAX_OFFSET = 1_000_000L;
    private static final int MIN_TEMPORARY_PASSWORD_LENGTH = 12;
    private static final int MAX_TEMPORARY_PASSWORD_LENGTH = 128;

    private final AdminUserAdministrationPort users;
    private final PasswordHasher passwords;
    private final AdminUserIdentityGenerator identities;
    private final RefreshSessionRepository sessions;
    private final ApplicationTransaction transactions;
    private final Clock clock;

    public AdministerAdminUsersUseCase(AdminUserAdministrationPort users, PasswordHasher passwords,
                                       AdminUserIdentityGenerator identities, RefreshSessionRepository sessions,
                                       ApplicationTransaction transactions, Clock clock) {
        this.users = require(users, "users");
        this.passwords = require(passwords, "passwords");
        this.identities = require(identities, "identities");
        this.sessions = require(sessions, "sessions");
        this.transactions = require(transactions, "transactions");
        this.clock = require(clock, "clock");
    }

    public ManagedAdminUser create(UUID actorId, String email, String temporaryPassword, String role) {
        UUID actor = require(actorId, "actorId");
        boolean general = parseRole(role);
        String normalizedEmail = normalizeEmail(email);
        String validatedPassword = validateTemporaryPassword(temporaryPassword);
        if (!users.canAdminister(actor)) throw new ForbiddenAdminUserAdministrationException();
        String hash = passwords.hash(validatedPassword);
        return users.createAsGeneralAdmin(actor, identities.newUserId(),
            normalizedEmail, hash, general, clock.instant());
    }

    public ManagedAdminUser get(UUID actorId, UUID userId) {
        return users.findByIdAsGeneralAdmin(require(actorId, "actorId"), require(userId, "userId"));
    }

    public ManagedAdminUserPage list(UUID actorId, int page, int size) {
        require(actorId, "actorId");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid page");
        }
        long offset;
        try { offset = Math.multiplyExact((long) page, (long) size); }
        catch (ArithmeticException tooLarge) { throw new IllegalArgumentException("page offset too large", tooLarge); }
        if (offset > MAX_OFFSET) throw new IllegalArgumentException("page offset too large");
        return users.listAsGeneralAdmin(actorId, page, size, offset);
    }

    public ManagedAdminUser updateEmail(UUID actorId, UUID userId, String email) {
        return users.updateEmailAsGeneralAdmin(require(actorId, "actorId"), require(userId, "userId"),
            normalizeEmail(email), clock.instant());
    }

    public void activate(UUID actorId, UUID userId) {
        users.setActiveAsGeneralAdmin(require(actorId, "actorId"), require(userId, "userId"), true, clock.instant());
    }

    public void deactivate(UUID actorId, UUID userId) {
        users.setActiveAsGeneralAdmin(require(actorId, "actorId"), require(userId, "userId"), false, clock.instant());
    }

    public void resetPassword(UUID actorId, UUID userId, String temporaryPassword) {
        UUID actor = require(actorId, "actorId");
        UUID target = require(userId, "userId");
        String validatedPassword = validateTemporaryPassword(temporaryPassword);
        if (!users.canAdminister(actor)) throw new ForbiddenAdminUserAdministrationException();
        String hash = passwords.hash(validatedPassword);
        final java.time.Instant now = clock.instant();
        transactions.execute(() -> {
            users.resetPasswordAsGeneralAdmin(actor, target, hash, now);
            sessions.revokeAllByUserOrdered(target, now);
            return null;
        });
    }

    private static boolean parseRole(String role) {
        if ("GENERAL_ADMIN".equals(role)) return true;
        if ("PROJECT_ADMIN".equals(role)) return false;
        throw new IllegalArgumentException("role must be GENERAL_ADMIN or PROJECT_ADMIN");
    }

    private static String normalizeEmail(String value) {
        if (value == null) throw new IllegalArgumentException("email must not be null");
        String email = value.trim().toLowerCase(Locale.ROOT);
        int at = email.indexOf('@');
        if (email.length() < 3 || email.length() > 320 || at < 1 || at != email.lastIndexOf('@')
                || at > 64 || at == email.length() - 1) {
            throw new IllegalArgumentException("email is invalid");
        }
        for (int i = 0; i < email.length(); i++) {
            char c = email.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalArgumentException("email is invalid");
            }
        }
        return email;
    }

    private static String validateTemporaryPassword(String value) {
        if (value == null || value.length() < MIN_TEMPORARY_PASSWORD_LENGTH
                || value.length() > MAX_TEMPORARY_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("temporary password length is invalid");
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) throw new IllegalArgumentException("temporary password is invalid");
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        if (!letter || !digit) throw new IllegalArgumentException("temporary password is invalid");
        return value;
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
