package com.ebravo.wallet.adapter.security;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.util.StringUtils;

/** The issuer's signing keys, read from the key set the configuration names. */
final class IssuerKeys {

    private IssuerKeys() {
    }

    /**
     * Discovery would serialize concurrent requests behind an issuer that does not answer, so the key
     * set is named; and without the issuer, any token the key set signs would be accepted.
     */
    static void requireIssuerAndKeySet(OAuth2ResourceServerProperties.Jwt jwt) {
        if (!StringUtils.hasText(jwt.getIssuerUri())) {
            throw new IllegalStateException(
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri must name the trusted token issuer");
        }
        if (!StringUtils.hasText(jwt.getJwkSetUri())) {
            throw new IllegalStateException(
                    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri must name the issuer's key set: "
                            + "the service does not discover it");
        }
    }
}
