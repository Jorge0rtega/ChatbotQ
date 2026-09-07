package com.chatbotq.rag.application.model;

/** Immutable request context for embedding generation. */
public final class EmbeddingRequest {
    private final String input;
    private final EmbeddingAttemptGate attemptGate;

    public EmbeddingRequest(String input, EmbeddingAttemptGate attemptGate) {
        if (input == null || input.trim().isEmpty()) throw new IllegalArgumentException("input must not be blank");
        if (attemptGate == null) throw new IllegalArgumentException("attemptGate must not be null");
        this.input = input;
        this.attemptGate = attemptGate;
    }

    public String getInput() { return input; }
    public EmbeddingAttemptGate getAttemptGate() { return attemptGate; }
}
