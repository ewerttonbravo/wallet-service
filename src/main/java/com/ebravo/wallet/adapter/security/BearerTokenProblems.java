package com.ebravo.wallet.adapter.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a refused token, or a token refused access, with the bearer challenge (RFC 6750) and a
 * Problem Details body, as every other error is answered. The body is the same whatever was wrong:
 * the reason stays in the header.
 */
final class BearerTokenProblems implements AuthenticationEntryPoint, AccessDeniedHandler {

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
        write(request, response, HttpStatus.UNAUTHORIZED, "A valid bearer token is required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refusal)
            throws IOException, ServletException {
        denial.handle(request, response, refusal);
        write(request, response, HttpStatus.FORBIDDEN, "The token does not grant access to this resource");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(),
                new Problem(status.getReasonPhrase(), status.value(), detail, request.getRequestURI()));
    }

    /** The fields Spring writes for the other errors; {@code type} is left out, as {@code about:blank}. */
    private record Problem(String title, int status, String detail, String instance) {
    }
}
