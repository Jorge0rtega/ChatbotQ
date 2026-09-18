package com.chatbotq.infrastructure.configuration;

import com.chatbotq.identityaccess.application.port.AdminUserAdministrationPort;
import com.chatbotq.identityaccess.application.port.AdminUserIdentityGenerator;
import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.port.CurrentAdminViewPort;
import com.chatbotq.identityaccess.application.port.PasswordHasher;
import com.chatbotq.identityaccess.application.port.ProjectAdministrationDecisionPort;
import com.chatbotq.identityaccess.application.port.ProjectAccessPort;
import com.chatbotq.identityaccess.application.port.RefreshSessionRepository;
import com.chatbotq.identityaccess.application.port.UserProjectAssignmentRepository;
import com.chatbotq.identityaccess.application.usecase.AdministerAdminUsersUseCase;
import com.chatbotq.identityaccess.application.usecase.AdministerUserProjectAssignmentsUseCase;

import com.chatbotq.identityaccess.application.usecase.CanAdministerProjectUseCase;
import com.chatbotq.identityaccess.application.usecase.CreateAdminUserUseCase;
import com.chatbotq.identityaccess.application.usecase.GetCurrentAdminViewUseCase;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcAdminUserAdministrationAdapter;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcAdminUserRepository;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcCurrentAdminViewAdapter;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcProjectAccessPort;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcProjectAdministrationDecisionAdapter;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcUserProjectAssignmentRepository;
import com.chatbotq.identityaccess.infrastructure.security.BCryptPasswordHasher;
import com.chatbotq.infrastructure.identity.UuidAdminUserIdentityGenerator;
import com.chatbotq.infrastructure.identity.UuidKnowledgeEntryIdentityGenerator;
import com.chatbotq.infrastructure.identity.UuidProjectIdentityGenerator;
import com.chatbotq.knowledge.application.port.KnowledgeAdministrationPort;
import com.chatbotq.knowledge.application.port.KnowledgeCsvPreviewPort;
import com.chatbotq.knowledge.application.port.KnowledgeEmbeddingProcessingPort;
import com.chatbotq.knowledge.application.port.KnowledgeImportAdministrationPort;
import com.chatbotq.knowledge.application.port.KnowledgeImportExecutionPort;
import com.chatbotq.knowledge.application.port.KnowledgeImportRowClaimPort;
import com.chatbotq.knowledge.application.port.KnowledgeEntryIdentityGenerator;
import com.chatbotq.knowledge.application.port.KnowledgeImportRowMutationPort;
import com.chatbotq.knowledge.application.usecase.AdministerKnowledgeUseCase;
import com.chatbotq.knowledge.application.usecase.CsvKnowledgePreviewUseCase;
import com.chatbotq.knowledge.application.usecase.AdministerKnowledgeImportsUseCase;
import com.chatbotq.knowledge.application.usecase.ExecuteKnowledgeImportUseCase;
import com.chatbotq.knowledge.application.usecase.ProcessOneCreateOnlyKnowledgeImportRowUseCase;
import com.chatbotq.knowledge.application.usecase.ProcessOneUpsertKnowledgeImportRowUseCase;
import com.chatbotq.knowledge.infrastructure.csv.ApacheCommonsCsvKnowledgeParser;
import com.chatbotq.knowledge.infrastructure.persistence.JdbcKnowledgeAdministrationAdapter;
import com.chatbotq.knowledge.infrastructure.persistence.JdbcKnowledgeEmbeddingProcessingAdapter;
import com.chatbotq.knowledge.infrastructure.persistence.JdbcKnowledgeImportAdministrationAdapter;
import com.chatbotq.knowledge.infrastructure.persistence.JdbcKnowledgeImportExecutionAdapter;
import com.chatbotq.projects.application.port.AllowedOriginIdentityGenerator;
import com.chatbotq.projects.application.port.AllowedOriginRepository;
import com.chatbotq.projects.application.port.ProjectIdentityGenerator;
import com.chatbotq.projects.application.port.ProjectAdministrationPort;
import com.chatbotq.projects.application.port.ProjectRepository;
import com.chatbotq.projects.application.port.ProjectStatusPort;
import com.chatbotq.projects.application.port.ProjectSiteKeyAdministrationPort;
import com.chatbotq.projects.application.usecase.AddAllowedOriginUseCase;
import com.chatbotq.projects.application.usecase.AdministerProjectsUseCase;
import com.chatbotq.projects.application.usecase.CreateProjectUseCase;
import com.chatbotq.projects.application.usecase.ManageProjectSiteKeyUseCase;
import com.chatbotq.projects.infrastructure.persistence.JdbcAllowedOriginRepository;
import com.chatbotq.projects.infrastructure.persistence.JdbcProjectAdministrationAdapter;
import com.chatbotq.projects.infrastructure.persistence.JdbcProjectRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class IdentityProjectInfrastructureConfiguration {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    JdbcProjectRepository projectRepository(JdbcTemplate jdbc) {
        return new JdbcProjectRepository(jdbc);
    }

    @Bean
    JdbcProjectAdministrationAdapter projectAdministrationAdapter(JdbcTemplate jdbc) {
        return new JdbcProjectAdministrationAdapter(jdbc);
    }

    @Bean(name = {"knowledgeAdministrationPort", "knowledgeImportRowMutationPort"})
    JdbcKnowledgeAdministrationAdapter knowledgeAdministrationAdapter(JdbcTemplate jdbc) {
        return new JdbcKnowledgeAdministrationAdapter(jdbc);
    }

    @Bean
    KnowledgeEmbeddingProcessingPort knowledgeEmbeddingProcessingPort(JdbcTemplate jdbc) {
        return new JdbcKnowledgeEmbeddingProcessingAdapter(jdbc);
    }

    @Bean
    KnowledgeImportAdministrationPort knowledgeImportAdministrationPort(JdbcTemplate jdbc) {
        return new JdbcKnowledgeImportAdministrationAdapter(jdbc);
    }

    @Bean
    KnowledgeImportExecutionPort knowledgeImportExecutionPort(JdbcTemplate jdbc) {
        return new JdbcKnowledgeImportExecutionAdapter(jdbc);
    }

    @Bean
    KnowledgeImportRowClaimPort knowledgeImportRowClaimPort(JdbcTemplate jdbc) {
        return new JdbcKnowledgeImportExecutionAdapter(jdbc);
    }

    @Bean
    KnowledgeEntryIdentityGenerator knowledgeEntryIdentityGenerator() {
        return new UuidKnowledgeEntryIdentityGenerator();
    }

    @Bean
    AdministerKnowledgeUseCase administerKnowledgeUseCase(KnowledgeAdministrationPort entries,
                                                           KnowledgeEntryIdentityGenerator identities,
                                                           Clock clock,
                                                           @Value("${chatbotq.embedding.limits.max-input-tokens-per-entry:4000}") int maxInputTokensPerEntry) {
        return new AdministerKnowledgeUseCase(entries, identities, clock, maxInputTokensPerEntry);
    }

    @Bean
    ApacheCommonsCsvKnowledgeParser knowledgeCsvParser(
        @Value("${chatbotq.knowledge.csv.max-raw-bytes:1048576}") int maxRawBytes,
        @Value("${chatbotq.knowledge.csv.max-data-rows:1000}") int maxDataRows,
        @Value("${chatbotq.knowledge.csv.max-columns:4}") int maxColumns,
        @Value("${chatbotq.knowledge.csv.max-cell-encoded-bytes:32768}") int maxCellEncodedBytes) {
        return new ApacheCommonsCsvKnowledgeParser(maxRawBytes, maxDataRows, maxColumns, maxCellEncodedBytes);
    }

    @Bean
    KnowledgeCsvPreviewPort knowledgeCsvPreviewPort(ApacheCommonsCsvKnowledgeParser parser,
        @Value("${chatbotq.embedding.limits.max-input-tokens-per-entry:4000}") int maxInputTokensPerEntry) {
        return new CsvKnowledgePreviewUseCase(parser, maxInputTokensPerEntry);
    }

    @Bean
    AdministerKnowledgeImportsUseCase administerKnowledgeImportsUseCase(KnowledgeCsvPreviewPort preview,
                                                                         KnowledgeImportAdministrationPort jobs,
                                                                         Clock clock) {
        return new AdministerKnowledgeImportsUseCase(preview, jobs, clock);
    }

    @Bean
    ExecuteKnowledgeImportUseCase executeKnowledgeImportUseCase(KnowledgeImportExecutionPort executions,
        @Qualifier("knowledgeImportRowClaimPort") KnowledgeImportRowClaimPort rows, ProcessOneCreateOnlyKnowledgeImportRowUseCase createOnly,
        ProcessOneUpsertKnowledgeImportRowUseCase upsert, ApplicationTransaction transactions) {
        return new ExecuteKnowledgeImportUseCase(executions, rows, createOnly, upsert, transactions);
    }

    @Bean
    ProcessOneCreateOnlyKnowledgeImportRowUseCase processOneCreateOnlyKnowledgeImportRowUseCase(
        KnowledgeImportRowMutationPort mutations, KnowledgeEntryIdentityGenerator identities, Clock clock,
        @Value("${chatbotq.embedding.limits.max-input-tokens-per-entry:4000}") int maxInputTokensPerEntry) {
        return new ProcessOneCreateOnlyKnowledgeImportRowUseCase(mutations, identities, clock, maxInputTokensPerEntry);
    }

    @Bean
    ProcessOneUpsertKnowledgeImportRowUseCase processOneUpsertKnowledgeImportRowUseCase(
        KnowledgeImportRowMutationPort mutations, KnowledgeEntryIdentityGenerator identities, Clock clock,
        @Value("${chatbotq.embedding.limits.max-input-tokens-per-entry:4000}") int maxInputTokensPerEntry) {
        return new ProcessOneUpsertKnowledgeImportRowUseCase(mutations, identities, clock, maxInputTokensPerEntry);
    }

    @Bean
    AllowedOriginRepository allowedOriginRepository(JdbcTemplate jdbc) {
        return new JdbcAllowedOriginRepository(jdbc);
    }

    @Bean
    AdminUserRepository adminUserRepository(JdbcTemplate jdbc) {
        return new JdbcAdminUserRepository(jdbc);
    }

    @Bean
    AdminUserAdministrationPort adminUserAdministrationPort(JdbcTemplate jdbc) {
        return new JdbcAdminUserAdministrationAdapter(jdbc);
    }

    @Bean
    ProjectAccessPort projectAccessPort(JdbcTemplate jdbc) {
        return new JdbcProjectAccessPort(jdbc);
    }

    @Bean
    UserProjectAssignmentRepository userProjectAssignmentRepository(JdbcTemplate jdbc) {
        return new JdbcUserProjectAssignmentRepository(jdbc);
    }

    @Bean
    CurrentAdminViewPort currentAdminViewPort(JdbcTemplate jdbc) {
        return new JdbcCurrentAdminViewAdapter(jdbc);
    }

    @Bean
    ProjectAdministrationDecisionPort projectAdministrationDecisionPort(JdbcTemplate jdbc) {
        return new JdbcProjectAdministrationDecisionAdapter(jdbc);
    }

    @Bean
    UuidProjectIdentityGenerator projectIdentityGenerator() {
        return new UuidProjectIdentityGenerator();
    }

    @Bean
    AdminUserIdentityGenerator adminUserIdentityGenerator() {
        return new UuidAdminUserIdentityGenerator();
    }

    @Bean
    BCryptPasswordHasher passwordHasher(
        @Value("${chatbotq.security.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordHasher(strength);
    }

    @Bean
    CreateProjectUseCase createProjectUseCase(ProjectRepository projects,
                                               ProjectIdentityGenerator identities,
                                               Clock clock) {
        return new CreateProjectUseCase(projects, identities, clock);
    }

    @Bean
    AdministerProjectsUseCase administerProjectsUseCase(ProjectAdministrationPort projects,
                                                         ProjectIdentityGenerator identities,
                                                         Clock clock) {
        return new AdministerProjectsUseCase(projects, identities, clock);
    }

    @Bean
    ManageProjectSiteKeyUseCase manageProjectSiteKeyUseCase(ProjectSiteKeyAdministrationPort siteKeys,
                                                             ProjectIdentityGenerator identities,
                                                             Clock clock) {
        return new ManageProjectSiteKeyUseCase(siteKeys, identities, clock);
    }

    @Bean
    AddAllowedOriginUseCase addAllowedOriginUseCase(ProjectStatusPort projects,
                                                     AllowedOriginRepository origins,
                                                     AllowedOriginIdentityGenerator identities,
                                                     Clock clock) {
        return new AddAllowedOriginUseCase(projects, origins, identities, clock);
    }

    @Bean
    AdministerAdminUsersUseCase administerAdminUsersUseCase(AdminUserAdministrationPort users,
                                                             PasswordHasher passwordHasher,
                                                             AdminUserIdentityGenerator identities,
                                                             RefreshSessionRepository sessions,
                                                             ApplicationTransaction transactions,
                                                             Clock clock) {
        return new AdministerAdminUsersUseCase(
            users, passwordHasher, identities, sessions, transactions, clock);
    }

    @Bean
    AdministerUserProjectAssignmentsUseCase administerUserProjectAssignmentsUseCase(
            UserProjectAssignmentRepository assignments, ApplicationTransaction transactions, Clock clock) {
        return new AdministerUserProjectAssignmentsUseCase(assignments, transactions, clock);
    }

    @Bean
    GetCurrentAdminViewUseCase getCurrentAdminViewUseCase(CurrentAdminViewPort views) {
        return new GetCurrentAdminViewUseCase(views);
    }

    @Bean
    CreateAdminUserUseCase createAdminUserUseCase(AdminUserRepository users,
                                                   PasswordHasher passwordHasher,
                                                   AdminUserIdentityGenerator identities,
                                                   Clock clock) {
        return new CreateAdminUserUseCase(users, passwordHasher, identities, clock);
    }


    @Bean
    CanAdministerProjectUseCase canAdministerProjectUseCase(
        ProjectAdministrationDecisionPort decision) {
        return new CanAdministerProjectUseCase(decision);
    }
}
