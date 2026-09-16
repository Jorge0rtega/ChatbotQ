package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportRow;
import com.chatbotq.knowledge.application.port.KnowledgeImportExecutionPort;
import com.chatbotq.knowledge.application.port.KnowledgeImportRowClaimPort;
import com.chatbotq.knowledge.application.usecase.ForbiddenKnowledgeAdministrationException;
import com.chatbotq.knowledge.application.usecase.ImportExecutionNotReadyException;
import com.chatbotq.knowledge.application.usecase.KnowledgeImportJobNotFoundException;
import com.chatbotq.projects.application.usecase.ProjectNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;

public class JdbcKnowledgeImportExecutionAdapter implements KnowledgeImportExecutionPort, KnowledgeImportRowClaimPort {
    private final JdbcTemplate jdbc;
    private final Runnable afterCurrentJobValidated;
    private final Runnable beforeExecutionJobLockAttempt;
    private final IntConsumer executionJobLockAttemptObserver;

    public JdbcKnowledgeImportExecutionAdapter(JdbcTemplate jdbc) {
        this(jdbc, () -> { }, () -> { }, null);
    }

    JdbcKnowledgeImportExecutionAdapter(JdbcTemplate jdbc, Runnable afterCurrentJobValidated) {
        this(jdbc, afterCurrentJobValidated, () -> { }, null);
    }

    JdbcKnowledgeImportExecutionAdapter(JdbcTemplate jdbc, Runnable afterCurrentJobValidated, Runnable beforeExecutionJobLockAttempt) {
        this(jdbc, afterCurrentJobValidated, beforeExecutionJobLockAttempt, null);
    }

    JdbcKnowledgeImportExecutionAdapter(JdbcTemplate jdbc, Runnable afterCurrentJobValidated, IntConsumer executionJobLockAttemptObserver) {
        this(jdbc, afterCurrentJobValidated, () -> { }, executionJobLockAttemptObserver);
    }

    private JdbcKnowledgeImportExecutionAdapter(JdbcTemplate jdbc, Runnable afterCurrentJobValidated,
                                                 Runnable beforeExecutionJobLockAttempt, IntConsumer executionJobLockAttemptObserver) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc must not be null");
        if (afterCurrentJobValidated == null) throw new IllegalArgumentException("afterCurrentJobValidated must not be null");
        if (beforeExecutionJobLockAttempt == null) throw new IllegalArgumentException("beforeExecutionJobLockAttempt must not be null");
        this.jdbc = jdbc;
        this.afterCurrentJobValidated = afterCurrentJobValidated;
        this.beforeExecutionJobLockAttempt = beforeExecutionJobLockAttempt;
        this.executionJobLockAttemptObserver = executionJobLockAttemptObserver;
    }

    @Override
    @Transactional
    public Optional<ClaimedKnowledgeImportRow> claimNextValidRow(ClaimedKnowledgeImportExecution jobClaim) {
        if (jobClaim == null) throw new IllegalArgumentException("jobClaim must not be null");
        List<UUID> currentJobs = jdbc.query("select id from knowledge_import_job where id=? and project_id=? "
                + "and status='PROCESSING' and execution_claim_token=? and execution_lease_expires_at>clock_timestamp() for key share",
            (rs, rowNum) -> rs.getObject("id", UUID.class), jobClaim.getJobId(), jobClaim.getProjectId(), jobClaim.getClaimToken());
        if (currentJobs.isEmpty()) return Optional.empty();
        afterCurrentJobValidated.run();
        List<ClaimedKnowledgeImportRow> claims = jdbc.query(
            "with current_job as materialized (select id from knowledge_import_job where id=? and project_id=? "
                + "and status='PROCESSING' and execution_claim_token=? and execution_lease_expires_at>clock_timestamp() for no key update), "
                + "candidate as materialized (select r.import_job_id,r.row_number from knowledge_import_row r "
                + "where r.import_job_id=? and r.status='VALID' order by r.row_number for update skip locked limit 1), "
                + "claimed as (update knowledge_import_row r set status='PROCESSING',attempt_count=r.attempt_count+1 "
                + "from candidate c join current_job j on j.id=c.import_job_id where r.import_job_id=c.import_job_id "
                + "and r.row_number=c.row_number and r.status='VALID' "
                + "returning r.import_job_id,r.row_number,r.question,r.answer,r.external_id,r.active) "
                + "select import_job_id,row_number,question,answer,external_id,active from claimed",
            (rs, rowNum) -> new ClaimedKnowledgeImportRow(rs.getObject("import_job_id", UUID.class), rs.getInt("row_number"),
                rs.getString("question"), rs.getString("answer"), rs.getString("external_id"), rs.getBoolean("active")),
            jobClaim.getJobId(), jobClaim.getProjectId(), jobClaim.getClaimToken(), jobClaim.getJobId());
        return claims.isEmpty() ? Optional.empty() : Optional.of(claims.get(0));
    }

    @Override
    @Transactional
    public ClaimedKnowledgeImportExecution claimReadyForExecution(UUID actorId, UUID projectId, UUID jobId) {
        Actor actor = lockActor(actorId);
        String projectStatus = lockProject(projectId, actor.generalAdmin);
        authorize(actor, projectId, projectStatus);
        beforeExecutionJobLockAttempt.run();
        if (executionJobLockAttemptObserver != null) executionJobLockAttemptObserver.accept(
            jdbc.queryForObject("select pg_backend_pid()", Integer.class));
        List<String> statuses = jdbc.query("select status from knowledge_import_job where id=? and project_id=? for update",
            (rs, rowNum) -> rs.getString(1), jobId, projectId);
        if (statuses.isEmpty()) throw new KnowledgeImportJobNotFoundException();
        List<ClaimedKnowledgeImportExecution> claims = jdbc.query(
            "update knowledge_import_job set status='PROCESSING',execution_claim_token=gen_random_uuid(),"
                + "execution_lease_expires_at=clock_timestamp()+interval '5 minutes',"
                + "execution_attempt_count=execution_attempt_count+1,last_execution_error_code=null "
                + "where id=? and project_id=? and execution_attempt_count<5 and (status='READY' "
                + "or (status='PROCESSING' and execution_lease_expires_at<=clock_timestamp())) "
                + "returning id,project_id,strategy,execution_claim_token",
            (rs, rowNum) -> new ClaimedKnowledgeImportExecution(rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getString("strategy"),
                rs.getObject("execution_claim_token", UUID.class)), jobId, projectId);
        if (claims.isEmpty()) throw new ImportExecutionNotReadyException();
        return claims.get(0);
    }

    private Actor lockActor(UUID actorId) {
        List<Actor> actors = jdbc.query("select is_general_admin from admin_user where id=? and status='ACTIVE' "
                + "and (locked_until is null or locked_until<=clock_timestamp()) for update",
            (rs, rowNum) -> new Actor(actorId, rs.getBoolean(1)), actorId);
        if (actors.isEmpty()) throw new ForbiddenKnowledgeAdministrationException();
        return actors.get(0);
    }

    private String lockProject(UUID projectId, boolean generalAdmin) {
        List<String> projects = jdbc.query("select status from project where id=? for update", (rs, rowNum) -> rs.getString(1), projectId);
        if (projects.isEmpty()) {
            if (generalAdmin) throw new ProjectNotFoundException();
            throw new ForbiddenKnowledgeAdministrationException();
        }
        return projects.get(0);
    }

    private void authorize(Actor actor, UUID projectId, String projectStatus) {
        if (!"ACTIVE".equals(projectStatus)) {
            throw new ForbiddenKnowledgeAdministrationException();
        }
        if (!actor.generalAdmin && jdbc.queryForObject(
            "select count(*) from user_project_role where user_id=? and project_id=? and role='PROJECT_ADMIN'",
            Integer.class, actor.id, projectId) == 0) {
            throw new ForbiddenKnowledgeAdministrationException();
        }
    }

    private static final class Actor {
        private final UUID id;
        private final boolean generalAdmin;

        private Actor(UUID id, boolean generalAdmin) {
            this.id = id;
            this.generalAdmin = generalAdmin;
        }
    }
}
