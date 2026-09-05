package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;
import com.chatbotq.knowledge.application.port.KnowledgeEmbeddingProcessingPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public class JdbcKnowledgeEmbeddingProcessingAdapter implements KnowledgeEmbeddingProcessingPort {
    private final JdbcTemplate jdbc;

    public JdbcKnowledgeEmbeddingProcessingAdapter(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc must not be null");
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Optional<ClaimedKnowledgeEmbedding> claimOnePending() {
        List<ClaimedKnowledgeEmbedding> claims = jdbc.query(
            "with candidate as materialized (select id from knowledge_entry where embedding_status='PENDING' "
                + "order by updated_at,id for update skip locked limit 1), claimed as (update knowledge_entry e "
                + "set embedding_status='PROCESSING',embedding_attempt_count=e.embedding_attempt_count+1,"
                + "embedding_last_attempt_at=current_timestamp from candidate c where e.id=c.id "
                + "and e.embedding_status='PENDING' returning e.id,e.embedding_revision,e.question) "
                + "select id,embedding_revision,question from claimed",
            (rs, rowNum) -> new ClaimedKnowledgeEmbedding(rs.getObject("id", java.util.UUID.class),
                rs.getLong("embedding_revision"), rs.getString("question")));
        return claims.isEmpty() ? Optional.empty() : Optional.of(claims.get(0));
    }

    @Override
    @Transactional
    public boolean markReady(ClaimedKnowledgeEmbedding claim, float[] embedding) {
        if (claim == null) throw new IllegalArgumentException("claim must not be null");
        validateEmbedding(embedding);
        return jdbc.update("update knowledge_entry set embedding_status='READY',embedding=cast(? as vector),"
                + "embedded_at=current_timestamp,embedding_last_error_code=null,embedding_last_error_message=null,"
                + "updated_at=current_timestamp where id=? and embedding_revision=? and embedding_status='PROCESSING'",
            toVector(embedding), claim.getEntryId(), claim.getEmbeddingRevision()) == 1;
    }

    private static void validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length != 1536) {
            throw new IllegalArgumentException("embedding must contain exactly 1536 dimensions");
        }
        for (float value : embedding) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("embedding must contain only finite values");
            }
        }
    }

    private static String toVector(float[] values) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) builder.append(',');
            builder.append(values[index]);
        }
        return builder.append(']').toString();
    }
}
