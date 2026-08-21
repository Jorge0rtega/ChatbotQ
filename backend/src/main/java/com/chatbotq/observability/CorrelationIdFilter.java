package com.chatbotq.observability;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public final class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_NAME);
        String correlationId = isSafe(incoming) ? incoming : UUID.randomUUID().toString();
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    private static boolean isSafe(String value) {
        return value != null && SAFE_VALUE.matcher(value).matches();
    }
}
