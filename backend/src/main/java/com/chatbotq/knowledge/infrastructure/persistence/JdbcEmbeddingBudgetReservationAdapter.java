package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeEmbedding;
import com.chatbotq.knowledge.application.port.EmbeddingBudgetReservationPort;
import com.chatbotq.knowledge.application.port.EmbeddingBudgetReservationPort.Settlement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/** PostgreSQL-backed, serialized account-global embedding budget ledger by HTTP attempt. */
public final class JdbcEmbeddingBudgetReservationAdapter implements EmbeddingBudgetReservationPort {
    private static final BigDecimal COST_PER_MILLION_TOKENS_USD = new BigDecimal("0.02");
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final int GLOBAL_EMBEDDING_BUDGET_LOCK = 731942;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int maxEntriesPerDay;
    private final int maxInputTokensPerDay;
    private final BigDecimal monthlyHardLimitUsd;

    public JdbcEmbeddingBudgetReservationAdapter(JdbcTemplate jdbc, int maxEntriesPerDay,
                                                 int maxInputTokensPerDay, BigDecimal monthlyHardLimitUsd) {
        if (jdbc == null || jdbc.getDataSource() == null) throw new IllegalArgumentException("jdbc must have a data source");
        if (maxEntriesPerDay < 1 || maxInputTokensPerDay < 1) throw new IllegalArgumentException("daily limits must be positive");
        if (monthlyHardLimitUsd == null || monthlyHardLimitUsd.signum() < 0) throw new IllegalArgumentException("monthlyHardLimitUsd must not be negative");
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        this.maxEntriesPerDay = maxEntriesPerDay;
        this.maxInputTokensPerDay = maxInputTokensPerDay;
        this.monthlyHardLimitUsd = monthlyHardLimitUsd;
    }

    @Override
    public Reservation reserve(final ClaimedKnowledgeEmbedding claim) {
        return reserve(claim, 1);
    }

    @Override
    public Reservation reserve(final ClaimedKnowledgeEmbedding claim, final int attemptNumber) {
        if (claim == null) throw new IllegalArgumentException("claim must not be null");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        return transactions.execute(status -> reserveInTransaction(claim, attemptNumber));
    }

    @Override
    public void settle(final Reservation reservation, final Settlement settlement) {
        if (reservation == null || reservation.getDecision() != Decision.RESERVED || reservation.getId() == null) throw new IllegalArgumentException("reservation must be granted");
        if (settlement == null) throw new IllegalArgumentException("settlement must not be null");
        transactions.execute(status -> {
            int updated = jdbc.update("update embedding_budget_reservation set outcome=?,settled_at=clock_timestamp() where id=? and outcome is null",
                settlement.name(), reservation.getId());
            if (updated == 1) return null;
            Boolean alreadySettled = jdbc.queryForObject("select exists(select 1 from embedding_budget_reservation where id=? and outcome=?)",
                Boolean.class, reservation.getId(), settlement.name());
            if (!Boolean.TRUE.equals(alreadySettled)) throw new IllegalStateException("embedding reservation settlement conflict");
            return null;
        });
    }

    private Reservation reserveInTransaction(ClaimedKnowledgeEmbedding claim, int attemptNumber) {
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, GLOBAL_EMBEDDING_BUDGET_LOCK);
        if (!currentlyOwns(claim)) return Reservation.stale();
        UUID existing = jdbc.query("select id from embedding_budget_reservation where knowledge_entry_id=? and embedding_revision=? and claim_token=? and attempt_number=?",
            (ResultSetExtractor<UUID>) rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            claim.getEntryId(), claim.getEmbeddingRevision(), claim.getClaimToken(), attemptNumber);
        if (existing != null) return Reservation.reserved(existing);

        Integer dailyEntries = jdbc.queryForObject("select count(*) from embedding_budget_reservation where reserved_utc_day=(clock_timestamp() at time zone 'UTC')::date and outcome is distinct from 'CANCELLED'", Integer.class);
        Long dailyTokens = jdbc.queryForObject("select coalesce(sum(input_token_upper_bound),0) from embedding_budget_reservation where reserved_utc_day=(clock_timestamp() at time zone 'UTC')::date and outcome is distinct from 'CANCELLED'", Long.class);
        BigDecimal monthlyCost = jdbc.queryForObject("select coalesce(sum(reserved_cost_usd),0) from embedding_budget_reservation where reserved_utc_month=date_trunc('month', clock_timestamp() at time zone 'UTC')::date and outcome is distinct from 'CANCELLED'", BigDecimal.class);
        BigDecimal cost = costFor(claim.getInputTokenUpperBound());
        if (dailyEntries.intValue() >= maxEntriesPerDay || dailyTokens.longValue() + claim.getInputTokenUpperBound() > maxInputTokensPerDay || monthlyCost.add(cost).compareTo(monthlyHardLimitUsd) > 0) return Reservation.denied();

        UUID id = UUID.randomUUID();
        jdbc.update("insert into embedding_budget_reservation(id,knowledge_entry_id,project_id,embedding_revision,claim_token,attempt_number,input_token_upper_bound,reserved_cost_usd,reserved_utc_day,reserved_utc_month) values (?,?,?,?,?,?,?, ?,(clock_timestamp() at time zone 'UTC')::date,date_trunc('month', clock_timestamp() at time zone 'UTC')::date)",
            id, claim.getEntryId(), claim.getProjectId(), claim.getEmbeddingRevision(), claim.getClaimToken(), attemptNumber, claim.getInputTokenUpperBound(), cost);
        return Reservation.reserved(id);
    }

    private boolean currentlyOwns(ClaimedKnowledgeEmbedding claim) {
        Boolean owns = jdbc.query("select true from knowledge_entry where id=? and project_id=? and embedding_revision=? and embedding_status='PROCESSING' and embedding_processing_claim_token=? and embedding_processing_lease_expires_at>clock_timestamp() for update", (ResultSetExtractor<Boolean>) rs -> rs.next(),
            claim.getEntryId(), claim.getProjectId(), claim.getEmbeddingRevision(), claim.getClaimToken());
        return Boolean.TRUE.equals(owns);
    }

    private static BigDecimal costFor(int tokens) {
        return BigDecimal.valueOf(tokens).multiply(COST_PER_MILLION_TOKENS_USD).divide(ONE_MILLION);
    }
}
