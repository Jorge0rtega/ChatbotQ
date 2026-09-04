package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;
import com.chatbotq.knowledge.application.port.KnowledgeAdministrationPort;
import com.chatbotq.knowledge.application.port.KnowledgeEntryIdentityGenerator;

import java.time.Clock;
import java.util.UUID;

public final class AdministerKnowledgeUseCase {
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
