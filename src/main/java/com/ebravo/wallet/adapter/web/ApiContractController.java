package com.ebravo.wallet.adapter.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the OpenAPI contract, written by hand and checked by the tests against every exchange.
 * A static resource would be served as {@code application/octet-stream}, since Spring knows no
 * media type for {@code .yaml}.
 */
@RestController
class ApiContractController {

    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");
    private static final Resource CONTRACT = new ClassPathResource("openapi.yaml");

    @GetMapping("/openapi.yaml")
    ResponseEntity<Resource> contract() {
        return ResponseEntity.ok().contentType(YAML).body(CONTRACT);
    }
}
