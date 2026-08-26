package com.chatbotq.identityaccess.infrastructure.security;

import com.chatbotq.identityaccess.application.usecase.CanAdministerProjectUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectAuthorizationTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab");

    @Test
    void rejectsNullBlankWhitespaceShortAndInvalidCanonicalUuidFailClosed() {
        ProjectAuthorization authorization = authorization(true);
        Authentication authentication = authentication();

        assertFalse(authorization.canAdminister(authentication, null));
        assertFalse(authorization.canAdminister(authentication, ""));
        assertFalse(authorization.canAdminister(authentication, "   "));
        assertFalse(authorization.canAdminister(authentication, "1-1-1-1-1"));
        assertFalse(authorization.canAdminister(authentication,
            "g0000000-0000-0000-0000-000000000000"));
    }

    @Test
    void acceptsCanonicalUuidCaseInsensitively() {
        ProjectAuthorization authorization = authorization(true);

        assertTrue(authorization.canAdminister(authentication(), PROJECT_ID.toString().toUpperCase()));
    }

    @Test
    void rejectsMissingUnauthenticatedAndWrongPrincipal() {
        ProjectAuthorization authorization = authorization(true);
        Authentication unauthenticated = new UsernamePasswordAuthenticationToken("user", "password");
        Authentication wrongPrincipal = new UsernamePasswordAuthenticationToken(
            "user", "password", Collections.emptyList());

        assertFalse(authorization.canAdminister(null, PROJECT_ID.toString()));
        assertFalse(authorization.canAdminister(unauthenticated, PROJECT_ID.toString()));
        assertFalse(authorization.canAdminister(wrongPrincipal, PROJECT_ID.toString()));
    }

    private static ProjectAuthorization authorization(boolean allowed) {
        return new ProjectAuthorization(new CanAdministerProjectUseCase(
            (userId, projectId) -> allowed && USER_ID.equals(userId) && PROJECT_ID.equals(projectId)));
    }

    private static Authentication authentication() {
        AdminAccessPrincipal principal = new AdminAccessPrincipal(USER_ID, "admin@example.com", false);
        return new UsernamePasswordAuthenticationToken(principal, "token", Collections.emptyList());
    }
}
