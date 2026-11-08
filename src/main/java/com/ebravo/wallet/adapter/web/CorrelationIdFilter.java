package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Takes the caller's correlation id, or generates one, and returns it on every response. Runs
 * before security so that rejected requests can be traced too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Correlation-Id";
    static final String ATTRIBUTE = CorrelationId.class.getName();

    // Fixed text: the rejected value is never echoed back.
    private static final String MALFORMED = """
            {"type":"about:blank","title":"Bad Request","status":400,\
            "detail":"X-Correlation-Id must be 1 to 64 letters, digits, '.', '_', ':' or '-'"}""";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var header = request.getHeader(HEADER);
        CorrelationId correlationId;
        try {
            correlationId = header == null ? CorrelationId.generate() : new CorrelationId(header);
        } catch (IllegalArgumentException malformed) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            // JSON is UTF-8 by definition and takes no charset parameter, like Spring's own Problem Details.
            response.getOutputStream().write(MALFORMED.getBytes(StandardCharsets.UTF_8));
            return;
        }
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId.value());
        chain.doFilter(request, response);
    }
}
