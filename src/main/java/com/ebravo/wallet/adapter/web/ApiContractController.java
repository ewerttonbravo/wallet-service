package com.ebravo.wallet.adapter.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the OpenAPI contract, written by hand and checked by the tests against every exchange.
 * A static resource would be served as {@code application/octet-stream}, since Spring knows no
 * media type for {@code .yaml}. The body is bytes, not a resource, so a range request gets the
 * whole document rather than a partial one.
 */
@RestController
class ApiContractController {

    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");

    private final byte[] contract;

    ApiContractController() {
        try {
            // Read once at startup: a missing contract fails the service before it takes traffic.
            this.contract = new ClassPathResource("openapi.yaml").getContentAsByteArray();
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }

    @GetMapping("/openapi.yaml")
    ResponseEntity<byte[]> contract() {
        return ResponseEntity.ok().contentType(YAML).body(contract);
    }
}
