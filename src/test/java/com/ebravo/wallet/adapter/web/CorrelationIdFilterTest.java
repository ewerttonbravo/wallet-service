package com.ebravo.wallet.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Every line logged while a request runs carries its correlation id, and no line after it. */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

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

        try {
            filter.doFilter(request, new MockHttpServletResponse(), (_, _) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // The failure is the container's to answer; only the context left behind matters here.
        }

        assertThat(MDC.get("correlationId")).isNull();
    }
}
