package com.ebravo.wallet.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class ApiContractApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void servesTheContractWithoutAToken() throws Exception {
        var served = mvc.get().uri("/openapi.yaml").exchange();

        assertThat(served).hasStatusOk().hasContentType("application/yaml");
        // The very file the tests check exchanges against, so what is served is what is verified.
        var contract = new ClassPathResource("openapi.yaml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(served.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .startsWith("openapi: 3.1.0")
                .isEqualTo(contract);
    }
}
