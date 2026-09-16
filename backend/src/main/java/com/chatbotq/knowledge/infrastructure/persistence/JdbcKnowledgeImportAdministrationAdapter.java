package com.chatbotq.knowledge.infrastructure.persistence;

import com.chatbotq.knowledge.application.model.PersistedKnowledgeImportJob;
import com.chatbotq.knowledge.application.model.PersistedKnowledgeImportRow;
import com.chatbotq.knowledge.application.port.KnowledgeImportAdministrationPort;
import com.chatbotq.knowledge.application.usecase.ForbiddenKnowledgeAdministrationException;
import com.chatbotq.knowledge.application.usecase.KnowledgeImportJobNotFoundException;
import com.chatbotq.projects.application.usecase.ProjectNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JdbcKnowledgeImportAdministrationAdapter implements KnowledgeImportAdministrationPort {
    private static final Pattern ERROR_CODE = Pattern.compile("\\\"([a-z_]+)\\\"");
    private final JdbcTemplate jdbc;
    public JdbcKnowledgeImportAdministrationAdapter(JdbcTemplate jdbc) { if (jdbc == null) throw new IllegalArgumentException("jdbc must not be null"); this.jdbc = jdbc; }

    @Override @Transactional
    public PersistedKnowledgeImportJob create(UUID actorId, PersistedKnowledgeImportJob job) {
        authorizeForWrite(actorId, job.getProjectId());
        jdbc.update("insert into knowledge_import_job(id,project_id,created_by,file_name,strategy,status,total_rows,valid_rows,invalid_rows,imported_rows,error_summary,created_at) values (?,?,?,?,?,?,?,?,?,?,?::jsonb,?)",
            job.getId(), job.getProjectId(), actorId, job.getFileName(), job.getStrategy(), job.getStatus(), job.getTotalRows(), job.getValidRows(), job.getInvalidRows(), job.getImportedRows(), json(job.getErrorSummary()), Timestamp.from(job.getCreatedAt()));
        for (PersistedKnowledgeImportRow row : job.getRows()) jdbc.update("insert into knowledge_import_row(import_job_id,row_number,external_id,question,answer,active,status,errors) values (?,?,?,?,?,?,?,?::jsonb)",
            job.getId(), row.getRowNumber(), row.getExternalId(), row.getQuestion(), row.getAnswer(), row.isActive(), row.getStatus(), json(row.getErrors()));
        return job;
    }

    @Override @Transactional(readOnly = true)
    public PersistedKnowledgeImportJob get(UUID actorId, UUID projectId, UUID jobId, int page, int size) {
        long offset = Math.multiplyExact((long) page, (long) size);
        List<ImportDetailRow> detail = jdbc.query("with actor as materialized (select u.id,u.is_general_admin from admin_user u where u.id=? and u.status='ACTIVE' and (u.locked_until is null or u.locked_until<=current_timestamp)), "
            + "target as materialized (select p.id,p.status from project p where p.id=?), decision as materialized (select exists(select 1 from actor a where a.is_general_admin) general_admin,exists(select 1 from target) project_exists,exists(select 1 from actor a join target p on p.status='ACTIVE' where a.is_general_admin or exists(select 1 from user_project_role upr where upr.user_id=a.id and upr.project_id=p.id and upr.role='PROJECT_ADMIN')) authorized), "
            + "job as materialized (select j.id,j.project_id,j.created_by,j.file_name,j.strategy,j.status,j.total_rows,j.valid_rows,j.invalid_rows,j.imported_rows,j.error_summary,j.created_at from knowledge_import_job j join target t on t.id=j.project_id cross join decision d where j.id=? and d.authorized), "
            + "paged_rows as materialized (select r.row_number,r.question,r.answer,r.external_id,r.active,r.status,r.errors from knowledge_import_row r join job j on j.id=r.import_job_id order by r.row_number limit ? offset ?) "
            + "select d.general_admin,d.project_exists,d.authorized,j.id,j.project_id,j.created_by,j.file_name,j.strategy,j.status,j.total_rows,j.valid_rows,j.invalid_rows,j.imported_rows,j.error_summary,j.created_at,r.row_number,r.question,r.answer,r.external_id,r.active,r.status row_status,r.errors from decision d left join job j on true left join paged_rows r on true order by r.row_number",
            (rs, n) -> new ImportDetailRow(rs.getBoolean("general_admin"), rs.getBoolean("project_exists"), rs.getBoolean("authorized"),
                rs.getObject("id") == null ? null : (UUID) rs.getObject("id"), (UUID) rs.getObject("project_id"), (UUID) rs.getObject("created_by"), rs.getString("file_name"), rs.getString("strategy"), rs.getString("status"), rs.getInt("total_rows"), rs.getInt("valid_rows"), rs.getInt("invalid_rows"), rs.getInt("imported_rows"), rs.getObject("id") == null ? new ArrayList<String>() : codes(rs.getString("error_summary")), rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(), rs.getObject("row_number") == null ? null : rs.getInt("row_number"), rs.getString("question"), rs.getString("answer"), rs.getString("external_id"), rs.getBoolean("active"), rs.getString("row_status"), rs.getObject("row_number") == null ? new ArrayList<String>() : codes(rs.getString("errors"))),
            actorId, projectId, jobId, size, offset);
        ImportDetailRow outcome = detail.get(0);
        if (!outcome.authorized) {
            if (outcome.generalAdmin && !outcome.projectExists) throw new ProjectNotFoundException();
            throw new ForbiddenKnowledgeAdministrationException();
        }
        if (outcome.jobId == null) throw new KnowledgeImportJobNotFoundException();
        List<PersistedKnowledgeImportRow> rows = new ArrayList<PersistedKnowledgeImportRow>();
        for (ImportDetailRow row : detail) if (row.rowNumber != null) rows.add(new PersistedKnowledgeImportRow(row.rowNumber, row.question, row.answer, row.externalId, row.active, row.rowStatus, row.errors));
        return new PersistedKnowledgeImportJob(outcome.jobId, outcome.projectId, outcome.createdBy, outcome.fileName, outcome.strategy, outcome.status, outcome.totalRows, outcome.validRows, outcome.invalidRows, outcome.importedRows, outcome.errorSummary, outcome.createdAt, rows);
    }

    private void authorizeForWrite(UUID actorId, UUID projectId) {
        List<Object[]> actors = jdbc.query("select is_general_admin,status,locked_until from admin_user where id=? for update", (rs, n) -> new Object[] { rs.getBoolean(1), rs.getString(2), rs.getTimestamp(3) }, actorId);
        if (actors.isEmpty()) throw new ForbiddenKnowledgeAdministrationException();
        Object[] actor = actors.get(0); boolean general = (Boolean) actor[0]; String status = (String) actor[1]; Timestamp lockedUntil = (Timestamp) actor[2];
        List<String> projects = jdbc.query("select status from project where id=? for update", (rs, n) -> rs.getString(1), projectId);
        if (projects.isEmpty()) { if (general && "ACTIVE".equals(status) && (lockedUntil == null || !lockedUntil.toInstant().isAfter(Instant.now()))) throw new ProjectNotFoundException(); throw new ForbiddenKnowledgeAdministrationException(); }
        authorizeState(actorId, general, status, lockedUntil, projects.get(0), projectId);
    }

    private void authorizeState(UUID actorId, boolean general, String status, Timestamp lockedUntil, String projectStatus, UUID projectId) {
        if (!"ACTIVE".equals(status) || (lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now())) || !"ACTIVE".equals(projectStatus)) throw new ForbiddenKnowledgeAdministrationException();
        if (!general && jdbc.queryForObject("select count(*) from user_project_role where user_id=? and project_id=? and role='PROJECT_ADMIN'", Integer.class, actorId, projectId) == 0) throw new ForbiddenKnowledgeAdministrationException();
    }
    private static final class ImportDetailRow {
        private final boolean generalAdmin, projectExists, authorized, active;
        private final UUID jobId, projectId, createdBy;
        private final String fileName, strategy, status, question, answer, externalId, rowStatus;
        private final int totalRows, validRows, invalidRows, importedRows;
        private final List<String> errorSummary, errors;
        private final Instant createdAt;
        private final Integer rowNumber;
        private ImportDetailRow(boolean generalAdmin, boolean projectExists, boolean authorized, UUID jobId, UUID projectId,
                                UUID createdBy, String fileName, String strategy, String status, int totalRows, int validRows,
                                int invalidRows, int importedRows, List<String> errorSummary, Instant createdAt, Integer rowNumber,
                                String question, String answer, String externalId, boolean active, String rowStatus, List<String> errors) {
            this.generalAdmin = generalAdmin; this.projectExists = projectExists; this.authorized = authorized;
            this.jobId = jobId; this.projectId = projectId; this.createdBy = createdBy; this.fileName = fileName;
            this.strategy = strategy; this.status = status; this.totalRows = totalRows; this.validRows = validRows;
            this.invalidRows = invalidRows; this.importedRows = importedRows; this.errorSummary = errorSummary;
            this.createdAt = createdAt; this.rowNumber = rowNumber; this.question = question; this.answer = answer;
            this.externalId = externalId; this.active = active; this.rowStatus = rowStatus; this.errors = errors;
        }
    }
    private static String json(List<String> codes) { StringBuilder b = new StringBuilder("["); for (String code : codes) { if (!code.matches("[a-z_]+")) throw new IllegalArgumentException("invalid error code"); if (b.length() > 1) b.append(','); b.append('"').append(code).append('"'); } return b.append(']').toString(); }
    private static List<String> codes(String json) { List<String> result = new ArrayList<String>(); Matcher m = ERROR_CODE.matcher(json); while (m.find()) result.add(m.group(1)); return result; }
}
