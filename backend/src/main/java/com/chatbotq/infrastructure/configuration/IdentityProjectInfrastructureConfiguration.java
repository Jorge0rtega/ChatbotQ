package com.chatbotq.infrastructure.configuration;

import com.chatbotq.identityaccess.application.port.AdminUserIdentityGenerator;
import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.application.port.PasswordHasher;
import com.chatbotq.identityaccess.application.port.ProjectAccessPort;
import com.chatbotq.identityaccess.application.port.UserProjectAssignmentRepository;
import com.chatbotq.identityaccess.application.usecase.AssignProjectAdminUseCase;
import com.chatbotq.identityaccess.application.usecase.CreateAdminUserUseCase;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcAdminUserRepository;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcProjectAccessPort;
import com.chatbotq.identityaccess.infrastructure.persistence.JdbcUserProjectAssignmentRepository;
import com.chatbotq.identityaccess.infrastructure.security.BCryptPasswordHasher;
import com.chatbotq.infrastructure.identity.UuidAdminUserIdentityGenerator;
import com.chatbotq.infrastructure.identity.UuidProjectIdentityGenerator;
import com.chatbotq.projects.application.port.AllowedOriginIdentityGenerator;
import com.chatbotq.projects.application.port.AllowedOriginRepository;
import com.chatbotq.projects.application.port.ProjectIdentityGenerator;
import com.chatbotq.projects.application.port.ProjectRepository;
import com.chatbotq.projects.application.port.ProjectStatusPort;
import com.chatbotq.projects.application.usecase.AddAllowedOriginUseCase;
import com.chatbotq.projects.application.usecase.CreateProjectUseCase;
import com.chatbotq.projects.infrastructure.persistence.JdbcAllowedOriginRepository;
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
    AllowedOriginRepository allowedOriginRepository(JdbcTemplate jdbc) {
        return new JdbcAllowedOriginRepository(jdbc);
    }

    @Bean
    AdminUserRepository adminUserRepository(JdbcTemplate jdbc) {
        return new JdbcAdminUserRepository(jdbc);
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
    UuidProjectIdentityGenerator projectIdentityGenerator() {
        return new UuidProjectIdentityGenerator();
    }

    @Bean
    AdminUserIdentityGenerator adminUserIdentityGenerator() {
        return new UuidAdminUserIdentityGenerator();
    }

    @Bean
    PasswordHasher passwordHasher(
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
    AddAllowedOriginUseCase addAllowedOriginUseCase(ProjectStatusPort projects,
                                                     AllowedOriginRepository origins,
                                                     AllowedOriginIdentityGenerator identities,
                                                     Clock clock) {
        return new AddAllowedOriginUseCase(projects, origins, identities, clock);
    }

    @Bean
    CreateAdminUserUseCase createAdminUserUseCase(AdminUserRepository users,
                                                   PasswordHasher passwordHasher,
                                                   AdminUserIdentityGenerator identities,
                                                   Clock clock) {
        return new CreateAdminUserUseCase(users, passwordHasher, identities, clock);
    }

    @Bean
    AssignProjectAdminUseCase assignProjectAdminUseCase(AdminUserRepository users,
                                                         ProjectAccessPort projects,
                                                         UserProjectAssignmentRepository assignments,
                                                         Clock clock) {
        return new AssignProjectAdminUseCase(users, projects, assignments, clock);
    }
}
