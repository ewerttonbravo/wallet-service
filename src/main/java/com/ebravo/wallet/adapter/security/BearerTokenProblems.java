package com.ebravo.wallet.adapter.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a refused token with the bearer challenge (RFC 6750) and a Problem Details body, as every
 * other error is answered. The body is the same whatever was wrong: the reason stays in the header.
 */
final class BearerTokenProblems implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint challenge = new BearerTokenAuthenticationEntryPoint();
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
