package com.ebravo.wallet;

import com.ebravo.wallet.domain.OwnerId;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** The token issuer the service trusts in tests: the same image Docker Compose runs locally. */
public final class LocalIssuer extends GenericContainer<LocalIssuer> {

    public static final String AUDIENCE = "wallet-service";

    private static final String IMAGE = "ghcr.io/navikt/mock-oauth2-server:6.1.1";
    private static final String TRUSTED = "local";
    private static final int PORT = 8080;

    LocalIssuer() {
        super(DockerImageName.parse(IMAGE));
        withExposedPorts(PORT);
        waitingFor(Wait.forHttp("/" + TRUSTED + "/.well-known/openid-configuration"));
    }

    public URI issuerUri() {
        return issuerUri(TRUSTED);
    }

    public String tokenFor(OwnerId owner, String audience) {
        return token(TRUSTED, owner, audience);
    }

    /** Same server, different issuer id: a well-formed token the service must not trust. */
    public String untrustedTokenFor(OwnerId owner) {
        return token("untrusted", owner, AUDIENCE);
    }

    private URI issuerUri(String issuerId) {
        return URI.create("http://%s:%d/%s".formatted(getHost(), getMappedPort(PORT), issuerId));
    }

    // With the client credentials grant the issuer copies client_id into sub.
    private String token(String issuerId, OwnerId owner, String audience) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", owner.value());
        form.add("client_secret", "unused");
        form.add("audience", audience);
        return RestClient.create().post()
                .uri(issuerUri(issuerId) + "/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class)
                .accessToken();
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
