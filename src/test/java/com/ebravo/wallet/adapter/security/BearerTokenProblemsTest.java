package com.ebravo.wallet.adapter.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

/**
 * No route asks for more than a valid token today, so nothing reaches {@code 403} end to end; this
 * pins the answer a refused authorization will get once one does.
 */
class BearerTokenProblemsTest {

    private final BearerTokenProblems problems = new BearerTokenProblems(JsonMapper.builder().build());

    @Test
    void describesARefusedAuthorizationAsProblemDetails() throws Exception {
        var request = new MockHttpServletRequest("GET", "/wallets/me");
        var response = new MockHttpServletResponse();

        problems.handle(request, response, new AccessDeniedException("Access Denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).isEqualTo("""
                {"title":"Forbidden","status":403,\
                "detail":"The token does not grant access to this resource","instance":"/wallets/me"}""");
    }
}
