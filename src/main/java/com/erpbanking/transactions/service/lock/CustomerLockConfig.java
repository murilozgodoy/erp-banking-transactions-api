package com.erpbanking.transactions.service.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.persistence.EntityManager;

/**
 * Escolhe a estratégia de {@link CustomerAccountLock} pelo {@code spring.datasource.url}, uma vez no startup:
 * {@code jdbc:postgresql:} usa o advisory lock do banco; qualquer outro (H2) usa o fallback na JVM.
 */
@Configuration
public class CustomerLockConfig {

    private static final Logger log = LoggerFactory.getLogger(CustomerLockConfig.class);

    @Bean
    public CustomerAccountLock customerAccountLock(@Value("${spring.datasource.url:}") String url,
                                                   EntityManager entityManager) {
        CustomerAccountLock lock = select(url, entityManager);
        log.info("Trava de abertura de conta por cliente: {}", lock.getClass().getSimpleName());
        return lock;
    }

    static CustomerAccountLock select(String url, EntityManager entityManager) {
        if (url != null && url.startsWith("jdbc:postgresql:")) {
            return new PostgresAdvisoryCustomerLock(entityManager);
        }
        return new JvmStripedCustomerLock();
    }
}
