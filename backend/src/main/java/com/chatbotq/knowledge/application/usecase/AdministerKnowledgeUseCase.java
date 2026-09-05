package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntryPage;
import com.chatbotq.knowledge.application.port.KnowledgeAdministrationPort;
import com.chatbotq.knowledge.application.port.KnowledgeEntryIdentityGenerator;

import java.time.Clock;
import java.util.UUID;

public final class AdministerKnowledgeUseCase {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final long MAX_OFFSET = 1_000_000L;
    private final KnowledgeAdministrationPort entries;
    private final KnowledgeEntryIdentityGenerator identities;
    private final Clock clock;

    public AdministerKnowledgeUseCase(KnowledgeAdministrationPort entries,
                                      KnowledgeEntryIdentityGenerator identities, Clock clock) {
        this.entries = require(entries, "entries");
        this.identities = require(identities, "identities");
        this.clock = require(clock, "clock");
    }

    public ManagedKnowledgeEntry create(UUID actorId, UUID projectId, String question, String answer,
                                        String externalId, boolean active) {
        return entries.create(require(actorId, "actorId"), require(projectId, "projectId"),
            identities.newKnowledgeEntryId(), normalize(question, "question", 2000, false),
            normalize(answer, "answer", 8000, false), normalize(externalId, "externalId", 255, true), active,
            clock.instant());
    }

    public ManagedKnowledgeEntry get(UUID actorId, UUID projectId, UUID entryId) {
        return entries.get(require(actorId, "actorId"), require(projectId, "projectId"),
            require(entryId, "entryId"));
    }

    public ManagedKnowledgeEntry update(UUID actorId, UUID projectId, UUID entryId, String question, String answer,
                                        String externalId, boolean active, long version) {
        if (version < 0) throw new IllegalArgumentException("version must be non-negative");
        return entries.update(require(actorId, "actorId"), require(projectId, "projectId"), require(entryId, "entryId"),
            normalize(question, "question", 2000, false), normalize(answer, "answer", 8000, false),
            normalize(externalId, "externalId", 255, true), active, version, clock.instant());
    }

    public ManagedKnowledgeEntry retryEmbedding(UUID actorId, UUID projectId, UUID entryId, long version) {
        if (version < 0) throw new IllegalArgumentException("version must be non-negative");
        return entries.retryEmbedding(require(actorId, "actorId"), require(projectId, "projectId"),
            require(entryId, "entryId"), version, clock.instant());
    }

    public ManagedKnowledgeEntryPage list(UUID actorId, UUID projectId, String query, int page, int size) {
        require(actorId, "actorId");
        require(projectId, "projectId");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
        final long offset;
        try {
            offset = Math.multiplyExact((long) page, (long) size);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("requested page offset is too large", overflow);
        }
        if (offset > MAX_OFFSET) throw new IllegalArgumentException("requested page offset is too large");
        String normalizedQuery = normalize(query, "q", 200, true);
        return entries.list(actorId, projectId, escapeLike(normalizedQuery), page, size, offset);
    }

    private static String escapeLike(String value) {
        if (value == null) return null;
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String normalize(String value, String name, int maximum, boolean optional) {
        if (value == null) {
            if (optional) return null;
            throw new IllegalArgumentException(name + " must not be null");
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        String normalized = value.substring(start, end);
        for (int index = 0; index < normalized.length();) {
            int codePoint = normalized.codePointAt(index);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException(name + " must not contain control characters");
            }
            index += Character.charCount(codePoint);
        }
        int characters = normalized.codePointCount(0, normalized.length());
        if (characters == 0 || characters > maximum) {
            throw new IllegalArgumentException(name + " must contain between 1 and " + maximum + " characters");
        }
        return normalized;
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
