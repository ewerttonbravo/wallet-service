package com.ebravo.wallet.adapter.security;

import static com.ebravo.wallet.Fixtures.anOwner;
import static com.ebravo.wallet.LocalIssuer.AUDIENCE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import com.ebravo.wallet.IntegrationTest;
import com.ebravo.wallet.LocalIssuer;
import com.ebravo.wallet.domain.OwnerId;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class JwtAuthenticationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private LocalIssuer issuer;

    @Test
    void identifiesTheCallerBySubjectOfAnIssuedToken() {
        var alice = issuer.tokenFor(anOwner(), AUDIENCE);
        var bob = issuer.tokenFor(anOwner(), AUDIENCE);

        assertThat(mvc.post().uri("/wallets").header(AUTHORIZATION, bearer(alice))).hasStatus(HttpStatus.CREATED);
        assertThat(mvc.get().uri("/wallets/me").header(AUTHORIZATION, bearer(alice))).hasStatusOk();
        assertThat(mvc.get().uri("/wallets/me").header(AUTHORIZATION, bearer(bob))).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsATokenIssuedToAnotherAudience() {
        assertRejected(issuer.tokenFor(anOwner(), "another-service"));
    }

    @Test
    void rejectsATokenFromAnUntrustedIssuer() {
        assertRejected(issuer.untrustedTokenFor(anOwner()));
    }

    @Test
    void rejectsATokenWhoseClaimsWereAltered() {
        assertRejected(withSubject(issuer.tokenFor(anOwner(), AUDIENCE), anOwner()));
    }

    // A fresh owner has no wallet: an accepted token answers 404, so 401 can only mean rejection.
    private void assertRejected(String token) {
        var result = mvc.get().uri("/wallets/me").header(AUTHORIZATION, bearer(token)).exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result.getResponse().getHeader(WWW_AUTHENTICATE)).contains("error=\"invalid_token\"");
    }

    /** Swaps the subject and keeps the original signature, which no longer matches the payload. */
    private static String withSubject(String token, OwnerId owner) {
        var parts = token.split("\\.");
        var claims = new String(Base64.getUrlDecoder().decode(parts[1]), UTF_8)
                .replaceFirst("\"sub\":\"[^\"]+\"", "\"sub\":\"" + owner.value() + "\"");
        var forged = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(UTF_8));
        return String.join(".", parts[0], forged, parts[2]);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
