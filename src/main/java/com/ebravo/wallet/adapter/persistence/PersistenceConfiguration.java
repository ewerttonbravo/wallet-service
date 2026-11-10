package com.ebravo.wallet.adapter.persistence;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

@Configuration(proxyBeanMethods = false)
class PersistenceConfiguration {

    /**
     * Hibernate reads PostgreSQL's shutdown states (57P01 on a restart or a failover) as generic
     * errors. Spring's SQLState table, the one {@code JdbcClient} already uses for the ledger, reads
     * them as a database out of reach, so a wallet query and a ledger query that fail alike are
     * answered alike. States the table does not know, such as a lock timeout, keep Hibernate's reading.
     */
    @Bean
    static BeanPostProcessor translateJpaFailuresLikeJdbc() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HibernateJpaVendorAdapter adapter) {
                    adapter.getJpaDialect().setJdbcExceptionTranslator(new SQLExceptionSubclassTranslator());
                }
                return bean;
            }
        };
    }
}
