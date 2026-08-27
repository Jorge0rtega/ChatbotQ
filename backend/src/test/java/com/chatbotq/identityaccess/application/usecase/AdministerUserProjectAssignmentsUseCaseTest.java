package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.port.UserProjectAssignmentRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdministerUserProjectAssignmentsUseCaseTest {
    @Test
    void listsAndAtomicallyReplacesSortedAssignments() {
        UUID actor = UUID.randomUUID(); UUID target = UUID.randomUUID();
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        RecordingPort port = new RecordingPort(Arrays.asList(high, low));
        RecordingTransaction transaction = new RecordingTransaction();
        Instant now = Instant.parse("2026-08-26T12:00:00Z");
        AdministerUserProjectAssignmentsUseCase useCase = new AdministerUserProjectAssignmentsUseCase(
            port, transaction, Clock.fixed(now, ZoneOffset.UTC));

        assertEquals(Arrays.asList(low, high), useCase.list(actor, target));
        assertEquals(Arrays.asList(low, high), useCase.replace(actor, target, Arrays.asList(high, low)));
        assertTrue(transaction.used);
        assertEquals(Arrays.asList(low, high), port.replaced);
        assertEquals(now, port.at);
    }

    @Test
    void rejectsNullDuplicateAndOverLimitBeforeOpeningTransaction() {
        RecordingPort port = new RecordingPort(Collections.<UUID>emptyList());
        RecordingTransaction transaction = new RecordingTransaction();
        AdministerUserProjectAssignmentsUseCase useCase = new AdministerUserProjectAssignmentsUseCase(
            port, transaction, Clock.systemUTC());
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> useCase.replace(id, id, null));
        assertThrows(IllegalArgumentException.class, () -> useCase.replace(id, id, Arrays.asList(id, id)));
        assertThrows(IllegalArgumentException.class, () -> useCase.replace(id, id, Arrays.asList(id, null)));
        assertThrows(IllegalArgumentException.class, () -> useCase.replace(id, id,
            Collections.nCopies(AdministerUserProjectAssignmentsUseCase.MAX_PROJECT_IDS + 1, UUID.randomUUID())));
        assertTrue(!transaction.used);
    }

    private static final class RecordingPort implements UserProjectAssignmentRepository {
        private final List<UUID> listed; private List<UUID> replaced; private Instant at;
        RecordingPort(List<UUID> listed) { this.listed = listed; }
        @Override public List<UUID> listAsGeneralAdmin(UUID actorId, UUID userId) { return listed; }
        @Override public List<UUID> replaceAsGeneralAdmin(UUID actorId, UUID userId, List<UUID> ids, Instant now) {
            replaced = ids; at = now; return ids;
        }
        @Override public List<UUID> listActiveForCurrentUser(UUID userId) { return listed; }
    }
    private static final class RecordingTransaction implements ApplicationTransaction {
        boolean used;
        @Override public <T> T execute(java.util.function.Supplier<T> work) { used = true; return work.get(); }
    }
}
