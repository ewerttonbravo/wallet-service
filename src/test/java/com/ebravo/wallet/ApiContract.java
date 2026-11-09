package com.ebravo.wallet;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.MockMvcRequest;
import com.atlassian.oai.validator.mockmvc.MockMvcResponse;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.SimpleValidationReportFormat;
import com.atlassian.oai.validator.report.ValidationReport;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The OpenAPI contract the service serves, as a check on what the tests send and receive. */
public final class ApiContract {

    private final OpenApiInteractionValidator validator;

    private ApiContract(OpenApiInteractionValidator validator) {
        this.validator = validator;
    }

    public static ApiContract load() {
        return new ApiContract(OpenApiInteractionValidator.createFor("openapi.yaml")
                // Spring Security's test tokens authenticate without an Authorization header.
                .withLevelResolver(LevelResolver.create()
                        .withLevel("validation.request.security", ValidationReport.Level.IGNORE)
                        .build())
                .build());
    }

    /**
     * Every response must be one the contract describes. The request is checked only when it
     * succeeded: error tests send requests the contract refuses on purpose, but a success to such a
     * request means the contract and the service disagree.
     */
    public void verify(MockHttpServletRequest request, MockHttpServletResponse response) {
        var report = HttpStatus.valueOf(response.getStatus()).is2xxSuccessful()
                ? validator.validate(MockMvcRequest.of(request), MockMvcResponse.of(response))
                : validator.validateResponse(
                        request.getRequestURI(), Request.Method.valueOf(request.getMethod()), MockMvcResponse.of(response));
        if (report.hasErrors()) {
            throw new AssertionError("%s %s answered %d outside the API contract:%n%s".formatted(
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    SimpleValidationReportFormat.getInstance().apply(report)));
        }
    }
}
