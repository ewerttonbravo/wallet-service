package com.ebravo.wallet.adapter.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;

/**
 * The keys come from the issuer's key set, named in the configuration: discovering it would make
 * concurrent requests queue behind an issuer that does not answer. Without the issuer, tokens from
 * anyone the key set signs for would be accepted. The service refuses to start without either.
 */
class IssuerKeysTest {

    @Test
    void acceptsTheIssuerAndItsKeySet() {
        var jwt = jwt("http://issuer/local", "http://issuer/local/jwks");

        assertThatCode(() -> IssuerKeys.requireIssuerAndKeySet(jwt)).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWithoutTheKeySet() {
        var jwt = jwt("http://issuer/local", null);

        assertThatIllegalStateException()
                .isThrownBy(() -> IssuerKeys.requireIssuerAndKeySet(jwt))
                .withMessageContaining("jwk-set-uri");
    }

    @Test
    void refusesToStartWithoutTheIssuer() {
        var jwt = jwt(null, "http://issuer/local/jwks");

        assertThatIllegalStateException()
                .isThrownBy(() -> IssuerKeys.requireIssuerAndKeySet(jwt))
                .withMessageContaining("issuer-uri");
    }

    private static OAuth2ResourceServerProperties.Jwt jwt(String issuerUri, String jwkSetUri) {
        var jwt = new OAuth2ResourceServerProperties().getJwt();
        jwt.setIssuerUri(issuerUri);
        jwt.setJwkSetUri(jwkSetUri);
        return jwt;
    }
}
