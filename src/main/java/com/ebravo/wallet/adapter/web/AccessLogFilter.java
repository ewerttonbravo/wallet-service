package com.ebravo.wallet.adapter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs one line per request with Elastic Common Schema fields: method, path, status and duration,
 * and the correlation id the response carries. Outermost, so that every refusal is logged, a
 * malformed correlation id included. Never the query string, the body or the token.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var start = System.nanoTime();
        // A failure the chain throws is answered by the container's error page, with 500.
        var status = HttpStatus.INTERNAL_SERVER_ERROR.value();
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            var path = request.getRequestURI();
            var line = LOG.atInfo();
            // Absent when the correlation id itself was refused: the rejected value is never written.
            var correlationId = response.getHeader(CorrelationIdFilter.HEADER);
            if (correlationId != null) {
                line = line.addKeyValue(CorrelationIdFilter.LOG_KEY, correlationId);
            }
            line.addKeyValue("http.request.method", request.getMethod())
                    .addKeyValue("url.path", path)
                    .addKeyValue("http.response.status_code", status)
                    .addKeyValue("event.duration", System.nanoTime() - start)
                    .log("{} {} {}", request.getMethod(), path, status);
        }
    }
}
