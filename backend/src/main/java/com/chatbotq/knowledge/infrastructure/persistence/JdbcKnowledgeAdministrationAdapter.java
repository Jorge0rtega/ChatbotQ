package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntry;
import com.chatbotq.knowledge.application.model.ManagedKnowledgeEntryPage;
import com.chatbotq.knowledge.application.port.KnowledgeAdministrationPort;
import com.chatbotq.knowledge.application.usecase.ForbiddenKnowledgeAdministrationException;
import com.chatbotq.knowledge.application.usecase.KnowledgeEntryNotFoundException;
import com.chatbotq.knowledge.application.usecase.KnowledgeVersionConflictException;
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

public class JdbcKnowledgeAdministrationAdapter implements KnowledgeAdministrationPort {
    private final JdbcTemplate jdbc;

    public JdbcKnowledgeAdministrationAdapter(JdbcTemplate jdbc) {
        if (jdbc == null) throw new IllegalArgumentException("jdbc must not be null");
        this.jdbc = jdbc;
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
                + "and upr.role='PROJECT_ADMIN')) authorized), inserted as (insert into knowledge_entry "
                + "(id,project_id,question,answer,external_id,active,embedding_input_token_upper_bound,created_by,updated_by,created_at,updated_at) "
                + "select ?,t.id,?,?,?,?,?,?,?,?,? from target t cross join decision d where d.authorized "
                + "returning id,project_id,question,answer,external_id,active,embedding_status,embedding_revision,embedding_attempt_count,embedding_last_attempt_at,embedding_last_error_code,embedding_last_error_message,version,"
                + "created_at,updated_at) select d.general_admin,d.project_exists,d.authorized,i.id,i.project_id,"
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
                + "updated as (update knowledge_entry u set question=?,answer=?,external_id=?,active=?,updated_by=?,updated_at=?,version=u.version+1,embedding_input_token_upper_bound=case when u.question is distinct from ? then ? else u.embedding_input_token_upper_bound end,embedding_status=case when u.question is distinct from ? then 'PENDING' else u.embedding_status end,embedding_revision=case when u.question is distinct from ? then u.embedding_revision+1 else u.embedding_revision end,embedding=case when u.question is distinct from ? then null else u.embedding end,embedded_at=case when u.question is distinct from ? then null else u.embedded_at end,embedding_attempt_count=case when u.question is distinct from ? then 0 else u.embedding_attempt_count end,embedding_last_attempt_at=case when u.question is distinct from ? then null else u.embedding_last_attempt_at end,embedding_last_error_code=case when u.question is distinct from ? then null else u.embedding_last_error_code end,embedding_last_error_message=case when u.question is distinct from ? then null else u.embedding_last_error_message end,embedding_processing_claim_token=case when u.question is distinct from ? then null else u.embedding_processing_claim_token end,embedding_processing_lease_expires_at=case when u.question is distinct from ? then null else u.embedding_processing_lease_expires_at end from entry e where u.id=e.id and u.version=? and (u.question is distinct from ? or u.answer is distinct from ? or u.external_id is distinct from ? or u.active is distinct from ?) and (u.question is not distinct from ? or u.embedding_revision < 9223372036854775807) returning u.*), "
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
