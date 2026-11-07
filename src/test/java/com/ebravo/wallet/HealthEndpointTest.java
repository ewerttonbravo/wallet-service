package com.ebravo.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class HealthEndpointTest {

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void reportsUpWithoutAuthentication(String path) {
        assertThat(mvc.get().uri(path))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("UP");
    }
}
