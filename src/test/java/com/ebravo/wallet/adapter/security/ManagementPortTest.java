package com.ebravo.wallet.adapter.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The actuator answers without a token because only the platform reaches its port. On the API's
 * port, anyone would read it: the service refuses to start that way.
 */
class ManagementPortTest {

    @Test
    void acceptsAManagementPortOfItsOwn() {
        var environment = new MockEnvironment().withProperty("management.server.port", "8081");

        assertThatCode(() -> SecurityConfiguration.requireManagementPortOfItsOwn(environment)).doesNotThrowAnyException();
    }

    @Test
    void refusesTheActuatorOnTheApiPort() {
        var environment = new MockEnvironment()
                .withProperty("server.port", "8080")
                .withProperty("management.server.port", "8080");

        assertThatIllegalStateException()
                .isThrownBy(() -> SecurityConfiguration.requireManagementPortOfItsOwn(environment))
                .withMessageContaining("management.server.port");
    }

    @Test
    void refusesAMissingManagementPort() {
        assertThatIllegalStateException()
                .isThrownBy(() -> SecurityConfiguration.requireManagementPortOfItsOwn(new MockEnvironment()));
    }
}
