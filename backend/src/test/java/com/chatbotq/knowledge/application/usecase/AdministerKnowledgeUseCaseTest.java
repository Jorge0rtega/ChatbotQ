package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntryPage;
import com.chatbotq.knowledge.application.port.KnowledgeAdministrationPort;
import com.chatbotq.knowledge.application.port.KnowledgeEntryIdentityGenerator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdministerKnowledgeUseCaseTest {
    @Test
    void rejectsQuestionWhoseUtf8UpperBoundExceedsConfiguredLimitBeforePortWrite() {
        RecordingPort entries = new RecordingPort();
        AdministerKnowledgeUseCase useCase = useCase(entries, 4);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> useCase.create(UUID.randomUUID(), UUID.randomUUID(), "🙂🙂", "Answer", null, true));

        assertEquals("question exceeds embedding input token limit of 4", failure.getMessage());
        assertEquals(0, entries.writes);
    }

    @Test
    void passesUtf8UpperBoundOnCreateAndUpdateOnlyWhenQuestionChanges() {
        RecordingPort entries = new RecordingPort();
        AdministerKnowledgeUseCase useCase = useCase(entries, 10);
        UUID actor = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID entry = UUID.randomUUID();

        useCase.create(actor, project, "ñ", "Answer", null, true);
        useCase.update(actor, project, entry, "🙂", "Answer", null, true, 0);

        assertEquals(2, entries.createdBound);
        assertEquals(4, entries.updatedBound);
    }

    private static AdministerKnowledgeUseCase useCase(RecordingPort entries, int maxInputTokensPerEntry) {
        return new AdministerKnowledgeUseCase(entries, new KnowledgeEntryIdentityGenerator() {
            @Override public UUID newKnowledgeEntryId() { return UUID.randomUUID(); }
        }, Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC), maxInputTokensPerEntry);
    }

    private static final class RecordingPort implements KnowledgeAdministrationPort {
        private int writes;
        private int createdBound;
        private int updatedBound;

        @Override public ManagedKnowledgeEntry create(UUID actorId, UUID projectId, UUID entryId, String question,
                                                       String answer, String externalId, boolean active,
                                                       int embeddingInputTokenUpperBound, Instant now) {
            writes++;
            createdBound = embeddingInputTokenUpperBound;
            return null;
        }
        @Override public ManagedKnowledgeEntry get(UUID actorId, UUID projectId, UUID entryId) {
            Instant now = Instant.parse("2026-09-06T00:00:00Z");
            return new ManagedKnowledgeEntry(entryId, projectId, "Previous", "Answer", null, true,
                "PENDING", 1, 0, null, null, null, 0, now, now);
        }
        @Override public ManagedKnowledgeEntry update(UUID actorId, UUID projectId, UUID entryId, String question,
                                                       String answer, String externalId, boolean active, long version,
                                                       int embeddingInputTokenUpperBound, Instant now) {
            writes++;
            updatedBound = embeddingInputTokenUpperBound;
            return null;
        }
        @Override public ManagedKnowledgeEntry retryEmbedding(UUID actorId, UUID projectId, UUID entryId, long version,
                                                               Instant now) { return null; }
        @Override public ManagedKnowledgeEntryPage list(UUID actorId, UUID projectId, String query, int page, int size,
                                                        long offset) { return null; }
    }
}
