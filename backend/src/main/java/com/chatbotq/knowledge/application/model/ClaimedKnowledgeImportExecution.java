package com.chatbotq.knowledge.application.model;

import java.util.UUID;

public final class ClaimedKnowledgeImportExecution {
    private final UUID jobId;
    private final UUID projectId;
    private final String strategy;
    private final UUID claimToken;

    public ClaimedKnowledgeImportExecution(UUID jobId, UUID projectId, String strategy, UUID claimToken) {
        if (jobId == null || projectId == null || strategy == null || claimToken == null) {
            throw new IllegalArgumentException("claim fields must not be null");
        }
        this.jobId = jobId;
        this.projectId = projectId;
        this.strategy = strategy;
        this.claimToken = claimToken;
    }

    public UUID getJobId() { return jobId; }
    public UUID getProjectId() { return projectId; }
    public String getStrategy() { return strategy; }
    public UUID getClaimToken() { return claimToken; }
}
