package com.ebravo.wallet.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebravo.wallet.IntegrationTest;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
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
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@IntegrationTest
class ApiContractApiTest {

    // Actuator endpoints are documented too, but they are not this service's controllers.
    private static final String ACTUATOR = "/actuator/";

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
                .isEqualTo(contract());
    }

    /** The contract is one small document: a range of it is no use, and would be a status to document. */
    @Test
    void servesTheWholeContractToARangeRequest() throws Exception {
        var served = mvc.get().uri("/openapi.yaml").header(HttpHeaders.RANGE, "bytes=0-9").exchange();

        assertThat(served).hasStatusOk();
        assertThat(served.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(contract());
    }

    @Test
    void contractParsesWithoutMessages() throws IOException {
        var parsed = parse();

        assertThat(parsed.getMessages()).isEmpty();
        assertThat(parsed.getOpenAPI().getOpenapi()).isEqualTo("3.1.0");
    }

    /** The validator ignores parameters declared on a path item, so they would go unchecked there. */
    @Test
    void declaresParametersOnOperationsOnly() throws IOException {
        var paths = parse().getOpenAPI().getPaths();

        assertThat(paths).isNotEmpty();
        assertThat(paths.entrySet())
                .filteredOn(path -> path.getValue().getParameters() != null)
                .extracting(Map.Entry::getKey)
                .isEmpty();
    }

    /** A route no test calls would escape the check on exchanges; this one sees every route. */
    @Test
    void documentsEveryOperationTheServiceMaps() throws IOException {
        var mapped = mappedOperations();

        // An empty scan would match an empty contract; the service maps at least this one.
        assertThat(mapped).contains("POST /wallets");
        assertThat(documentedOperations()).isEqualTo(mapped);
    }

    private Set<String> mappedOperations() {
        return mappings.getHandlerMethods().entrySet().stream()
                .filter(handler -> handler.getValue().getBeanType().getPackageName().startsWith("com.ebravo.wallet"))
                .flatMap(handler -> handler.getKey().getMethodsCondition().getMethods().stream()
                        .flatMap(method -> handler.getKey().getPatternValues().stream()
                                .map(path -> method.name() + " " + path)))
                .collect(Collectors.toSet());
    }

    private static Set<String> documentedOperations() throws IOException {
        return parse().getOpenAPI().getPaths().entrySet().stream()
                .filter(path -> !path.getKey().startsWith(ACTUATOR))
                .flatMap(path -> path.getValue().readOperationsMap().keySet().stream()
                        .map(PathItem.HttpMethod::name)
                        .map(method -> method + " " + path.getKey()))
                .collect(Collectors.toSet());
    }

    private static SwaggerParseResult parse() throws IOException {
        return new OpenAPIV3Parser().readContents(contract());
    }

    private static String contract() throws IOException {
        return new ClassPathResource("openapi.yaml").getContentAsString(StandardCharsets.UTF_8);
    }
}
