package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;
import com.chatbotq.knowledge.application.port.KnowledgeEmbeddingProcessingPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class JdbcKnowledgeEmbeddingProcessingAdapter implements KnowledgeEmbeddingProcessingPort {
    private static final Map<String, String> SAFE_FAILURE_MESSAGES = safeFailureMessages();
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
                + "or (embedding_status='PROCESSING' and embedding_processing_lease_expires_at<=clock_timestamp()) "
                + "order by updated_at,id for update skip locked limit 1), claimed as (update knowledge_entry e "
                + "set embedding_status='PROCESSING',embedding_processing_claim_token=gen_random_uuid(),"
                + "embedding_processing_lease_expires_at=clock_timestamp()+interval '5 minutes' "
                + "from candidate c where e.id=c.id and (e.embedding_status='PENDING' or "
                + "(e.embedding_status='PROCESSING' and e.embedding_processing_lease_expires_at<=clock_timestamp())) "
                + "returning e.id,e.project_id,e.embedding_revision,e.question,e.embedding_input_token_upper_bound,e.embedding_processing_claim_token) "
                + "select id,project_id,embedding_revision,question,embedding_input_token_upper_bound,embedding_processing_claim_token from claimed",
            (rs, rowNum) -> new ClaimedKnowledgeEmbedding(rs.getObject("id", java.util.UUID.class),
                rs.getObject("project_id", java.util.UUID.class), rs.getLong("embedding_revision"), rs.getString("question"),
                rs.getInt("embedding_input_token_upper_bound"),
                rs.getObject("embedding_processing_claim_token", java.util.UUID.class)));
        return claims.isEmpty() ? Optional.empty() : Optional.of(claims.get(0));
    }

    @Override
    @Transactional
    public boolean recordProviderAttempt(ClaimedKnowledgeEmbedding claim) {
        if (claim == null) throw new IllegalArgumentException("claim must not be null");
        return jdbc.update("update knowledge_entry set embedding_attempt_count=embedding_attempt_count+1,"
                + "embedding_last_attempt_at=clock_timestamp(),embedding_processing_lease_expires_at=clock_timestamp()+interval '5 minutes',updated_at=current_timestamp "
                + "where id=? and embedding_revision=? and embedding_status='PROCESSING' and embedding_processing_claim_token=? "
                + "and embedding_processing_lease_expires_at>clock_timestamp()",
            claim.getEntryId(), claim.getEmbeddingRevision(), claim.getClaimToken()) == 1;
    }

    @Override
    @Transactional
    public boolean markReady(ClaimedKnowledgeEmbedding claim, float[] embedding) {
        if (claim == null) throw new IllegalArgumentException("claim must not be null");
        validateEmbedding(embedding);
        return jdbc.update("update knowledge_entry set embedding_status='READY',embedding=cast(? as vector),"
                + "embedded_at=current_timestamp,embedding_processing_claim_token=null,"
                + "embedding_processing_lease_expires_at=null,embedding_last_error_code=null,embedding_last_error_message=null,"
                + "updated_at=current_timestamp where id=? and embedding_revision=? and embedding_status='PROCESSING' "
                + "and embedding_processing_claim_token=? and embedding_processing_lease_expires_at>clock_timestamp()",
            toVector(embedding), claim.getEntryId(), claim.getEmbeddingRevision(), claim.getClaimToken()) == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(ClaimedKnowledgeEmbedding claim, String errorCode, String errorMessage) {
        if (claim == null) throw new IllegalArgumentException("claim must not be null");
        validateSafeError(errorCode, errorMessage);
        return jdbc.update("update knowledge_entry set embedding_status='FAILED',embedding=null,embedded_at=null,"
                + "embedding_processing_claim_token=null,embedding_processing_lease_expires_at=null,"
                + "embedding_last_error_code=?,embedding_last_error_message=?,updated_at=current_timestamp "
                + "where id=? and embedding_revision=? and embedding_status='PROCESSING' and embedding_processing_claim_token=? "
                + "and embedding_processing_lease_expires_at>clock_timestamp()",
            errorCode, errorMessage, claim.getEntryId(), claim.getEmbeddingRevision(), claim.getClaimToken()) == 1;
    }

    private static void validateSafeError(String errorCode, String errorMessage) {
        String expectedMessage = SAFE_FAILURE_MESSAGES.get(errorCode);
        if (expectedMessage == null || !expectedMessage.equals(errorMessage)) {
            throw new IllegalArgumentException("error must be a supported safe diagnostic");
        }
    }

    private static Map<String, String> safeFailureMessages() {
        Map<String, String> messages = new HashMap<String, String>();
        messages.put("PROVIDER_TRANSIENT", "Embedding provider temporarily unavailable");
        messages.put("PROVIDER_TIMEOUT", "Embedding provider timed out");
        messages.put("PROVIDER_UNAVAILABLE", "Embedding provider unavailable");
        messages.put("PROVIDER_INVALID_RESPONSE", "Embedding provider returned an invalid response");
        messages.put("PROVIDER_FAILURE", "Embedding generation failed");
        messages.put("EMBEDDING_BUDGET_LIMIT_REACHED", "Embedding budget limit reached");
        return Collections.unmodifiableMap(messages);
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
