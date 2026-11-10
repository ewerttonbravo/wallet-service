package com.ebravo.wallet.adapter.security;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain api(HttpSecurity http, JsonMapper json) {
        var problems = new BearerTokenProblems(json);
        return http
                .authorizeHttpRequests(requests -> requests
                        // The probes on the application's port, /livez and /readyz.
                        .requestMatchers(EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, HealthEndpoint.class))
                        .permitAll()
                        // The management port, which only the platform reaches: health and metrics.
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                        // The contract grants nothing by itself, and clients read it before holding a token.
                        .requestMatchers(HttpMethod.GET, "/openapi.yaml").permitAll()
                        // The error page renders a failure already decided, such as an issuer that cannot be
                        // reached; refusing it would answer that failure with 401, as if the token were bad.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer tokens only, no cookies: there is no ambient credential for CSRF to abuse.
                .csrf(CsrfConfigurer::disable)
                .build();
    }
}
