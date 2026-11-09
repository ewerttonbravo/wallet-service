package com.ebravo.wallet;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/** Every exchange of every integration test is checked against the contract the service serves. */
@TestConfiguration(proxyBeanMethods = false)
class ApiContractValidation {

    @Bean
    MockMvcBuilderCustomizer checkEveryExchangeAgainstTheContract() {
        var contract = ApiContract.load();
        return builder -> builder.alwaysDo(result -> contract.verify(result.getRequest(), result.getResponse()));
    }
}
