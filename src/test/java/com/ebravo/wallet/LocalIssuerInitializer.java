package com.ebravo.wallet;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Points the service at the local issuer before the context refreshes. A dynamic property
 * registered later would come too late: the JWT decoder is auto-configured only when the issuer
 * URI is already set.
 */
final class LocalIssuerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final LocalIssuer ISSUER = new LocalIssuer();

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ISSUER.start();
        TestPropertyValues.of("spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER.issuerUri())
                .applyTo(context);
        context.getBeanFactory().registerSingleton("localIssuer", ISSUER);
    }
}
