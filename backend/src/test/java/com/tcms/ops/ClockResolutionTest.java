package com.tcms.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The application's clock cannot out-resolve the columns it stamps.
 *
 * <p>Every instant this application produces is written to a Postgres {@code timestamptz},
 * which stores <strong>microseconds</strong>. {@code Instant} carries nanoseconds. So an
 * untruncated clock makes any value that is both <em>returned in memory</em> and
 * <em>read back from the database</em> two different values, differing in the last three
 * digits — and nothing in the type system says so.
 *
 * <p>{@code InvitationService.issueIfPending} is where it surfaced: it returns the
 * in-memory {@code now.plus(validity)} when it mints an invitation, and the Postgres
 * round-trip of that same value when one is already outstanding. Consequences were
 * {@code provision-company} printing an expiry that is not the one stored, and a client
 * comparing {@code expiresAt} across two calls watching it move. Handoff §58 carries the
 * full account; the defect was open from 2026-07-31 to 2026-08-02 with two tests
 * {@code @Disabled} over it.
 *
 * <h2>Why this class asserts the bean's identity, which is normally the wrong altitude</h2>
 *
 * <p>Because the behavioural assertion is <strong>structurally unable to fail on the
 * machine this suite is run on</strong>. macOS's {@code Clock.systemUTC()} already yields
 * only microsecond precision — measured on this project's workstation, 20000/20000 samples
 * landing on a whole microsecond — so the round-trip below is lossless there with or
 * without the fix. Linux {@code clock_gettime} gives true nanoseconds. That asymmetry cost
 * seven consecutive red CI runs that no local gate could reproduce, and since 2026-08-02
 * CI no longer runs the suite at all, so <em>this machine is now the only observer</em>.
 *
 * <p>{@link #theClockIsTruncatedToTheResolutionOfTheColumnsItIsWrittenTo()} is therefore
 * the load-bearing test here: it compares the bean against the clock it is supposed to be,
 * and goes red on <em>any</em> platform the moment the truncation is removed. The
 * round-trip test states the property the truncation exists to protect, and is the one
 * that would have caught the original defect on Linux.
 */
@DisplayName("The application clock matches the resolution of the database it writes to")
class ClockResolutionTest extends IntegrationTestBase {

    /**
     * The resolution of a Postgres {@code timestamptz}. Duplicated from
     * {@code TcmsApplication} deliberately: a test that imported the constant under test
     * would assert that a value equals itself and pass for any value.
     */
    private static final Duration POSTGRES_RESOLUTION = Duration.ofNanos(1_000);

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("the clock is truncated to the resolution of the columns it is written to")
    void theClockIsTruncatedToTheResolutionOfTheColumnsItIsWrittenTo() {
        assertThat(clock)
                .as("the one assertion in this class that can go red on macOS, where the "
                        + "product defect this protects against is invisible")
                .isEqualTo(Clock.tick(Clock.systemUTC(), POSTGRES_RESOLUTION));
    }

    @Test
    @DisplayName("a one-nanosecond tick would have been a silent no-op, so the unit matters")
    void aOneNanosecondTickWouldHaveBeenASilentNoOp() {
        // Clock.tick returns the BASE CLOCK UNCHANGED for a one-nanosecond tick — there is
        // nothing to truncate to. So `Clock.tick(systemUTC(), Duration.ofNanos(1))` reads
        // like a fix, compiles, and is exactly the unfixed code. Pinned here because the
        // difference between the working constant and that one is a single character.
        assertThat(Clock.tick(Clock.systemUTC(), Duration.ofNanos(1)))
                .isEqualTo(Clock.systemUTC());
        assertThat(clock)
                .as("an untruncated system clock is the defect, however it is spelled")
                .isNotEqualTo(Clock.systemUTC());
    }

    @Test
    @DisplayName("an instant the application stamps survives a round trip through Postgres")
    void anInstantTheApplicationStampsSurvivesARoundTripThroughPostgres() throws SQLException {
        // The property the truncation exists to protect, and the one that actually broke.
        // NOTE: this passes on macOS with or without the fix — see the class comment. It is
        // here because it is what a Linux run, or a future Linux gate, would fail on.
        List<Instant> stamped = new ArrayList<>();
        for (int sample = 0; sample < 500; sample++) {
            stamped.add(clock.instant());
        }

        assertThat(stamped)
                .as("a stamp Postgres cannot store is a stamp that changes when it is read")
                .allSatisfy(instant -> assertThat(instant.getNano() % 1_000).isZero());

        try (Connection connection = migrationOwnerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TEMPORARY TABLE clock_round_trip (ordinal int, stamped timestamptz)");

            try (var insert = connection.prepareStatement(
                    "INSERT INTO clock_round_trip (ordinal, stamped) VALUES (?, ?)")) {
                for (int i = 0; i < stamped.size(); i++) {
                    insert.setInt(1, i);
                    // Not setObject(Instant): pgjdbc refuses to infer a SQL type for it.
                    insert.setObject(2, stamped.get(i).atOffset(ZoneOffset.UTC));
                    insert.addBatch();
                }
                insert.executeBatch();
            }

            List<Instant> readBack = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(
                    "SELECT stamped FROM clock_round_trip ORDER BY ordinal")) {
                while (rows.next()) {
                    readBack.add(rows.getObject(1, OffsetDateTime.class).toInstant());
                }
            }

            assertThat(readBack)
                    .as("what the application returns in memory and what it reads back out "
                            + "of the database must be the same value")
                    .isEqualTo(stamped);
        }
    }
}
