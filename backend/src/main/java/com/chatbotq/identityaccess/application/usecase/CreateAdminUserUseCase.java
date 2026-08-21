package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.AdminUserIdentityGenerator;
import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.PasswordHasher;
import com.chatbotq.identityaccess.domain.AdminUser;

import java.time.Clock;
import java.util.Locale;

public final class CreateAdminUserUseCase {
    private final AdminUserRepository users;
    private final PasswordHasher passwordHasher;
    private final AdminUserIdentityGenerator identities;
    private final Clock clock;

    public CreateAdminUserUseCase(AdminUserRepository users,
                                  PasswordHasher passwordHasher,
                                  AdminUserIdentityGenerator identities,
                                  Clock clock) {
        this.users = require(users, "users");
        this.passwordHasher = require(passwordHasher, "passwordHasher");
        this.identities = require(identities, "identities");
        this.clock = require(clock, "clock");
    }

    public AdminUser execute(String email, String temporaryPassword, boolean generalAdmin) {
        String normalizedEmail = normalizeForLookup(email);
        if (users.existsByEmail(normalizedEmail)) {
            throw new IllegalStateException("admin user email already exists");
        }
        String passwordHash = passwordHasher.hash(temporaryPassword);
        AdminUser user = AdminUser.create(
            identities.newUserId(), normalizedEmail, passwordHash, generalAdmin, clock.instant());
        return users.save(user);
    }

    private static String normalizeForLookup(String value) {
        if (value == null) {
            throw new IllegalArgumentException("email must not be null");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
