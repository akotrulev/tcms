package com.tcms.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Replaces Spring Boot's auto-configured {@link org.springframework.orm.jpa.JpaTransactionManager}
 * with the company-aware one, so isolation is established for every transaction in the
 * application rather than for the ones someone remembered to annotate.
 *
 * <p>Defining a {@link PlatformTransactionManager} bean backs off Boot's
 * {@code JpaTransactionManager} auto-configuration, which is conditional on a missing
 * bean of that type.
 */
@Configuration(proxyBeanMethods = false)
public class TenancyConfiguration {

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new CompanyAwareTransactionManager(entityManagerFactory);
    }
}
