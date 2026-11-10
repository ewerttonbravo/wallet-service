package com.ebravo.wallet.adapter.persistence;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

@Configuration(proxyBeanMethods = false)
class PersistenceConfiguration {

    /** A wallet query and a ledger query that lose the database fail alike (see {@link ResourceFailureTranslator}). */
    @Bean
    static BeanPostProcessor readLostConnectionsAsResourceFailures() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HibernateJpaVendorAdapter adapter) {
                    adapter.getJpaDialect().setJdbcExceptionTranslator(new ResourceFailureTranslator());
                }
                return bean;
            }
        };
    }
}
