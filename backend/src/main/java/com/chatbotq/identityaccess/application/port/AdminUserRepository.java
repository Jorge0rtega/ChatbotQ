package com.chatbotq.identityaccess.application.port;

import com.chatbotq.identityaccess.domain.AdminUser;

import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository {
    boolean existsByEmail(String normalizedEmail);

    Optional<AdminUser> findById(UUID id);

    AdminUser save(AdminUser user);
}
