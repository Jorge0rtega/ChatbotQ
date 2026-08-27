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
import com.chatbotq.infrastructure.identity.UuidProjectIdentityGenerator;
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
