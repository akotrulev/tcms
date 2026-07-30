package com.tcms;

import com.tcms.provisioning.ProvisionCompanyCommand;
import java.time.Clock;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

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
        // The same jar is the server and the operator CLI. A CLI invocation must run
        // NON-WEB: with the web starter on the classpath the default is a servlet
        // container, and a provisioning run would then do its work correctly and never
        // exit — the operator sees a command that "hangs" after succeeding. Decided from
        // the raw arguments because the application type is fixed before the context (and
        // therefore before ApplicationArguments) exists.
        boolean cli = ProvisionCompanyCommand.isRequestedBy(args);
        new SpringApplicationBuilder(TcmsApplication.class)
                .web(cli ? WebApplicationType.NONE : WebApplicationType.SERVLET)
                // The banner is six lines of ASCII art between the operator and the usage
                // message they are trying to read. Kept for the server, where it stamps
                // the version into the log.
                .bannerMode(cli ? Banner.Mode.OFF : Banner.Mode.CONSOLE)
                .run(args);
    }

    /**
     * One clock, injected everywhere rather than {@code Instant.now()} at call sites, so
     * anything that stamps a time is testable — and so a provisioning run stamps
     * <em>one</em> instant across the company, the membership, the grant and the audit
     * entry rather than five that differ by microseconds and cannot be correlated.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
