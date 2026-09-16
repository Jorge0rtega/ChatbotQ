package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ClaimedKnowledgeImportExecution;
import com.chatbotq.knowledge.application.port.KnowledgeImportExecutionPort;
import com.chatbotq.knowledge.application.usecase.ForbiddenKnowledgeAdministrationException;
import com.chatbotq.knowledge.application.usecase.ImportExecutionNotReadyException;
import com.chatbotq.knowledge.application.usecase.KnowledgeImportJobNotFoundException;
import com.chatbotq.projects.application.usecase.ProjectNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public class JdbcKnowledgeImportExecutionAdapter implements KnowledgeImportExecutionPort {
    private final JdbcTemplate jdbc;

    public JdbcKnowledgeImportExecutionAdapter(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc must not be null");
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ClaimedKnowledgeImportExecution claimReadyForExecution(UUID actorId, UUID projectId, UUID jobId) {
        Actor actor = lockActor(actorId);
        String projectStatus = lockProject(projectId, actor.generalAdmin);
        authorize(actor, projectId, projectStatus);
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
