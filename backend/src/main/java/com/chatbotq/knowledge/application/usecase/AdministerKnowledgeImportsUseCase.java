package com.chatbotq.knowledge.application.usecase;

import com.chatbotq.knowledge.application.model.KnowledgeCsvImportStrategy;
import com.chatbotq.knowledge.application.model.KnowledgeCsvPreview;
import com.chatbotq.knowledge.application.model.KnowledgeCsvPreviewError;
import com.chatbotq.knowledge.application.model.KnowledgeCsvPreviewRow;
import com.chatbotq.knowledge.application.model.KnowledgeTextNormalizer;
import com.chatbotq.knowledge.application.model.PersistedKnowledgeImportJob;
import com.chatbotq.knowledge.application.model.PersistedKnowledgeImportRow;
import com.chatbotq.knowledge.application.port.KnowledgeCsvPreviewPort;
import com.chatbotq.knowledge.application.port.KnowledgeImportAdministrationPort;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public final class AdministerKnowledgeImportsUseCase {
    private final KnowledgeCsvPreviewPort preview; private final KnowledgeImportAdministrationPort jobs; private final Clock clock;
    public AdministerKnowledgeImportsUseCase(KnowledgeCsvPreviewPort preview, KnowledgeImportAdministrationPort jobs, Clock clock) {
        this.preview = require(preview, "preview"); this.jobs = require(jobs, "jobs"); this.clock = require(clock, "clock");
    }
    public PersistedKnowledgeImportJob create(UUID actorId, UUID projectId, String fileName, byte[] bytes, KnowledgeCsvImportStrategy strategy) {
        KnowledgeCsvPreview result = preview.preview(require(bytes, "file"), require(strategy, "strategy"));
        List<PersistedKnowledgeImportRow> rows = new ArrayList<PersistedKnowledgeImportRow>();
        LinkedHashSet<String> summary = new LinkedHashSet<String>();
        for (KnowledgeCsvPreviewError error : result.getFileErrors()) summary.add(error.getCode());
        for (KnowledgeCsvPreviewRow row : result.getRows()) {
            List<String> errors = codes(row.getErrors()); summary.addAll(errors);
            rows.add(new PersistedKnowledgeImportRow(Math.toIntExact(row.getRowNumber()), row.getQuestion(), row.getAnswer(), row.getExternalId(),
                row.isActive(), errors.isEmpty() ? "VALID" : "INVALID", errors));
        }
        int valid = result.getValidRowCount();
        String status = result.getFileErrors().isEmpty() && valid == rows.size() ? "READY" : "FAILED";
        PersistedKnowledgeImportJob job = new PersistedKnowledgeImportJob(UUID.randomUUID(), require(projectId, "projectId"), require(actorId, "actorId"),
            KnowledgeTextNormalizer.normalize(fileName, "fileName", 512, false), strategy.name(), status, rows.size(), valid,
            rows.size() - valid, 0, new ArrayList<String>(summary), clock.instant(), rows);
        return jobs.create(actorId, job);
    }
    public PersistedKnowledgeImportJob get(UUID actorId, UUID projectId, UUID jobId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        return jobs.get(require(actorId, "actorId"), require(projectId, "projectId"), require(jobId, "jobId"), page, size);
    }
    private static List<String> codes(List<KnowledgeCsvPreviewError> errors) { List<String> result = new ArrayList<String>(); for (KnowledgeCsvPreviewError error : errors) result.add(error.getCode()); return result; }
    private static <T> T require(T value, String name) { if (value == null) throw new IllegalArgumentException(name + " is required"); return value; }
}
