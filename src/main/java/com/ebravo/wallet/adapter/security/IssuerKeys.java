package com.ebravo.wallet.adapter.security;

import static java.util.stream.Collectors.toUnmodifiableSet;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.util.StringUtils;

/**
 * The issuer's signing keys, read from the key set the configuration names, within a bounded time:
 * an issuer that cannot answer costs a request two seconds to read the keys, after at most two
 * waiting for another request's read, instead of the 30 Spring reads for and the 15 Nimbus waits.
 */
final class IssuerKeys {

    /** Like the wait for a database connection: an answer, a 503 if need be, in about two seconds. */
    private static final Duration WAIT = Duration.ofSeconds(2);
    private static final int WAIT_MILLIS = (int) WAIT.toMillis();
    /** What Spring Security asks a key set for. */
    private static final String KEY_SET_TYPES = "application/json, application/jwk-set+json";

    private IssuerKeys() {
    }

    /**
     * Keeps the decoder Spring Boot builds, with its issuer and audience validation, and swaps only
     * where its keys come from. The cache's life is Spring Security's; the waits are bounded, and the
     * keys read outlast an issuer outage (see {@link #keySet(URL, ResourceRetriever, long)}).
     */
    static JwkSetUriJwtDecoderBuilderCustomizer readWithinBounds(OAuth2ResourceServerProperties.Jwt jwt) {
        requireIssuerAndKeySet(jwt);
        var algorithms = jwt.getJwsAlgorithms().stream().map(JWSAlgorithm::parse).collect(toUnmodifiableSet());
        var keys = keySet(jwt.getJwkSetUri());
        return builder -> builder.jwtProcessorCustomizer(
                processor -> processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithms, keys)));
    }

    private static JWKSource<SecurityContext> keySet(String uri) {
        URL url;
        try {
            url = URI.create(uri).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("spring.security.oauth2.resourceserver.jwt.jwk-set-uri is not a URL", e);
        }
        var socket = new DefaultResourceRetriever(WAIT_MILLIS, WAIT_MILLIS, JWKSourceBuilder.DEFAULT_HTTP_SIZE_LIMIT);
        socket.setHeaders(Map.of("Accept", List.of(KEY_SET_TYPES)));
        return keySet(url, new DeadlineRetriever(socket, WAIT), JWKSourceBuilder.DEFAULT_CACHE_TIME_TO_LIVE);
    }

    /**
     * The cache's time to live is Spring Security's in production; a test shortens it. A key the
     * service has not read is looked for at the issuer; while the issuer is down, Nimbus serves the
     * keys already read instead, and a token signed by a key it rotated to would be refused as signed
     * by none. That token cannot be checked yet, like any token when no key can be read.
     */
    static JWKSource<SecurityContext> keySet(URL url, ResourceRetriever retriever, long cacheTimeToLive) {
        var issuer = new IssuerReads(retriever);
        var keys = JWKSourceBuilder.<SecurityContext>create(url, issuer)
                .cache(cacheTimeToLive, WAIT_MILLIS)
                // Keys read keep checking tokens through an issuer outage, for ten times the cache's
                // life (Nimbus's default): fifty minutes, about the life of the tokens already out.
                .outageTolerant(true)
                .refreshAheadCache(false)
                .rateLimited(false)
                .build();
        return (selector, context) -> {
            var found = keys.get(selector, context);
            if (found.isEmpty() && issuer.down) {
                throw new KeySourceException("No key read matches, and the issuer cannot be read to look for it");
            }
            return found;
        };
    }

    /** Whether the issuer answered its latest read. */
    private static final class IssuerReads implements ResourceRetriever {

        private final ResourceRetriever retriever;
        private volatile boolean down;

        IssuerReads(ResourceRetriever retriever) {
            this.retriever = retriever;
        }

        @Override
        public Resource retrieveResource(URL url) throws IOException {
            try {
                var keySet = retriever.retrieveResource(url);
                down = false;
                return keySet;
            } catch (IOException | RuntimeException failure) {
                down = true;
                throw failure;
            }
        }
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
