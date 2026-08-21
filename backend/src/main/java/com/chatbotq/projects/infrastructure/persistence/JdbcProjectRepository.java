package com.chatbotq.projects.infrastructure.persistence;

import com.chatbotq.projects.application.port.ProjectRepository;
import com.chatbotq.projects.domain.Project;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

public final class JdbcProjectRepository implements ProjectRepository {
    private final JdbcTemplate jdbc;

    public JdbcProjectRepository(JdbcTemplate jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc must not be null");
        }
        this.jdbc = jdbc;
    }

    @Override
    public Project save(Project project) {
        if (project == null) {
            throw new IllegalArgumentException("project must not be null");
        }
        jdbc.update("insert into project (id, name, status, site_key, "
                + "conversation_inactivity_seconds, retention_days, history_message_limit, "
                + "history_token_limit, response_token_limit, similarity_threshold, "
                + "retrieval_top_k, handoff_enabled, handoff_after_questions, "
                + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            project.getId(), project.getName(), project.isActive() ? "ACTIVE" : "DISABLED",
            project.getSiteKey(), project.getConversationInactivitySeconds(),
            project.getRetentionDays(), project.getHistoryMessageLimit(),
            project.getHistoryTokenLimit(), project.getResponseTokenLimit(),
            project.getSimilarityThreshold(), project.getRetrievalTopK(),
            project.isHandoffEnabled(), project.getHandoffAfterQuestions(),
            Timestamp.from(project.getCreatedAt()), Timestamp.from(project.getUpdatedAt()));
        return project;
    }
}
