package com.ebravo.wallet.adapter.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;

/**
 * The keys come from the issuer's key set, named in the configuration: discovering it would make
 * concurrent requests queue behind an issuer that does not answer. Without the issuer, tokens from
 * anyone the key set signs for would be accepted. The service refuses to start without either.
 */
class IssuerKeysTest {

    private static final Duration CACHE = Duration.ofMillis(200);
    private static final String KEY_ID = "signing-key";
    private static final String ROTATED_KEY_ID = "rotated-key";
    private static final URL KEY_SET = url("http://issuer/local/jwks");

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

    /** A relative or malformed key set fails at startup, naming the setting to fix. */
    @Test
    void refusesToStartWithAKeySetThatIsNotAUrl() {
        var jwt = jwt("http://issuer/local", "/local/jwks");

        assertThatIllegalStateException()
                .isThrownBy(() -> IssuerKeys.readWithinBounds(jwt))
                .withMessageContaining("jwk-set-uri");
    }

    /**
     * Once the cache expires, keys read before the issuer went down still check tokens: the issuer
     * issues none meanwhile, and the tokens already out are signed by the keys read.
     */
    @Test
    void keepsTheKeysItReadWhileTheIssuerIsDown() throws Exception {
        var issuer = new Issuer();
        var keys = IssuerKeys.keySet(KEY_SET, issuer, CACHE.toMillis());
        assertThat(signingKeys(keys)).hasSize(1);

        issuer.down = true;
        waitFor(CACHE.multipliedBy(2));

        assertThat(signingKeys(keys)).hasSize(1);
        assertThat(issuer.reads).isGreaterThan(1);
    }

    /** Nimbus keeps them for ten times the cache's life, then the outage is reported again. */
    @Test
    void dropsTheKeysOnceTheOutageOutlastsItsBound() throws Exception {
        var issuer = new Issuer();
        var keys = IssuerKeys.keySet(KEY_SET, issuer, CACHE.toMillis());
        assertThat(signingKeys(keys)).hasSize(1);

        issuer.down = true;
        waitFor(CACHE.multipliedBy(12));

        assertThatExceptionOfType(KeySourceException.class).isThrownBy(() -> signingKeys(keys));
    }

    /**
     * A key the service has not read may be one the issuer rotated to just before it went down: the
     * token can be checked only once the issuer answers, so it is not refused as signed by no key.
     */
    @Test
    void cannotCheckAKeyItHasNotReadWhileTheIssuerIsDown() throws Exception {
        var issuer = new Issuer();
        var keys = IssuerKeys.keySet(KEY_SET, issuer, CACHE.toMillis());
        assertThat(signingKeys(keys)).hasSize(1);

        issuer.down = true;

        assertThatExceptionOfType(KeySourceException.class).isThrownBy(() -> keys(keys, ROTATED_KEY_ID));
        assertThat(signingKeys(keys)).hasSize(1);
    }

    /** While the issuer answers, a key it does not publish signs no token of its own. */
    @Test
    void findsNoKeyTheIssuerDoesNotPublish() throws Exception {
        var keys = IssuerKeys.keySet(KEY_SET, new Issuer(), CACHE.toMillis());

        assertThat(keys(keys, ROTATED_KEY_ID)).isEmpty();
    }

    private static List<JWK> signingKeys(JWKSource<SecurityContext> keys) throws KeySourceException {
        return keys(keys, KEY_ID);
    }

    private static List<JWK> keys(JWKSource<SecurityContext> keys, String keyId) throws KeySourceException {
        return keys.get(new JWKSelector(new JWKMatcher.Builder().keyID(keyId).build()), null);
    }

    // Time passing is the input: an expiry never comes early, so a wait past it is deterministic.
    private static void waitFor(Duration time) throws InterruptedException {
        Thread.sleep(time);
    }

    /** An issuer serving one key, until it goes down. */
    private static final class Issuer implements ResourceRetriever {

        private final String keySet;
        private volatile boolean down;
        private volatile int reads;

        Issuer() throws Exception {
            keySet = new JWKSet(new RSAKeyGenerator(2048).keyID(KEY_ID).generate()).toPublicJWKSet().toString();
        }

        @Override
        public Resource retrieveResource(URL url) throws IOException {
            reads++;
            if (down) {
                throw new IOException("The issuer is down");
            }
            return new Resource(keySet, "application/json");
        }
    }

    private static OAuth2ResourceServerProperties.Jwt jwt(String issuerUri, String jwkSetUri) {
        var jwt = new OAuth2ResourceServerProperties().getJwt();
        jwt.setIssuerUri(issuerUri);
        jwt.setJwkSetUri(jwkSetUri);
        return jwt;
    }

    private static URL url(String value) {
        try {
            return URI.create(value).toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
