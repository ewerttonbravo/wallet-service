package com.ebravo.wallet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full application against a real PostgreSQL. Every integration test uses this exact setup,
 * so they all share one cached context and one container.
 *
 * <p>Tokens are built by Spring Security's test support and never reach the issuer, which is why
 * it points to a reserved, unresolvable domain.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.invalid")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
