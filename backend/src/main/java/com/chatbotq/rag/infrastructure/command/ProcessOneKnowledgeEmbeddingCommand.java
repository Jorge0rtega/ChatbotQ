package com.chatbotq.rag.infrastructure.command;

import com.chatbotq.knowledge.application.usecase.ProcessOneKnowledgeEmbeddingUseCase;
import com.chatbotq.rag.infrastructure.configuration.EmbeddingProcessingLimits;

/** Explicit one-shot trigger; it is never scheduled or invoked automatically. */
public final class ProcessOneKnowledgeEmbeddingCommand {
    private final ProcessOneKnowledgeEmbeddingUseCase useCase;
    private final EmbeddingProcessingLimits limits;

    public ProcessOneKnowledgeEmbeddingCommand(ProcessOneKnowledgeEmbeddingUseCase useCase,
                                                EmbeddingProcessingLimits limits) {
        if (useCase == null) throw new IllegalArgumentException("useCase must not be null");
        if (limits == null) throw new IllegalArgumentException("limits must not be null");
        if (limits.getMaxEntriesPerManualRun() != 1) {
            throw new IllegalArgumentException("manual processing must be limited to exactly one entry");
        }
        this.useCase = useCase;
        this.limits = limits;
    }

    public ProcessOneKnowledgeEmbeddingUseCase.Result runOnce() {
        return useCase.processOne();
    }
}
