package com.ebravo.wallet.adapter.web;

import static com.ebravo.wallet.Fixtures.anOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.ebravo.wallet.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One structured line per request, in Elastic Common Schema, tied to the request by its correlation id. */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AccessLogTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvcTester mvc;

    @Test
    void logsTheRequestWithItsCorrelationId(CapturedOutput output) {
        var correlationId = "log-" + UUID.randomUUID();

        assertThat(mvc.get().uri("/wallets/me/balance?at=2026-01-01T00:00:00Z")
                .with(jwt().jwt(token -> token.subject(anOwner().value())))
                .header("X-Correlation-Id", correlationId))
                .hasStatus(HttpStatus.NOT_FOUND);

        var line = accessLineOf(output, correlationId);
        assertThat(line.at("/log/level").asString()).isEqualTo("INFO");
        assertThat(line.at("/http/request/method").asString()).isEqualTo("GET");
        assertThat(line.at("/url/path").asString()).isEqualTo("/wallets/me/balance");
        assertThat(line.at("/http/response/status_code").asInt()).isEqualTo(404);
        assertThat(line.at("/event/duration").asLong()).isPositive();
        assertThat(line.at("/message").asString()).isEqualTo("GET /wallets/me/balance 404");
    }

    @Test
    void logsARefusedTokenToo(CapturedOutput output) {
        var correlationId = "log-" + UUID.randomUUID();

        assertThat(mvc.get().uri("/wallets/me").header("X-Correlation-Id", correlationId))
                .hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(accessLineOf(output, correlationId).at("/http/response/status_code").asInt()).isEqualTo(401);
    }

    @Test
    void logsARefusedCorrelationIdWithoutIt(CapturedOutput output) {
        var path = "/wallets/me/transactions/" + UUID.randomUUID();

        assertThat(mvc.get().uri(path).header("X-Correlation-Id", "has space")).hasStatus(HttpStatus.BAD_REQUEST);

        var line = accessLines(output).stream()
                .filter(it -> it.at("/url/path").asString().equals(path))
                .findFirst().orElseThrow();
        assertThat(line.at("/http/response/status_code").asInt()).isEqualTo(400);
        assertThat(line.has("correlationId")).isFalse();
        assertThat(output.getOut()).doesNotContain("has space");
    }

    private static JsonNode accessLineOf(CapturedOutput output, String correlationId) {
        return accessLines(output).stream()
                .filter(it -> it.path("correlationId").asString("").equals(correlationId))
                .findFirst().orElseThrow(() -> new AssertionError("No access line for " + correlationId));
    }

    private static List<JsonNode> accessLines(CapturedOutput output) {
        return output.getOut().lines()
                .filter(line -> line.startsWith("{") && line.contains("\"logger\":\"com.ebravo.wallet.adapter.web.AccessLogFilter\""))
                .map(JSON::readTree)
                .toList();
    }
}
