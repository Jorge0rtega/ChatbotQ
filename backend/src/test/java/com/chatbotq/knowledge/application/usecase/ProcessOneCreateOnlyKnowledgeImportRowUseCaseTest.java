package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportRow;
import com.chatbotq.knowledge.application.model.NewKnowledgeEntry;
import com.chatbotq.knowledge.application.port.KnowledgeEntryIdentityGenerator;
import com.chatbotq.knowledge.application.port.KnowledgeImportRowMutationPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessOneCreateOnlyKnowledgeImportRowUseCaseTest {
    @Test
    void rejectsNonCreateOnlyExecutionBeforeMutation() {
        RecordingMutations mutations = new RecordingMutations();
        ProcessOneCreateOnlyKnowledgeImportRowUseCase useCase = useCase(mutations);
        UUID job = UUID.randomUUID();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> useCase.process(new ClaimedKnowledgeImportExecution(job, UUID.randomUUID(), "UPSERT", UUID.randomUUID()), row(job)));

        assertEquals("execution strategy must be CREATE_ONLY", failure.getMessage());
        assertEquals(0, mutations.calls);
    }

    @Test
    void rejectsRowFromAnotherJobBeforeMutation() {
        RecordingMutations mutations = new RecordingMutations();
        ProcessOneCreateOnlyKnowledgeImportRowUseCase useCase = useCase(mutations);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> useCase.process(new ClaimedKnowledgeImportExecution(UUID.randomUUID(), UUID.randomUUID(), "CREATE_ONLY", UUID.randomUUID()), row(UUID.randomUUID())));

        assertEquals("row must belong to execution job", failure.getMessage());
        assertEquals(0, mutations.calls);
    }

    @Test
    void translatesRolledBackStaleMutationToStaleResult() {
        KnowledgeImportRowMutationPort staleMutation = new KnowledgeImportRowMutationPort() {
            @Override public Result createOnly(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row, NewKnowledgeEntry entry) {
                throw new StaleKnowledgeImportMutationException();
            }
        };
        UUID job = UUID.randomUUID();

        assertEquals(ProcessOneCreateOnlyKnowledgeImportRowUseCase.Result.STALE,
            useCase(staleMutation).process(new ClaimedKnowledgeImportExecution(job, UUID.randomUUID(), "CREATE_ONLY", UUID.randomUUID()), row(job)));
    }

    private static ProcessOneCreateOnlyKnowledgeImportRowUseCase useCase(RecordingMutations mutations) {
        return useCase((KnowledgeImportRowMutationPort) mutations);
    }

    private static ProcessOneCreateOnlyKnowledgeImportRowUseCase useCase(KnowledgeImportRowMutationPort mutations) {
        return new ProcessOneCreateOnlyKnowledgeImportRowUseCase(mutations, new KnowledgeEntryIdentityGenerator() {
            @Override public UUID newKnowledgeEntryId() { return UUID.randomUUID(); }
        }, Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC), 4000);
    }

    private static ClaimedKnowledgeImportRow row(UUID job) {
        return new ClaimedKnowledgeImportRow(job, 2, "Question", "Answer", "external", true);
    }

    private static final class RecordingMutations implements KnowledgeImportRowMutationPort {
        private int calls;
        @Override public Result createOnly(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row, NewKnowledgeEntry entry) {
            calls++;
            return Result.IMPORTED;
        }
    }
}
