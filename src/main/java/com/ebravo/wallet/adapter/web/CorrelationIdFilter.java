package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Takes the caller's correlation id, or generates one, returns it on every response and puts it on
 * every line logged while the request runs. Runs before security so that rejected requests can be
 * traced too, and inside the access log, which reads it from the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class CorrelationIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Correlation-Id";
    static final String ATTRIBUTE = "com.ebravo.wallet.correlationId";
    static final String LOG_KEY = "correlationId";

    // Fixed text: the rejected value is never echoed back.
    private static final String MALFORMED = "X-Correlation-Id must be 1 to 64 letters, digits, '.', '_', ':' or '-'";

    private final JsonMapper json;

    CorrelationIdFilter(JsonMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var header = request.getHeader(HEADER);
        CorrelationId correlationId;
        try {
            correlationId = header == null ? CorrelationId.generate() : new CorrelationId(header);
        } catch (IllegalArgumentException malformed) {
            var status = HttpStatus.BAD_REQUEST;
            response.setStatus(status.value());
            // JSON is UTF-8 by definition and takes no charset parameter, like Spring's own Problem Details.
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(),
                    new Problem(status.getReasonPhrase(), status.value(), MALFORMED, request.getRequestURI()));
            return;
        }
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId.value());
        // Virtual threads are not pooled, but the servlet container's may be: never leave it behind.
        try (var _ = MDC.putCloseable(LOG_KEY, correlationId.value())) {
            chain.doFilter(request, response);
        }
    }

    /**
     * The fields Spring MVC writes for every other error; {@code type} is left out, as
     * {@code about:blank}. The filter answers before MVC, so it cannot use MVC's converters.
     */
    private record Problem(String title, int status, String detail, String instance) {
    }
}
