package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.ProjectAdministrationDecisionPort;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanAdministerProjectUseCaseTest {
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void delegatesTheWholeDecisionToOnePortCall() {
        RecordingDecisionPort decision = new RecordingDecisionPort(true);
        CanAdministerProjectUseCase useCase = new CanAdministerProjectUseCase(decision);

        assertTrue(useCase.isAllowed(USER_ID, PROJECT_ID));
        assertEquals(1, decision.calls);
        assertEquals(USER_ID, decision.userId);
        assertEquals(PROJECT_ID, decision.projectId);
    }

    @Test
    void returnsTheSingleDecisionPortsDenial() {
        RecordingDecisionPort decision = new RecordingDecisionPort(false);

        assertFalse(new CanAdministerProjectUseCase(decision).isAllowed(USER_ID, PROJECT_ID));
        assertEquals(1, decision.calls);
    }

    @Test
    void failsClosedForNullIdentifiersWithoutCallingPersistence() {
        RecordingDecisionPort decision = new RecordingDecisionPort(true);
        CanAdministerProjectUseCase useCase = new CanAdministerProjectUseCase(decision);

        assertFalse(useCase.isAllowed(null, PROJECT_ID));
        assertFalse(useCase.isAllowed(USER_ID, null));
        assertEquals(0, decision.calls);
    }

    private static final class RecordingDecisionPort implements ProjectAdministrationDecisionPort {
        private final boolean allowed;
        private int calls;
        private UUID userId;
        private UUID projectId;

        private RecordingDecisionPort(boolean allowed) {
            this.allowed = allowed;
        }

        @Override
        public boolean canAdminister(UUID userId, UUID projectId) {
            calls++;
            this.userId = userId;
            this.projectId = projectId;
            return allowed;
        }
    }
}
