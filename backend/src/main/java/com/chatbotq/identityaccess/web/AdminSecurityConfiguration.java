package com.chatbotq.identityaccess.web;

import com.chatbotq.identityaccess.application.port.AdminUserRepository;
import com.chatbotq.identityaccess.infrastructure.security.JwtAccessTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AdminSecurityConfiguration {
    @Bean
    JwtAdminAuthenticationFilter jwtAdminAuthenticationFilter(JwtAccessTokenService tokens,
            AdminUserRepository users, Clock clock) {
        return new JwtAdminAuthenticationFilter(tokens, users, clock);
    }

    @Bean
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http,
                                                  JwtAdminAuthenticationFilter jwtFilter) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, failure) ->
            JwtAdminAuthenticationFilter.unauthorized(response);
        http.csrf().disable()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and().exceptionHandling().authenticationEntryPoint(entryPoint)
            .and().authorizeRequests()
                .antMatchers("/api/admin/auth/login", "/api/admin/auth/refresh", "/api/admin/auth/logout",
                    "/api/admin/auth/complete-password-reset").permitAll()
                .antMatchers("/api/admin/**").authenticated()
                .anyRequest().permitAll()
            .and().addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
