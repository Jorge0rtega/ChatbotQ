package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.EmbeddingInputTokenUpperBound;
import com.chatbotq.knowledge.application.model.KnowledgeTextNormalizer;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntryPage;
import com.chatbotq.knowledge.application.model.NewKnowledgeEntry;
import com.chatbotq.knowledge.application.port.KnowledgeAdministrationPort;
import com.chatbotq.knowledge.application.port.KnowledgeEntryIdentityGenerator;

import java.time.Clock;
import java.util.UUID;

public final class AdministerKnowledgeUseCase {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final long MAX_OFFSET = 1_000_000L;
    private final KnowledgeAdministrationPort entries;
    private final Clock clock;
    private final KnowledgeEntryCreationService creation;

    public AdministerKnowledgeUseCase(KnowledgeAdministrationPort entries, KnowledgeEntryIdentityGenerator identities, Clock clock) {
        this(entries, identities, clock, 4000);
    }
    public AdministerKnowledgeUseCase(KnowledgeAdministrationPort entries, KnowledgeEntryIdentityGenerator identities,
                                      Clock clock, int maxEmbeddingInputTokensPerEntry) {
        if (maxEmbeddingInputTokensPerEntry < 1) throw new IllegalArgumentException("maxEmbeddingInputTokensPerEntry must be positive");
        this.entries = require(entries, "entries"); this.clock = require(clock, "clock");
        this.creation = new KnowledgeEntryCreationService(require(identities, "identities"), maxEmbeddingInputTokensPerEntry);
    }
    public ManagedKnowledgeEntry create(UUID actorId, UUID projectId, String question, String answer, String externalId, boolean active) {
        NewKnowledgeEntry entry = creation.prepare(question, answer, externalId, active);
        return entries.create(require(actorId, "actorId"), require(projectId, "projectId"), entry.getId(), entry.getQuestion(),
            entry.getAnswer(), entry.getExternalId(), entry.isActive(), entry.getEmbeddingInputTokenUpperBound(), clock.instant());
    }
    public ManagedKnowledgeEntry get(UUID actorId, UUID projectId, UUID entryId) {
        return entries.get(require(actorId, "actorId"), require(projectId, "projectId"), require(entryId, "entryId"));
    }
    public ManagedKnowledgeEntry update(UUID actorId, UUID projectId, UUID entryId, String question, String answer, String externalId, boolean active, long version) {
        if (version < 0) throw new IllegalArgumentException("version must be non-negative");
        UUID requiredActorId = require(actorId, "actorId"); UUID requiredProjectId = require(projectId, "projectId"); UUID requiredEntryId = require(entryId, "entryId");
        String normalizedQuestion = KnowledgeTextNormalizer.normalize(question, "question", 2000, false);
        ManagedKnowledgeEntry current = entries.get(requiredActorId, requiredProjectId, requiredEntryId);
        int tokenUpperBound = normalizedQuestion.equals(current.getQuestion()) ? 0 : creation.tokenUpperBound(normalizedQuestion);
        return entries.update(requiredActorId, requiredProjectId, requiredEntryId, normalizedQuestion,
            KnowledgeTextNormalizer.normalize(answer, "answer", 8000, false), KnowledgeTextNormalizer.normalize(externalId, "externalId", 255, true), active, version, tokenUpperBound, clock.instant());
    }
    public ManagedKnowledgeEntry retryEmbedding(UUID actorId, UUID projectId, UUID entryId, long version) {
        if (version < 0) throw new IllegalArgumentException("version must be non-negative");
        return entries.retryEmbedding(require(actorId, "actorId"), require(projectId, "projectId"), require(entryId, "entryId"), version, clock.instant());
    }
    public ManagedKnowledgeEntryPage list(UUID actorId, UUID projectId, String query, int page, int size) {
        require(actorId, "actorId"); require(projectId, "projectId");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        final long offset;
        try { offset = Math.multiplyExact((long) page, (long) size); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("requested page offset is too large", overflow); }
        if (offset > MAX_OFFSET) throw new IllegalArgumentException("requested page offset is too large");
        return entries.list(actorId, projectId, escapeLike(KnowledgeTextNormalizer.normalize(query, "q", 200, true)), page, size, offset);
    }
    private static String escapeLike(String value) { return value == null ? null : value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
    private static <T> T require(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " must not be null"); return value; }
}
