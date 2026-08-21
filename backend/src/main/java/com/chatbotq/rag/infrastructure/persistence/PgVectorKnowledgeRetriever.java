package com.chatbotq.rag.infrastructure.persistence;

import com.chatbotq.rag.application.model.RetrievalCandidate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

public final class PgVectorKnowledgeRetriever {
    private static final int EMBEDDING_DIMENSIONS = 1536;
    private final JdbcTemplate jdbc;

    public PgVectorKnowledgeRetriever(JdbcTemplate jdbc) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc must not be null");
        }
        this.jdbc = jdbc;
    }

    public List<RetrievalCandidate> retrieve(UUID projectId, float[] queryEmbedding,
                                             double threshold, int topK) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        if (queryEmbedding == null || queryEmbedding.length != EMBEDDING_DIMENSIONS) {
            throw new IllegalArgumentException("queryEmbedding must contain exactly 1536 dimensions");
        }
        if (threshold < 0.0d || threshold > 1.0d) {
            throw new IllegalArgumentException("threshold must be between 0 and 1");
        }
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }

        String sql = "select knowledge_entry_id, question, answer, similarity_score "
            + "from (select id as knowledge_entry_id, question, answer, "
            + "1 - (embedding <=> cast(? as vector)) as similarity_score "
            + "from knowledge_entry "
            + "where project_id = ? and active = true and embedding is not null) candidates "
            + "where similarity_score >= ? "
            + "order by similarity_score desc, knowledge_entry_id "
            + "limit ?";

        return jdbc.query(sql,
            new Object[]{toVector(queryEmbedding), projectId, threshold, topK},
            (result, rowNumber) -> new RetrievalCandidate(
                result.getObject("knowledge_entry_id", UUID.class),
                result.getString("question"),
                result.getString("answer"),
                result.getDouble("similarity_score")));
    }

    private static String toVector(float[] values) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(values[index]);
        }
        return builder.append(']').toString();
    }
}
