package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportRow;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntryPage;
import com.chatbotq.knowledge.application.model.NewKnowledgeEntry;
import com.chatbotq.knowledge.application.port.KnowledgeAdministrationPort;
import com.chatbotq.knowledge.application.port.KnowledgeImportRowMutationPort;
import com.chatbotq.knowledge.application.usecase.ForbiddenKnowledgeAdministrationException;
import com.chatbotq.knowledge.application.usecase.KnowledgeEntryNotFoundException;
import com.chatbotq.knowledge.application.usecase.KnowledgeVersionConflictException;
import com.chatbotq.knowledge.application.usecase.StaleKnowledgeImportMutationException;
import com.chatbotq.projects.application.usecase.ProjectNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

public class JdbcKnowledgeAdministrationAdapter implements KnowledgeAdministrationPort, KnowledgeImportRowMutationPort {
    private final JdbcTemplate jdbc;
    private final Runnable afterClaimedRowLocked;
    private final Runnable afterEntryInserted;

    // Both authorization protocols feed this one canonical persistence primitive. Imports attribute the entry
    // to the actor who created the durable import job; a reclaimed/fenced job cannot reach this CTE.
    private static String canonicalEntryInsertion(String source, String conflictClause) {
        return "inserted as (insert into knowledge_entry "
            + "(id,project_id,question,answer,external_id,active,embedding_input_token_upper_bound,created_by,updated_by,created_at,updated_at) "
            + source + conflictClause
            + " returning id,project_id,question,answer,external_id,active,embedding_status,embedding_revision,embedding_attempt_count,"
            + "embedding_last_attempt_at,embedding_last_error_code,embedding_last_error_message,version,created_at,updated_at)";
    }

    // The interactive update and an existing-entry UPSERT share this complete lifecycle assignment.
    // Callers provide an entry locked with FOR UPDATE and supply the question placeholders in this order.
    private static String canonicalEntryUpdateAssignment(String actorExpression, String nowExpression) {
        return "question=?,answer=?,external_id=?,active=?,updated_by=" + actorExpression + ",updated_at=" + nowExpression
            + ",version=u.version+1,embedding_input_token_upper_bound=case when u.question is distinct from ? then ? else u.embedding_input_token_upper_bound end"
            + ",embedding_status=case when u.question is distinct from ? then 'PENDING' else u.embedding_status end"
            + ",embedding_revision=case when u.question is distinct from ? then u.embedding_revision+1 else u.embedding_revision end"
            + ",embedding=case when u.question is distinct from ? then null else u.embedding end"
            + ",embedded_at=case when u.question is distinct from ? then null else u.embedded_at end"
            + ",embedding_attempt_count=case when u.question is distinct from ? then 0 else u.embedding_attempt_count end"
            + ",embedding_last_attempt_at=case when u.question is distinct from ? then null else u.embedding_last_attempt_at end"
            + ",embedding_last_error_code=case when u.question is distinct from ? then null else u.embedding_last_error_code end"
            + ",embedding_last_error_message=case when u.question is distinct from ? then null else u.embedding_last_error_message end"
            + ",embedding_processing_claim_token=case when u.question is distinct from ? then null else u.embedding_processing_claim_token end"
            + ",embedding_processing_lease_expires_at=case when u.question is distinct from ? then null else u.embedding_processing_lease_expires_at end";
    }

    public JdbcKnowledgeAdministrationAdapter(JdbcTemplate jdbc) {
        this(jdbc, () -> { }, () -> { });
    }

    JdbcKnowledgeAdministrationAdapter(JdbcTemplate jdbc, Runnable afterClaimedRowLocked) {
        this(jdbc, afterClaimedRowLocked, () -> { });
    }

    JdbcKnowledgeAdministrationAdapter(JdbcTemplate jdbc, Runnable afterClaimedRowLocked, Runnable afterEntryInserted) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc must not be null");
        if (afterClaimedRowLocked == null) throw new IllegalArgumentException("afterClaimedRowLocked must not be null");
        if (afterEntryInserted == null) throw new IllegalArgumentException("afterEntryInserted must not be null");
        this.jdbc = jdbc;
        this.afterClaimedRowLocked = afterClaimedRowLocked;
        this.afterEntryInserted = afterEntryInserted;
    }

    @Override
    @Transactional
    public ManagedKnowledgeEntry create(UUID actorId, UUID projectId, UUID entryId, String question, String answer,
                                        String externalId, boolean active, int embeddingInputTokenUpperBound, Instant now) {
        List<CreateOutcome> outcomes = jdbc.query(
            "with actor as materialized (select u.id,u.is_general_admin from admin_user u where u.id=? "
                + "and u.status='ACTIVE' and (u.locked_until is null or u.locked_until<=current_timestamp)), "
                + "target as materialized (select p.id,p.status from project p where p.id=?), "
                + "decision as materialized (select exists (select 1 from actor a where a.is_general_admin) "
                + "general_admin, exists (select 1 from target) project_exists, exists (select 1 from actor a "
                + "join target p on p.status='ACTIVE' where a.is_general_admin or exists (select 1 "
                + "from user_project_role upr where upr.user_id=a.id and upr.project_id=p.id "
                + "and upr.role='PROJECT_ADMIN')) authorized), "
                + canonicalEntryInsertion("select ?,t.id,?,?,?,?,?,?,?,?,? from target t cross join decision d where d.authorized", "")
                + " select d.general_admin,d.project_exists,d.authorized,i.id,i.project_id,"
                + "i.question,i.answer,i.external_id,i.active,i.embedding_status,i.embedding_revision,i.embedding_attempt_count,i.embedding_last_attempt_at,i.embedding_last_error_code,i.embedding_last_error_message,i.version,i.created_at,"
                + "i.updated_at from decision d left join inserted i on true",
            (rs, rowNum) -> mapOutcome(rs), actorId, projectId, entryId, question, answer, externalId, active,
            embeddingInputTokenUpperBound, actorId, actorId, Timestamp.from(now), Timestamp.from(now));
        CreateOutcome outcome = outcomes.get(0);
        if (!outcome.authorized) {
            if (outcome.generalAdmin && !outcome.projectExists) throw new ProjectNotFoundException();
            throw new ForbiddenKnowledgeAdministrationException();
        }
        return outcome.entry;
    }

    @Override
    @Transactional
    public KnowledgeImportRowMutationPort.Result createOnly(ClaimedKnowledgeImportExecution execution,
                                                              ClaimedKnowledgeImportRow row, NewKnowledgeEntry entry) {
        if (execution == null || row == null || entry == null) throw new IllegalArgumentException("import mutation fields must not be null");
        if (!lockClaimedRow(execution, row)) return KnowledgeImportRowMutationPort.Result.STALE;
        afterClaimedRowLocked.run();

        // ON CONFLICT observes the unique-index result, not the statement snapshot. A later READ COMMITTED
        // statement locks the actual conflicting entry before it terminalizes the row, so a concurrent delete
        // either happens first and is retried as an insert, or waits until the FAILED outcome is durable.
        for (int attempt = 0; attempt < 3; attempt++) {
            UUID inserted = insertIfFenceIsCurrent(execution, entry);
            if (inserted != null) {
                afterEntryInserted.run();
                if (!terminalize(execution, row, "IMPORTED", inserted, null)) {
                    throw new StaleKnowledgeImportMutationException();
                }
                return KnowledgeImportRowMutationPort.Result.IMPORTED;
            }
            if (!isCurrentClaim(execution)) return KnowledgeImportRowMutationPort.Result.STALE;
            if (lockActualConflict(execution.getProjectId(), entry.getExternalId())) {
                return terminalize(execution, row, "FAILED", null, "knowledge_external_id_conflict")
                    ? KnowledgeImportRowMutationPort.Result.EXTERNAL_ID_CONFLICT : KnowledgeImportRowMutationPort.Result.STALE;
            }
        }
        throw new IllegalStateException("external-id conflict changed too often to terminalize import row");
    }

    @Override
    @Transactional
    public KnowledgeImportRowMutationPort.Result upsert(ClaimedKnowledgeImportExecution execution,
                                                          ClaimedKnowledgeImportRow row, NewKnowledgeEntry entry) {
        if (execution == null || row == null || entry == null) throw new IllegalArgumentException("import mutation fields must not be null");
        if (!lockClaimedUpsertRow(execution, row)) return KnowledgeImportRowMutationPort.Result.STALE;
        afterClaimedRowLocked.run();

        // An INSERT ... DO NOTHING observes concurrent unique-index entries.  If it loses that race, the
        // following statement locks the exact same-project entry before applying the canonical update.
        for (int attempt = 0; attempt < 3; attempt++) {
            UUID inserted = insertUpsertIfFenceIsCurrent(execution, entry);
            if (inserted != null) {
                afterEntryInserted.run();
                if (!terminalizeUpsert(execution, row, inserted)) throw new StaleKnowledgeImportMutationException();
                return KnowledgeImportRowMutationPort.Result.IMPORTED;
            }
            UUID existing = lockAndUpdateUpsertTargetIfFenceIsCurrent(execution, entry);
            if (existing != null) {
                afterEntryInserted.run();
                if (!terminalizeUpsert(execution, row, existing)) throw new StaleKnowledgeImportMutationException();
                return KnowledgeImportRowMutationPort.Result.IMPORTED;
            }
            if (!isCurrentUpsertClaim(execution)) return KnowledgeImportRowMutationPort.Result.STALE;
        }
        throw new IllegalStateException("external-id target changed too often to UPSERT import row");
    }

    private boolean lockClaimedUpsertRow(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row) {
        List<Integer> locked = jdbc.query("select r.row_number from knowledge_import_row r join knowledge_import_job j on j.id=r.import_job_id "
                + "where j.id=? and j.project_id=? and j.strategy='UPSERT' and j.status='PROCESSING' "
                + "and j.execution_claim_token=? and j.execution_lease_expires_at>clock_timestamp() "
                + "and r.import_job_id=? and r.row_number=? and r.status='PROCESSING' for update of r",
            (rs, rowNum) -> rs.getInt(1), execution.getJobId(), execution.getProjectId(), execution.getClaimToken(),
            row.getJobId(), row.getRowNumber());
        return !locked.isEmpty();
    }

    private UUID insertUpsertIfFenceIsCurrent(ClaimedKnowledgeImportExecution execution, NewKnowledgeEntry entry) {
        List<UUID> inserted = jdbc.query("with current_job as materialized (select id,project_id,created_by from knowledge_import_job "
                + "where id=? and project_id=? and strategy='UPSERT' and status='PROCESSING' and execution_claim_token=? "
                + "and execution_lease_expires_at>clock_timestamp() and created_by is not null for no key update), "
                + canonicalEntryInsertion("select ?,j.project_id,?,?,?,?,?,j.created_by,j.created_by,clock_timestamp(),clock_timestamp() "
                    + "from current_job j", " on conflict (project_id,external_id) do nothing")
                + " select id from inserted",
            (rs, rowNum) -> rs.getObject("id", UUID.class), execution.getJobId(), execution.getProjectId(), execution.getClaimToken(),
            entry.getId(), entry.getQuestion(), entry.getAnswer(), entry.getExternalId(), entry.isActive(),
            entry.getEmbeddingInputTokenUpperBound());
        return inserted.isEmpty() ? null : inserted.get(0);
    }

    private UUID lockAndUpdateUpsertTargetIfFenceIsCurrent(ClaimedKnowledgeImportExecution execution, NewKnowledgeEntry entry) {
        List<UUID> targets = jdbc.query("with current_job as materialized (select id,project_id,created_by from knowledge_import_job "
                + "where id=? and project_id=? and strategy='UPSERT' and status='PROCESSING' and execution_claim_token=? "
                + "and execution_lease_expires_at>clock_timestamp() and created_by is not null for no key update), "
                + "entry as materialized (select e.* from knowledge_entry e join current_job j on j.project_id=e.project_id "
                + "where e.external_id=? for update), "
                + "updated as (update knowledge_entry u set " + canonicalEntryUpdateAssignment("j.created_by", "clock_timestamp()")
                + " from entry e cross join current_job j where u.id=e.id and (u.question is distinct from ? or u.answer is distinct from ? "
                + "or u.external_id is distinct from ? or u.active is distinct from ?) and (u.question is not distinct from ? "
                + "or u.embedding_revision < 9223372036854775807) returning u.id), "
                + "unchanged as (select e.id from entry e where e.question is not distinct from ? and e.answer is not distinct from ? "
                + "and e.external_id is not distinct from ? and e.active is not distinct from ?), "
                + "snapshot as (select id from updated union all select id from unchanged) select id from snapshot",
            (rs, rowNum) -> rs.getObject("id", UUID.class), execution.getJobId(), execution.getProjectId(), execution.getClaimToken(),
            entry.getExternalId(), entry.getQuestion(), entry.getAnswer(), entry.getExternalId(), entry.isActive(),
            entry.getQuestion(), entry.getEmbeddingInputTokenUpperBound(), entry.getQuestion(), entry.getQuestion(), entry.getQuestion(),
            entry.getQuestion(), entry.getQuestion(), entry.getQuestion(), entry.getQuestion(), entry.getQuestion(), entry.getQuestion(),
            entry.getQuestion(), entry.getQuestion(), entry.getAnswer(), entry.getExternalId(), entry.isActive(), entry.getQuestion(),
            entry.getQuestion(), entry.getAnswer(), entry.getExternalId(), entry.isActive());
        return targets.isEmpty() ? null : targets.get(0);
    }

    private boolean isCurrentUpsertClaim(ClaimedKnowledgeImportExecution execution) {
        Integer current = jdbc.queryForObject("select count(*) from knowledge_import_job where id=? and project_id=? "
                + "and strategy='UPSERT' and status='PROCESSING' and execution_claim_token=? and created_by is not null "
                + "and execution_lease_expires_at>clock_timestamp()", Integer.class,
            execution.getJobId(), execution.getProjectId(), execution.getClaimToken());
        return current != null && current == 1;
    }

    private boolean terminalizeUpsert(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row, UUID entryId) {
        return jdbc.update("update knowledge_import_row r set status='IMPORTED',knowledge_entry_id=?,execution_error_code=null "
                + "from knowledge_import_job j where r.import_job_id=j.id and r.import_job_id=? and r.row_number=? "
                + "and r.status='PROCESSING' and j.id=? and j.project_id=? and j.strategy='UPSERT' "
                + "and j.status='PROCESSING' and j.execution_claim_token=? and j.execution_lease_expires_at>clock_timestamp()",
            entryId, row.getJobId(), row.getRowNumber(), execution.getJobId(), execution.getProjectId(), execution.getClaimToken()) == 1;
    }

    private boolean lockClaimedRow(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row) {
        List<Integer> locked = jdbc.query("select r.row_number from knowledge_import_row r join knowledge_import_job j on j.id=r.import_job_id "
                + "where j.id=? and j.project_id=? and j.strategy='CREATE_ONLY' and j.status='PROCESSING' "
                + "and j.execution_claim_token=? and j.execution_lease_expires_at>clock_timestamp() "
                + "and r.import_job_id=? and r.row_number=? and r.status='PROCESSING' for update of r",
            (rs, rowNum) -> rs.getInt(1), execution.getJobId(), execution.getProjectId(), execution.getClaimToken(),
            row.getJobId(), row.getRowNumber());
        return !locked.isEmpty();
    }

    private UUID insertIfFenceIsCurrent(ClaimedKnowledgeImportExecution execution, NewKnowledgeEntry entry) {
        List<UUID> inserted = jdbc.query("with current_job as materialized (select id,project_id,created_by from knowledge_import_job "
                + "where id=? and project_id=? and strategy='CREATE_ONLY' and status='PROCESSING' and execution_claim_token=? "
                + "and execution_lease_expires_at>clock_timestamp() for no key update), "
                + canonicalEntryInsertion("select ?,j.project_id,?,?,?,?,?,j.created_by,j.created_by,clock_timestamp(),clock_timestamp() "
                    + "from current_job j where j.created_by is not null", " on conflict (project_id,external_id) do nothing")
                + " select id from inserted",
            (rs, rowNum) -> rs.getObject("id", UUID.class), execution.getJobId(), execution.getProjectId(), execution.getClaimToken(),
            entry.getId(), entry.getQuestion(), entry.getAnswer(), entry.getExternalId(), entry.isActive(),
            entry.getEmbeddingInputTokenUpperBound());
        return inserted.isEmpty() ? null : inserted.get(0);
    }

    private boolean isCurrentClaim(ClaimedKnowledgeImportExecution execution) {
        Integer current = jdbc.queryForObject("select count(*) from knowledge_import_job where id=? and project_id=? "
                + "and strategy='CREATE_ONLY' and status='PROCESSING' and execution_claim_token=? and created_by is not null "
                + "and execution_lease_expires_at>clock_timestamp()", Integer.class,
            execution.getJobId(), execution.getProjectId(), execution.getClaimToken());
        return current != null && current == 1;
    }

    private boolean lockActualConflict(UUID projectId, String externalId) {
        List<UUID> conflicts = jdbc.query("select id from knowledge_entry where project_id=? and external_id=? for key share",
            (rs, rowNum) -> rs.getObject("id", UUID.class), projectId, externalId);
        return !conflicts.isEmpty();
    }

    private boolean terminalize(ClaimedKnowledgeImportExecution execution, ClaimedKnowledgeImportRow row, String status,
                                UUID entryId, String errorCode) {
        return jdbc.update("update knowledge_import_row r set status=?,knowledge_entry_id=?,execution_error_code=? "
                + "from knowledge_import_job j where r.import_job_id=j.id and r.import_job_id=? and r.row_number=? "
                + "and r.status='PROCESSING' and j.id=? and j.project_id=? and j.strategy='CREATE_ONLY' "
                + "and j.status='PROCESSING' and j.execution_claim_token=? and j.execution_lease_expires_at>clock_timestamp()",
            status, entryId, errorCode, row.getJobId(), row.getRowNumber(), execution.getJobId(), execution.getProjectId(),
            execution.getClaimToken()) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public ManagedKnowledgeEntry get(UUID actorId, UUID projectId, UUID entryId) {
        List<ReadOutcome> outcomes = jdbc.query(
            "with actor as materialized (select u.id,u.is_general_admin from admin_user u where u.id=? "
                + "and u.status='ACTIVE' and (u.locked_until is null or u.locked_until<=current_timestamp)), "
                + "target as materialized (select p.id,p.status from project p where p.id=?), "
                + "decision as materialized (select exists (select 1 from actor a where a.is_general_admin) "
                + "general_admin, exists (select 1 from target) project_exists, exists (select 1 from actor a "
                + "join target p on p.status='ACTIVE' where a.is_general_admin or exists (select 1 "
                + "from user_project_role upr where upr.user_id=a.id and upr.project_id=p.id "
                + "and upr.role='PROJECT_ADMIN')) authorized), entry as materialized (select e.id,e.project_id,"
                + "e.question,e.answer,e.external_id,e.active,e.embedding_status,e.embedding_revision,e.embedding_attempt_count,"
                + "e.embedding_last_attempt_at,e.embedding_last_error_code,e.embedding_last_error_message,e.version,e.created_at,"
                + "e.updated_at from knowledge_entry e join target t on t.id=e.project_id cross join decision d "
                + "where e.id=? and d.authorized) select d.general_admin,d.project_exists,d.authorized,e.id,"
                + "e.project_id,e.question,e.answer,e.external_id,e.active,e.embedding_status,e.embedding_revision,e.embedding_attempt_count,"
                + "e.embedding_last_attempt_at,e.embedding_last_error_code,e.embedding_last_error_message,e.version,e.created_at,e.updated_at from decision d left join entry e on true",
            (rs, rowNum) -> mapReadOutcome(rs), actorId, projectId, entryId);
        ReadOutcome outcome = outcomes.get(0);
        if (!outcome.authorized) {
            if (outcome.generalAdmin && !outcome.projectExists) throw new ProjectNotFoundException();
            throw new ForbiddenKnowledgeAdministrationException();
        }
        if (outcome.entry == null) throw new KnowledgeEntryNotFoundException();
        return outcome.entry;
    }

    @Override
    @Transactional
    public ManagedKnowledgeEntry update(UUID actorId, UUID projectId, UUID entryId, String question, String answer,
                                        String externalId, boolean active, long version, int embeddingInputTokenUpperBound,
                                        Instant now) {
        List<UpdateOutcome> outcomes = jdbc.query(
            "with locked_actor as materialized (select u.id,u.is_general_admin,u.status,u.locked_until from admin_user u where u.id=? for update), "
                + "actor as materialized (select a.id,a.is_general_admin from locked_actor a where a.status='ACTIVE' and (a.locked_until is null or a.locked_until<=current_timestamp)), "
                + "locked_target as materialized (select p.id,p.status from project p where p.id=? and exists (select 1 from locked_actor) for update), "
                + "target as materialized (select p.id,p.status from locked_target p), "
                + "decision as materialized (select exists (select 1 from actor a where a.is_general_admin) general_admin,exists (select 1 from target) project_exists,exists (select 1 from actor a join target p on p.status='ACTIVE' where a.is_general_admin or exists (select 1 from user_project_role upr where upr.user_id=a.id and upr.project_id=p.id and upr.role='PROJECT_ADMIN')) authorized), "
                + "entry as materialized (select e.* from knowledge_entry e join target t on t.id=e.project_id cross join decision d where e.id=? and d.authorized for update), "
                + "updated as (update knowledge_entry u set " + canonicalEntryUpdateAssignment("?", "?") + " from entry e where u.id=e.id and u.version=? and (u.question is distinct from ? or u.answer is distinct from ? or u.external_id is distinct from ? or u.active is distinct from ?) and (u.question is not distinct from ? or u.embedding_revision < 9223372036854775807) returning u.*), "
                + "snapshot as materialized (select * from updated union all select * from entry where not exists (select 1 from updated)) "
                + "select d.general_admin,d.project_exists,d.authorized,exists(select 1 from entry) entry_exists,exists(select 1 from entry e where e.version<>?) version_conflict,exists(select 1 from entry e where e.version=? and e.question is distinct from ? and e.embedding_revision=9223372036854775807) revision_conflict,s.id,s.project_id,s.question,s.answer,s.external_id,s.active,s.embedding_status,s.embedding_revision,s.embedding_attempt_count,s.embedding_last_attempt_at,s.embedding_last_error_code,s.embedding_last_error_message,s.version,s.created_at,s.updated_at from decision d left join snapshot s on true",
            (rs, rowNum) -> mapUpdateOutcome(rs), actorId, projectId, entryId, question, answer, externalId, active,
            actorId, Timestamp.from(now), question, embeddingInputTokenUpperBound, question, question, question, question,
            question, question, question, question, question, question,
            version, question, answer, externalId, active, question, version, version, question);
        UpdateOutcome outcome = outcomes.get(0);
        if (!outcome.authorized) {
            if (outcome.generalAdmin && !outcome.projectExists) throw new ProjectNotFoundException();
            throw new ForbiddenKnowledgeAdministrationException();
        }
        if (!outcome.entryExists) throw new KnowledgeEntryNotFoundException();
        if (outcome.versionConflict || outcome.revisionConflict) throw new KnowledgeVersionConflictException();
        return outcome.entry;
    }

    @Override
    @Transactional
    public ManagedKnowledgeEntry retryEmbedding(UUID actorId, UUID projectId, UUID entryId, long version, Instant now) {
        List<RetryOutcome> outcomes = jdbc.query(
            "with locked_actor as materialized (select u.id,u.is_general_admin,u.status,u.locked_until from admin_user u where u.id=? for update), "
                + "actor as materialized (select a.id,a.is_general_admin from locked_actor a where a.status='ACTIVE' and (a.locked_until is null or a.locked_until<=current_timestamp)), "
                + "locked_target as materialized (select p.id,p.status from project p where p.id=? and exists(select 1 from locked_actor) for update), target as materialized (select * from locked_target), "
                + "decision as materialized (select exists(select 1 from actor a where a.is_general_admin) general_admin,exists(select 1 from target) project_exists,exists(select 1 from actor a join target p on p.status='ACTIVE' where a.is_general_admin or exists(select 1 from user_project_role upr where upr.user_id=a.id and upr.project_id=p.id and upr.role='PROJECT_ADMIN')) authorized), "
                + "entry as materialized (select e.* from knowledge_entry e join target t on t.id=e.project_id cross join decision d where e.id=? and d.authorized for update), "
                + "updated as (update knowledge_entry u set embedding_status='PENDING',updated_by=?,updated_at=?,version=u.version+1 from entry e where u.id=e.id and u.version=? and u.embedding_status='FAILED' returning u.*), "
                + "snapshot as materialized (select * from updated union all select * from entry where not exists(select 1 from updated)) "
                + "select d.general_admin,d.project_exists,d.authorized,exists(select 1 from entry) entry_exists,exists(select 1 from entry e where e.version<>?) version_conflict,exists(select 1 from entry e where e.version=? and e.embedding_status<>'FAILED') retry_not_allowed,s.id,s.project_id,s.question,s.answer,s.external_id,s.active,s.embedding_status,s.embedding_revision,s.embedding_attempt_count,s.embedding_last_attempt_at,s.embedding_last_error_code,s.embedding_last_error_message,s.version,s.created_at,s.updated_at from decision d left join snapshot s on true",
            (rs, rowNum) -> mapRetryOutcome(rs), actorId, projectId, entryId, actorId, Timestamp.from(now), version, version, version);
        RetryOutcome outcome = outcomes.get(0);
        if (!outcome.authorized) {
            if (outcome.generalAdmin && !outcome.projectExists) throw new ProjectNotFoundException();
            throw new ForbiddenKnowledgeAdministrationException();
        }
        if (!outcome.entryExists) throw new KnowledgeEntryNotFoundException();
        if (outcome.versionConflict) throw new KnowledgeVersionConflictException();
        if (outcome.retryNotAllowed) throw new com.chatbotq.knowledge.application.usecase.KnowledgeRetryNotAllowedException();
        return outcome.entry;
    }

    @Override
    @Transactional(readOnly = true)
    public ManagedKnowledgeEntryPage list(UUID actorId, UUID projectId, String query, int page, int size, long offset) {
        final List<ManagedKnowledgeEntry> entries = new ArrayList<>();
        List<ListOutcome> outcomes = jdbc.query(
            "with actor as materialized (select u.id,u.is_general_admin from admin_user u where u.id=? "
                + "and u.status='ACTIVE' and (u.locked_until is null or u.locked_until<=current_timestamp)), "
                + "target as materialized (select p.id,p.status from project p where p.id=?), "
                + "decision as materialized (select exists (select 1 from actor a where a.is_general_admin) general_admin, "
                + "exists (select 1 from target) project_exists, exists (select 1 from actor a join target p "
                + "on p.status='ACTIVE' where a.is_general_admin or exists (select 1 from user_project_role upr "
                + "where upr.user_id=a.id and upr.project_id=p.id and upr.role='PROJECT_ADMIN')) authorized), "
                + "base as materialized (select e.id,e.project_id,e.question,e.answer,e.external_id,e.active,"
                + "e.embedding_status,e.embedding_revision,e.embedding_attempt_count,e.embedding_last_attempt_at,"
                + "e.embedding_last_error_code,e.embedding_last_error_message,e.version,e.created_at,e.updated_at from knowledge_entry e "
                + "join target t on t.id=e.project_id cross join decision d where d.authorized and (cast(? as text) is null "
                + "or lower(e.question) like '%' || lower(?) || '%' escape '\\' or lower(coalesce(e.external_id,'')) like '%' || lower(?) || '%' escape '\\')), "
                + "totals as (select count(*) total_elements from base), paged as (select * from base order by updated_at desc,id asc "
                + "limit ? offset ?) select d.general_admin,d.project_exists,d.authorized,p.id,p.project_id,p.question,p.answer,"
                + "p.external_id,p.active,p.embedding_status,p.embedding_revision,p.embedding_attempt_count,p.embedding_last_attempt_at,"
                + "p.embedding_last_error_code,p.embedding_last_error_message,p.version,p.created_at,p.updated_at,t.total_elements "
                + "from decision d cross join totals t left join paged p on true order by p.updated_at desc,p.id asc",
            (rs, rowNum) -> mapListOutcome(rs), actorId, projectId, query, query, query, size, offset);
        ListOutcome outcome = outcomes.get(0);
        if (!outcome.authorized) {
            if (outcome.generalAdmin && !outcome.projectExists) throw new ProjectNotFoundException();
            throw new ForbiddenKnowledgeAdministrationException();
        }
        for (ListOutcome row : outcomes) if (row.entry != null) entries.add(row.entry);
        return new ManagedKnowledgeEntryPage(entries, page, size, outcome.totalElements);
    }

    private static CreateOutcome mapOutcome(ResultSet rs) throws SQLException {
        boolean authorized = rs.getBoolean("authorized");
        ManagedKnowledgeEntry entry = null;
        if (authorized && rs.getObject("id") != null) {
            entry = new ManagedKnowledgeEntry((UUID) rs.getObject("id"), (UUID) rs.getObject("project_id"),
                rs.getString("question"), rs.getString("answer"), rs.getString("external_id"),
                rs.getBoolean("active"), rs.getString("embedding_status"), rs.getLong("embedding_revision"), rs.getInt("embedding_attempt_count"),
                instant(rs, "embedding_last_attempt_at"), rs.getString("embedding_last_error_code"), rs.getString("embedding_last_error_message"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        }
        return new CreateOutcome(rs.getBoolean("general_admin"), rs.getBoolean("project_exists"), authorized, entry);
    }

    private static ReadOutcome mapReadOutcome(ResultSet rs) throws SQLException {
        ManagedKnowledgeEntry entry = null;
        if (rs.getObject("id") != null) {
            entry = new ManagedKnowledgeEntry((UUID) rs.getObject("id"), (UUID) rs.getObject("project_id"),
                rs.getString("question"), rs.getString("answer"), rs.getString("external_id"),
                rs.getBoolean("active"), rs.getString("embedding_status"), rs.getLong("embedding_revision"), rs.getInt("embedding_attempt_count"),
                instant(rs, "embedding_last_attempt_at"), rs.getString("embedding_last_error_code"), rs.getString("embedding_last_error_message"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        }
        return new ReadOutcome(rs.getBoolean("general_admin"), rs.getBoolean("project_exists"),
            rs.getBoolean("authorized"), entry);
    }

    private static UpdateOutcome mapUpdateOutcome(ResultSet rs) throws SQLException {
        ManagedKnowledgeEntry entry = rs.getObject("id") == null ? null : new ManagedKnowledgeEntry(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("project_id"), rs.getString("question"),
            rs.getString("answer"), rs.getString("external_id"), rs.getBoolean("active"),
            rs.getString("embedding_status"), rs.getLong("embedding_revision"), rs.getInt("embedding_attempt_count"),
            instant(rs, "embedding_last_attempt_at"), rs.getString("embedding_last_error_code"), rs.getString("embedding_last_error_message"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        return new UpdateOutcome(rs.getBoolean("general_admin"), rs.getBoolean("project_exists"),
            rs.getBoolean("authorized"), rs.getBoolean("entry_exists"), rs.getBoolean("version_conflict"),
            rs.getBoolean("revision_conflict"), entry);
    }

    private static RetryOutcome mapRetryOutcome(ResultSet rs) throws SQLException {
        ManagedKnowledgeEntry entry = rs.getObject("id") == null ? null : new ManagedKnowledgeEntry(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("project_id"), rs.getString("question"), rs.getString("answer"),
            rs.getString("external_id"), rs.getBoolean("active"), rs.getString("embedding_status"), rs.getLong("embedding_revision"),
            rs.getInt("embedding_attempt_count"), instant(rs, "embedding_last_attempt_at"), rs.getString("embedding_last_error_code"),
            rs.getString("embedding_last_error_message"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        return new RetryOutcome(rs.getBoolean("general_admin"), rs.getBoolean("project_exists"), rs.getBoolean("authorized"),
            rs.getBoolean("entry_exists"), rs.getBoolean("version_conflict"), rs.getBoolean("retry_not_allowed"), entry);
    }

    private static ListOutcome mapListOutcome(ResultSet rs) throws SQLException {
        ManagedKnowledgeEntry entry = rs.getObject("id") == null ? null : new ManagedKnowledgeEntry(
            (UUID) rs.getObject("id"), (UUID) rs.getObject("project_id"), rs.getString("question"),
            rs.getString("answer"), rs.getString("external_id"), rs.getBoolean("active"),
            rs.getString("embedding_status"), rs.getLong("embedding_revision"), rs.getInt("embedding_attempt_count"),
            instant(rs, "embedding_last_attempt_at"), rs.getString("embedding_last_error_code"), rs.getString("embedding_last_error_message"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        return new ListOutcome(rs.getBoolean("general_admin"), rs.getBoolean("project_exists"),
            rs.getBoolean("authorized"), rs.getLong("total_elements"), entry);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final class RetryOutcome {
        private final boolean generalAdmin, projectExists, authorized, entryExists, versionConflict, retryNotAllowed;
        private final ManagedKnowledgeEntry entry;
        private RetryOutcome(boolean generalAdmin, boolean projectExists, boolean authorized, boolean entryExists,
                             boolean versionConflict, boolean retryNotAllowed, ManagedKnowledgeEntry entry) {
            this.generalAdmin = generalAdmin; this.projectExists = projectExists; this.authorized = authorized;
            this.entryExists = entryExists; this.versionConflict = versionConflict; this.retryNotAllowed = retryNotAllowed; this.entry = entry;
        }
    }

    private static final class UpdateOutcome {
        private final boolean generalAdmin, projectExists, authorized, entryExists, versionConflict, revisionConflict;
        private final ManagedKnowledgeEntry entry;
        private UpdateOutcome(boolean generalAdmin, boolean projectExists, boolean authorized, boolean entryExists,
                              boolean versionConflict, boolean revisionConflict, ManagedKnowledgeEntry entry) {
            this.generalAdmin = generalAdmin; this.projectExists = projectExists; this.authorized = authorized;
            this.entryExists = entryExists; this.versionConflict = versionConflict; this.revisionConflict = revisionConflict;
            this.entry = entry;
        }
    }

    private static final class ListOutcome {
        private final boolean generalAdmin;
        private final boolean projectExists;
        private final boolean authorized;
        private final long totalElements;
        private final ManagedKnowledgeEntry entry;

        private ListOutcome(boolean generalAdmin, boolean projectExists, boolean authorized, long totalElements,
                            ManagedKnowledgeEntry entry) {
            this.generalAdmin = generalAdmin; this.projectExists = projectExists; this.authorized = authorized;
            this.totalElements = totalElements; this.entry = entry;
        }
    }

    private static final class ReadOutcome {
        private final boolean generalAdmin;
        private final boolean projectExists;
        private final boolean authorized;
        private final ManagedKnowledgeEntry entry;

        private ReadOutcome(boolean generalAdmin, boolean projectExists, boolean authorized,
                            ManagedKnowledgeEntry entry) {
            this.generalAdmin = generalAdmin;
            this.projectExists = projectExists;
            this.authorized = authorized;
            this.entry = entry;
        }
    }

    private static final class CreateOutcome {
        private final boolean generalAdmin;
        private final boolean projectExists;
        private final boolean authorized;
        private final ManagedKnowledgeEntry entry;

        private CreateOutcome(boolean generalAdmin, boolean projectExists, boolean authorized,
                              ManagedKnowledgeEntry entry) {
            this.generalAdmin = generalAdmin;
            this.projectExists = projectExists;
            this.authorized = authorized;
            this.entry = entry;
        }
    }
}
