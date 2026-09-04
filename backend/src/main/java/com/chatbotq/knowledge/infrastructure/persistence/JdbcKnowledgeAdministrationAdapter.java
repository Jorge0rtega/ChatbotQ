package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;
import com.chatbotq.knowledge.application.port.KnowledgeAdministrationPort;
import com.chatbotq.knowledge.application.usecase.ForbiddenKnowledgeAdministrationException;
import com.chatbotq.projects.application.usecase.ProjectNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JdbcKnowledgeAdministrationAdapter implements KnowledgeAdministrationPort {
    private final JdbcTemplate jdbc;

    public JdbcKnowledgeAdministrationAdapter(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc must not be null");
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ManagedKnowledgeEntry create(UUID actorId, UUID projectId, UUID entryId, String question, String answer,
                                        String externalId, boolean active, Instant now) {
        List<CreateOutcome> outcomes = jdbc.query(
            "with actor as materialized (select u.id,u.is_general_admin from admin_user u where u.id=? "
                + "and u.status='ACTIVE' and (u.locked_until is null or u.locked_until<=current_timestamp)), "
                + "target as materialized (select p.id,p.status from project p where p.id=?), "
                + "decision as materialized (select exists (select 1 from actor a where a.is_general_admin) "
                + "general_admin, exists (select 1 from target) project_exists, exists (select 1 from actor a "
                + "join target p on p.status='ACTIVE' where a.is_general_admin or exists (select 1 "
                + "from user_project_role upr where upr.user_id=a.id and upr.project_id=p.id "
                + "and upr.role='PROJECT_ADMIN')) authorized), inserted as (insert into knowledge_entry "
                + "(id,project_id,question,answer,external_id,active,created_by,updated_by,created_at,updated_at) "
                + "select ?,t.id,?,?,?,?,?,?,?,? from target t cross join decision d where d.authorized "
                + "returning id,project_id,question,answer,external_id,active,embedding_status,embedding_revision,"
                + "created_at,updated_at) select d.general_admin,d.project_exists,d.authorized,i.id,i.project_id,"
                + "i.question,i.answer,i.external_id,i.active,i.embedding_status,i.embedding_revision,i.created_at,"
                + "i.updated_at from decision d left join inserted i on true",
            (rs, rowNum) -> mapOutcome(rs), actorId, projectId, entryId, question, answer, externalId, active,
            actorId, actorId, Timestamp.from(now), Timestamp.from(now));
        CreateOutcome outcome = outcomes.get(0);
        if (!outcome.authorized) {
            if (outcome.generalAdmin && !outcome.projectExists) throw new ProjectNotFoundException();
            throw new ForbiddenKnowledgeAdministrationException();
        }
        return outcome.entry;
    }

    private static CreateOutcome mapOutcome(ResultSet rs) throws SQLException {
        boolean authorized = rs.getBoolean("authorized");
        ManagedKnowledgeEntry entry = null;
        if (authorized && rs.getObject("id") != null) {
            entry = new ManagedKnowledgeEntry((UUID) rs.getObject("id"), (UUID) rs.getObject("project_id"),
                rs.getString("question"), rs.getString("answer"), rs.getString("external_id"),
                rs.getBoolean("active"), rs.getString("embedding_status"), rs.getLong("embedding_revision"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        }
        return new CreateOutcome(rs.getBoolean("general_admin"), rs.getBoolean("project_exists"), authorized, entry);
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
