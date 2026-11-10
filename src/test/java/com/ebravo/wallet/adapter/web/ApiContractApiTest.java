package com.ebravo.wallet.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.IntegrationTest;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@IntegrationTest
class ApiContractApiTest {

    // Actuator endpoints are documented too, but they are not this service's controllers.
    private static final String ACTUATOR = "/actuator/";
    private static final String CONTRACT = read();
    private static final SwaggerParseResult PARSED = new OpenAPIV3Parser().readContents(CONTRACT);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void servesTheContractWithoutAToken() throws Exception {
        var served = mvc.get().uri("/openapi.yaml").exchange();

        assertThat(served).hasStatusOk().hasContentType("application/yaml");
        // The very file the tests check exchanges against, so what is served is what is verified.
        assertThat(served.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .startsWith("openapi: 3.1.0")
                .isEqualTo(CONTRACT);
    }

    /** The contract is one small document: a range of it is no use, and would be a status to document. */
    @Test
    void servesTheWholeContractToARangeRequest() throws Exception {
        var served = mvc.get().uri("/openapi.yaml").header(HttpHeaders.RANGE, "bytes=0-9").exchange();

        assertThat(served).hasStatusOk();
        assertThat(served.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(CONTRACT);
    }

    @Test
    void contractParsesWithoutMessages() {
        assertThat(PARSED.getMessages()).isEmpty();
        assertThat(PARSED.getOpenAPI().getOpenapi()).isEqualTo("3.1.0");
    }

    /** The validator ignores parameters declared on a path item, so they would go unchecked there. */
    @Test
    void declaresParametersOnOperationsOnly() {
        var paths = PARSED.getOpenAPI().getPaths();

        assertThat(paths).isNotEmpty();
        assertThat(paths.entrySet())
                .filteredOn(path -> path.getValue().getParameters() != null)
                .extracting(Map.Entry::getKey)
                .isEmpty();
    }

    /** A route no test calls would escape the check on exchanges; this one sees every route. */
    @Test
    void documentsEveryOperationTheServiceMaps() {
        var mapped = mappedOperations();

        // An empty scan would match an empty contract; the service maps at least this one.
        assertThat(mapped).contains("POST /wallets");
        assertThat(documentedOperations()).isEqualTo(mapped);
    }

    private Set<String> mappedOperations() {
        return mappings.getHandlerMethods().entrySet().stream()
                .filter(handler -> handler.getValue().getBeanType().getPackageName().startsWith("com.ebravo.wallet"))
                .flatMap(handler -> methodsOf(handler.getKey()).stream()
                        .flatMap(method -> handler.getKey().getPatternValues().stream()
                                .map(path -> method + " " + path)))
                .collect(Collectors.toSet());
    }

    // A mapping that names no method serves every one; ANY matches no documented operation.
    private static Set<String> methodsOf(RequestMappingInfo mapping) {
        var methods = mapping.getMethodsCondition().getMethods();
        return methods.isEmpty()
                ? Set.of("ANY")
                : methods.stream().map(RequestMethod::name).collect(Collectors.toSet());
    }

    private static Set<String> documentedOperations() {
        return PARSED.getOpenAPI().getPaths().entrySet().stream()
                .filter(path -> !path.getKey().startsWith(ACTUATOR))
                .flatMap(path -> path.getValue().readOperationsMap().keySet().stream()
                        .map(PathItem.HttpMethod::name)
                        .map(method -> method + " " + path.getKey()))
                .collect(Collectors.toSet());
    }

    private static String read() {
        try {
            return new ClassPathResource("openapi.yaml").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }
}
