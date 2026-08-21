package com.chatbotq.observability;

import com.chatbotq.ChatbotQApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = ChatbotQApplication.class,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
    }
)
@AutoConfigureMockMvc
class ObservabilityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void exposesBaseMetricsWithCorrelationId() throws Exception {
        mockMvc.perform(get("/actuator/metrics").header(CorrelationIdFilter.HEADER_NAME, "metrics-check-1"))
            .andExpect(status().isOk())
            .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "metrics-check-1"));
    }

    @Test
    void structuredConsolePatternIncludesCorrelationId() {
        String pattern = environment.getProperty("logging.pattern.console", "");

        assertTrue(pattern.contains("timestamp="));
        assertTrue(pattern.contains("correlation_id=%X{correlationId:-none}"));
    }
}
