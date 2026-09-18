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

class ProcessOneUpsertKnowledgeImportRowUseCaseTest {
    @Test
    void rejectsNonUpsertExecutionBeforeMutation() {
        RecordingMutations mutations = new RecordingMutations();
        UUID job = UUID.randomUUID();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> useCase(mutations).process(
            new ClaimedKnowledgeImportExecution(job, UUID.randomUUID(), "CREATE_ONLY", UUID.randomUUID()), row(job)));

        assertEquals("execution strategy must be UPSERT", failure.getMessage());
        assertEquals(0, mutations.calls);
    }

    @Test
    void normalizesAndDelegatesClaimedUpsertRow() {
        RecordingMutations mutations = new RecordingMutations();
        UUID job = UUID.randomUUID();

        assertEquals(ProcessOneUpsertKnowledgeImportRowUseCase.Result.IMPORTED, useCase(mutations).process(
            new ClaimedKnowledgeImportExecution(job, UUID.randomUUID(), "UPSERT", UUID.randomUUID()),
            new ClaimedKnowledgeImportRow(job, 2, "  Question  ", " Answer ", " external ", false)));
        assertEquals("Question", mutations.entry.getQuestion());
        assertEquals("Answer", mutations.entry.getAnswer());
        assertEquals("external", mutations.entry.getExternalId());
        assertEquals(false, mutations.entry.isActive());
    }

    private static ProcessOneUpsertKnowledgeImportRowUseCase useCase(KnowledgeImportRowMutationPort mutations) {
        return new ProcessOneUpsertKnowledgeImportRowUseCase(mutations, new KnowledgeEntryIdentityGenerator() {
            @Override public UUID newKnowledgeEntryId() { return UUID.randomUUID(); }
        }, Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC), 4000);
    }

    private static ClaimedKnowledgeImportRow row(UUID job) {
        return new ClaimedKnowledgeImportRow(job, 2, "Question", "Answer", "external", true);
    }

    private static final class RecordingMutations implements KnowledgeImportRowMutationPort {
        private int calls;
        private NewKnowledgeEntry entry;
        @Override public Result createOnly(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row, NewKnowledgeEntry entry) {
            throw new AssertionError("unexpected create-only mutation");
        }
        @Override public Result upsert(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row, NewKnowledgeEntry entry) {
            calls++;
            this.entry = entry;
            return Result.IMPORTED;
        }
    }
}
