package com.ebravo.wallet.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** Every line logged while a request runs carries its correlation id, and no line after it. */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter(JsonMapper.builder().build());

    @Test
    void putsTheCorrelationIdOnLogsWrittenDuringTheRequest() throws Exception {
        var request = new MockHttpServletRequest("GET", "/wallets/me");
        request.addHeader("X-Correlation-Id", "req-42");
        var seen = new AtomicReference<String>();

        filter.doFilter(request, new MockHttpServletResponse(), (_, _) -> seen.set(MDC.get("correlationId")));

        assertThat(seen).hasValue("req-42");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void takesItOffWhenTheRequestFails() {
        var request = new MockHttpServletRequest("GET", "/wallets/me");

        // The failure is the container's to answer, so it goes on unchanged.
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (_, _) -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(MDC.get("correlationId")).isNull();
    }
}
