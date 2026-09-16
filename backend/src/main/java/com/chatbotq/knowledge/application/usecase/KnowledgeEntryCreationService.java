package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.EmbeddingInputTokenUpperBound;
import com.chatbotq.knowledge.application.model.KnowledgeTextNormalizer;
import com.chatbotq.knowledge.application.model.NewKnowledgeEntry;
import com.chatbotq.knowledge.application.port.KnowledgeEntryIdentityGenerator;

public final class KnowledgeEntryCreationService {
    private final KnowledgeEntryIdentityGenerator identities;
    private final int maxEmbeddingInputTokensPerEntry;

    public KnowledgeEntryCreationService(KnowledgeEntryIdentityGenerator identities, int maxEmbeddingInputTokensPerEntry) {
        if (identities == null) throw new IllegalArgumentException("identities must not be null");
        if (maxEmbeddingInputTokensPerEntry < 1) throw new IllegalArgumentException("maxEmbeddingInputTokensPerEntry must be positive");
        this.identities = identities;
        this.maxEmbeddingInputTokensPerEntry = maxEmbeddingInputTokensPerEntry;
    }

    public NewKnowledgeEntry prepare(String question, String answer, String externalId, boolean active) {
        String normalizedQuestion = KnowledgeTextNormalizer.normalize(question, "question", 2000, false);
        int tokenUpperBound = tokenUpperBound(normalizedQuestion);
        return new NewKnowledgeEntry(identities.newKnowledgeEntryId(), normalizedQuestion,
            KnowledgeTextNormalizer.normalize(answer, "answer", 8000, false),
            KnowledgeTextNormalizer.normalize(externalId, "externalId", 255, true), active, tokenUpperBound);
    }

    public int tokenUpperBound(String normalizedQuestion) {
        int tokenUpperBound = EmbeddingInputTokenUpperBound.forQuestion(normalizedQuestion);
        if (tokenUpperBound > maxEmbeddingInputTokensPerEntry) {
            throw new IllegalArgumentException("question exceeds embedding input token limit of " + maxEmbeddingInputTokensPerEntry);
        }
        return tokenUpperBound;
    }
}
