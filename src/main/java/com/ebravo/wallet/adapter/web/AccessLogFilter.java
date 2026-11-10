package com.ebravo.wallet.adapter.web;

import com.ebravo.wallet.domain.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs one line per request with Elastic Common Schema fields: method, path, status and duration,
 * and the request's correlation id. Outermost, so that every refusal is logged, a
 * malformed correlation id included. Never the query string, the body or the token.
 */
@Component
// Apart from the framework's own filters at the first orders, so which one wraps the other is defined.
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } catch (Throwable failure) {
            if (response.isCommitted()) {
                // The status was sent: the client hung up, or the failure came too late to change it.
                log(request, response, response.getStatus(), start, null);
            } else {
                // The error page answers it with 500. The container logs the failure too, but only once
                // the request's context is gone, so this line is the one that ties it to the request.
                log(request, response, HttpStatus.INTERNAL_SERVER_ERROR.value(), start, failure);
            }
            throw failure;
        }
        log(request, response, response.getStatus(), start, null);
    }

    private static void log(HttpServletRequest request, HttpServletResponse response, int status, long start,
            Throwable failure) {
        var path = request.getRequestURI();
        var line = LOG.atLevel(failure == null ? Level.INFO : Level.ERROR).setCause(failure);
        // Absent when the correlation id itself was refused: the rejected value is never written.
        if (request.getAttribute(CorrelationIdFilter.ATTRIBUTE) instanceof CorrelationId correlationId) {
            line = line.addKeyValue(CorrelationIdFilter.LOG_KEY, correlationId.value());
        }
        line.addKeyValue("http.request.method", request.getMethod())
                .addKeyValue("url.path", path)
                .addKeyValue("http.response.status_code", status)
                .addKeyValue("event.duration", System.nanoTime() - start)
                .log("{} {} {}", request.getMethod(), path, status);
    }
}
