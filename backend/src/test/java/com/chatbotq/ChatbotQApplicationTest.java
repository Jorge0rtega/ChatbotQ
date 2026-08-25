package com.chatbotq;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
    classes = ChatbotQApplication.class,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "chatbotq.security.jwt.secret=test-fixture-signing-material-with-thirty-two-bytes"
    }
)
class ChatbotQApplicationTest {

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private PlatformTransactionManager transactionManager;

    @Test
    void contextLoads() {
    }
}
