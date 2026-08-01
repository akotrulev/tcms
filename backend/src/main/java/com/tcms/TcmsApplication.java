package com.tcms;

import com.tcms.provisioning.OperatorCommands;
import java.time.Clock;
import java.time.Duration;
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

    /** The resolution of a Postgres {@code timestamptz} — see {@link #clock()}. */
    private static final Duration POSTGRES_TIMESTAMP_RESOLUTION = Duration.ofNanos(1_000);

    public static void main(String[] args) {
        // The same jar is the server and the operator CLI. A CLI invocation must run
        // NON-WEB: with the web starter on the classpath the default is a servlet
        // container, and a provisioning run would then do its work correctly and never
        // exit — the operator sees a command that "hangs" after succeeding. Decided from
        // the raw arguments because the application type is fixed before the context (and
        // therefore before ApplicationArguments) exists.
        boolean cli = OperatorCommands.isRequestedBy(args);
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
     *
     * <p><strong>Truncated to microseconds, because that is what a Postgres
     * {@code timestamptz} stores.</strong> Every instant this stamps is written to one.
     * An untruncated {@code Instant} carries nanoseconds, so any value that is both
     * returned in memory and later read back is <em>two different values</em> differing in
     * the last three digits, and no type says so. {@code InvitationService.issueIfPending}
     * is where that surfaced: it returns the in-memory {@code now.plus(validity)} when it
     * mints and the database round-trip when an invitation is already outstanding, so
     * {@code provision-company} printed an expiry that was not the one stored and a client
     * comparing {@code expiresAt} across two calls watched it move.
     *
     * <p>The truncation is a <em>no-op on macOS</em>, where {@code Clock.systemUTC()}
     * already yields only microseconds — which is why the defect survived seven consecutive
     * red CI runs that no local gate could reproduce (handoff §58). {@code ClockResolutionTest}
     * therefore asserts what this bean <em>is</em> and not only what it does, because the
     * behavioural assertion cannot fail on the machine that runs the suite.
     *
     * <p>{@code Duration.ofNanos(1)} here would be a silent no-op — {@link Clock#tick}
     * returns the base clock unchanged when there is nothing to truncate to. Pinned by a
     * test, since it differs from the working value by one character.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), POSTGRES_TIMESTAMP_RESOLUTION);
    }
}
