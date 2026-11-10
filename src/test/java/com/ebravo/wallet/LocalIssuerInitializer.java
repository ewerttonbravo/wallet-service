package com.ebravo.wallet;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Points the service at the local issuer before the context refreshes. A dynamic property
 * registered later would come too late: the JWT decoder is auto-configured only when its URIs are
 * already set. The key set is named, as the service requires, not discovered.
 */
final class LocalIssuerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final LocalIssuer ISSUER = new LocalIssuer();

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ISSUER.start();
        TestPropertyValues.of(
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER.issuerUri(),
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + ISSUER.issuerUri() + "/jwks")
                .applyTo(context);
        context.getBeanFactory().registerSingleton("localIssuer", ISSUER);
    }
}
