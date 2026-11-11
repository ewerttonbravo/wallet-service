package com.ebravo.wallet.adapter.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.WebApplicationContextUtils;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    /**
     * The management port, which only the platform reaches: health and metrics, with no token checked,
     * since a scraper may send one of its own, such as a service account's.
     */
    @Bean
    @Order(1)
    SecurityFilterChain management(HttpSecurity http, Environment environment) {
        requireManagementPortOfItsOwn(environment);
        return http
                .securityMatcher(SecurityConfiguration::onTheManagementPort)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(CsrfConfigurer::disable)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http, JsonMapper json, OAuth2ResourceServerProperties resourceServer) {
        var problems = new BearerTokenProblems(json);
        return http
                .authorizeHttpRequests(requests -> requests
                        // The probes on the application's port, /livez and /readyz.
                        .requestMatchers(EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, HealthEndpoint.class))
                        .permitAll()
                        // The contract grants nothing by itself, and clients read it before holding a token.
                        .requestMatchers(HttpMethod.GET, "/openapi.yaml").permitAll()
                        // The error page renders a failure already decided, such as a decoder that failed
                        // while checking a token; refusing it would answer that failure with 401, as if the
                        // token were bad.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .jwt(Customizer.withDefaults())
                        // Spring Security serves RFC 9728 metadata and offers no switch to turn it off; by
                        // default it claims certificate-bound tokens, which this service does not enforce.
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(
                                resource -> resource
                                        .authorizationServer(resourceServer.getJwt().getIssuerUri())
                                        .tlsClientCertificateBoundAccessTokens(false)))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        // The configurer offers no setter for it: a token that cannot be checked is 503.
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(problems);
                                return filter;
                            }
                        }))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer tokens only, no cookies: there is no ambient credential for CSRF to abuse.
                .csrf(CsrfConfigurer::disable)
                .build();
    }

    /**
     * The issuer's keys, read within a bounded time, so an issuer that cannot answer gets a quick 503.
     * Built at startup whatever else is configured, it refuses to start without the issuer and its key set.
     */
    @Bean
    JwkSetUriJwtDecoderBuilderCustomizer issuerKeys(OAuth2ResourceServerProperties resourceServer) {
        return IssuerKeys.readWithinBounds(resourceServer.getJwt());
    }

    /**
     * Every request the management server receives, so a path it does not serve is not found there,
     * instead of falling through to the API's chain and its token check.
     */
    private static boolean onTheManagementPort(HttpServletRequest request) {
        var context = WebApplicationContextUtils.getRequiredWebApplicationContext(request.getServletContext());
        return WebServerNamespace.MANAGEMENT.equals(WebServerNamespace.from(context));
    }

    /** On the API's port, the actuator would answer anyone without a token. */
    static void requireManagementPortOfItsOwn(Environment environment) {
        if (ManagementPortType.get(environment) == ManagementPortType.SAME) {
            throw new IllegalStateException(
                    "The actuator answers without a token: management.server.port must be a port of its own, "
                            + "reached by the platform alone, not the API's, or -1 to turn it off");
        }
    }
}
