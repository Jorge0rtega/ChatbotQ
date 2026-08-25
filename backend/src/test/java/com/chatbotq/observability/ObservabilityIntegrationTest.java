package com.chatbotq.observability;

import com.chatbotq.ChatbotQApplication;
import com.chatbotq.identityaccess.domain.AdminUser;
import com.chatbotq.identityaccess.infrastructure.security.JwtAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = ChatbotQApplication.class,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "chatbotq.security.jwt.secret=test-fixture-signing-material-with-thirty-two-bytes"
    }
)
@AutoConfigureMockMvc
class ObservabilityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Autowired
    private JwtAccessTokenService jwt;

    @Autowired
    private Clock clock;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private PlatformTransactionManager transactionManager;

    @Test
    void exposesBaseMetricsWithCorrelationId() throws Exception {
        mockMvc.perform(get("/actuator/metrics").header(CorrelationIdFilter.HEADER_NAME, "metrics-check-1"))
            .andExpect(status().isOk())
            .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "metrics-check-1"));
    }

    @Test
    void publicHealthIgnoresInvalidAndExpiredBearerTokens() throws Exception {
        Instant now = clock.instant();
        AdminUser user = AdminUser.create(UUID.randomUUID(), "health-token@example.com", "hash", true,
            now.minusSeconds(1200));
        user.activate(now.minusSeconds(1100));
        String expired = jwt.issue(user, now.minusSeconds(600));

        mockMvc.perform(get("/actuator/health").header("Authorization", "Bearer invalid"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health").header("Authorization", "Bearer " + expired))
            .andExpect(status().isOk());
    }

    @Test
    void structuredConsolePatternIncludesCorrelationId() {
        String pattern = environment.getProperty("logging.pattern.console", "");

        assertTrue(pattern.contains("timestamp="));
        assertTrue(pattern.contains("correlation_id=%X{correlationId:-none}"));
    }
}
