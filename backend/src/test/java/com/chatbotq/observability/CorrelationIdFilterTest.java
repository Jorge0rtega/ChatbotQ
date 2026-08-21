package com.chatbotq.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationIdFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void reusesValidIncomingCorrelationIdAndClearsMdcAfterRequest() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "client-request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> valueInsideChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> valueInsideChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertEquals("client-request-123", valueInsideChain.get());
        assertEquals("client-request-123", response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void generatesSafeCorrelationIdWhenIncomingValueIsInvalid() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "valor con espacios\ninyectado");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> valueInsideChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> valueInsideChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertEquals(generated, valueInsideChain.get());
        assertTrue(generated.matches("[a-f0-9\\-]{36}"));
        assertFalse(generated.contains("inyectado"));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
