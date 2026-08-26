package com.chatbotq.infrastructure.configuration;

import com.chatbotq.identityaccess.application.usecase.CanAdministerProjectUseCase;
import com.chatbotq.identityaccess.infrastructure.security.ProjectAuthorization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;

@Configuration(proxyBeanMethods = false)
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class ProjectMethodSecurityConfiguration {
    @Bean("projectAuthorization")
    ProjectAuthorization projectAuthorization(CanAdministerProjectUseCase authorization) {
        return new ProjectAuthorization(authorization);
    }
}
