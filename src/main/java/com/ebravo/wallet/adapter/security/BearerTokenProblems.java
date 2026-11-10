package com.ebravo.wallet.adapter.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a refused token, or a token refused access, with the bearer challenge (RFC 6750) and a
 * Problem Details body, as every other error is answered. The body is the same whatever was wrong:
 * the reason stays in the header. A token that could not be checked at all, because the issuer's
 * keys cannot be read, is not refused: it is answered {@code 503}, as an unavailable database is.
 */
final class BearerTokenProblems implements AuthenticationEntryPoint, AccessDeniedHandler, AuthenticationFailureHandler {

    private static final Logger LOG = LoggerFactory.getLogger(BearerTokenProblems.class);

    private final AuthenticationEntryPoint challenge = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler denial = new BearerTokenAccessDeniedHandler();
    private final JsonMapper json;

    BearerTokenProblems(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException refusal)
            throws IOException, ServletException {
        challenge.commence(request, response, refusal);
        write(request, response, "A valid bearer token is required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refusal)
            throws IOException, ServletException {
        denial.handle(request, response, refusal);
        write(request, response, "The token does not grant access to this resource");
    }

    /**
     * Spring raises {@link AuthenticationServiceException} over a {@link JwtException} when the
     * decoder fails for another reason than the token, such as keys that cannot be read. Any other
     * service failure, which the filter also catches from further down the chain, goes to the error
     * page, as Spring's own handler sends it. Any other failure is the token's, and is challenged.
     */
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failure) throws IOException, ServletException {
        if (failure instanceof AuthenticationServiceException && failure.getCause() instanceof JwtException) {
            LOG.error("The token could not be checked", failure);
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
            write(request, response, "The token cannot be checked now, retry the request");
        } else if (failure instanceof AuthenticationServiceException) {
            throw failure;
        } else {
            commence(request, response, failure);
        }
    }

    /** The status is the one the bearer handler chose, which is not always 401 or 403. */
    private void write(HttpServletRequest request, HttpServletResponse response, String detail) throws IOException {
        var status = HttpStatus.valueOf(response.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(),
                new Problem(status.getReasonPhrase(), status.value(), detail, request.getRequestURI()));
    }

    /** The fields Spring writes for the other errors; {@code type} is left out, as {@code about:blank}. */
    private record Problem(String title, int status, String detail, String instance) {
    }
}
