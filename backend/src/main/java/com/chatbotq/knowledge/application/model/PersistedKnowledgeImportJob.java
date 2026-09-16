package com.chatbotq.knowledge.application.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class PersistedKnowledgeImportJob {
    private final UUID id, projectId, createdBy;
    private final String fileName, strategy, status;
    private final int totalRows, validRows, invalidRows, importedRows;
    private final List<String> errorSummary;
    private final Instant createdAt;
    private final List<PersistedKnowledgeImportRow> rows;

    public PersistedKnowledgeImportJob(UUID id, UUID projectId, UUID createdBy, String fileName, String strategy, String status,
                                       int totalRows, int validRows, int invalidRows, int importedRows, List<String> errorSummary,
                                       Instant createdAt, List<PersistedKnowledgeImportRow> rows) {
        this.id = id; this.projectId = projectId; this.createdBy = createdBy; this.fileName = fileName;
        this.strategy = strategy; this.status = status; this.totalRows = totalRows; this.validRows = validRows;
        this.invalidRows = invalidRows; this.importedRows = importedRows;
        this.errorSummary = Collections.unmodifiableList(new ArrayList<String>(errorSummary));
        this.createdAt = createdAt; this.rows = Collections.unmodifiableList(new ArrayList<PersistedKnowledgeImportRow>(rows));
    }
    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getCreatedBy() { return createdBy; }
    public String getFileName() { return fileName; }
    public String getStrategy() { return strategy; }
    public String getStatus() { return status; }
    public int getTotalRows() { return totalRows; }
    public int getValidRows() { return validRows; }
    public int getInvalidRows() { return invalidRows; }
    public int getImportedRows() { return importedRows; }
    public List<String> getErrorSummary() { return errorSummary; }
    public Instant getCreatedAt() { return createdAt; }
    public List<PersistedKnowledgeImportRow> getRows() { return rows; }
}
