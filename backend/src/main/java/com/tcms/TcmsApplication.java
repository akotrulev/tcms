package com.tcms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * TCMS — a self-hosted test case management system serving one or more client
 * companies from a single instance.
 *
 * <p>A Spring Modulith monolith. Modules are packages under {@code com.tcms}:
 * {@code tenancy · identity · project · repository · execution · documentation ·
 * reporting · work · shared}. {@code tenancy} is depended on by everything and
 * depends on nothing.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TcmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(TcmsApplication.class, args);
    }
}
