package com.ebravo.wallet.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();

    /** A client that hangs up mid-response got the status already sent, not a server error. */
    @Test
    void logsTheStatusAlreadySentWhenTheClientHangsUp(CapturedOutput output) {
        var request = new MockHttpServletRequest("GET", "/wallets/me/transactions");
        var response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (_, sent) -> {
            sent.flushBuffer();
            throw new IOException("Broken pipe");
        })).isInstanceOf(IOException.class);

        assertThat(output.getOut()).contains("GET /wallets/me/transactions 200").doesNotContain(" 500");
    }
}
