package com.ebravo.wallet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * Full application against a real PostgreSQL and the local token issuer. Every integration test
 * uses this exact setup, so they all share one cached context and one set of containers.
 *
 * <p>Every MockMvc exchange is checked against the OpenAPI contract ({@link ApiContractValidation}).
 *
 * <p>API tests build tokens with Spring Security's test support, which skips decoding;
 * {@code JwtAuthenticationTest} covers validation of issued tokens.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ContextConfiguration(initializers = LocalIssuerInitializer.class)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ApiContractValidation.class})
public @interface IntegrationTest {
}
