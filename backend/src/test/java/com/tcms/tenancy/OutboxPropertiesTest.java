package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The outbox's configuration refuses the values that are silently wrong.
 *
 * <p>No Spring context: these are the record's own compact constructor, and every one of them is a
 * rule Bean Validation cannot express — {@code @Positive} is not defined over {@link Duration}
 * ({@code CaseHealthProperties} records paying for that), and the lease rule is a relation between
 * two fields, which no field-level annotation can see at all.
 */
@DisplayName("Outbox properties — the combinations that must not start")
class OutboxPropertiesTest {

    private static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1));

    private static OutboxProperties with(Duration interval, Duration lease) {
        return new OutboxProperties(true, interval, 100, lease, 8, BACKOFF);
    }

    @Test
    @DisplayName("a lease shorter than the interval is refused — it double-delivers everything slow")
    void leaseShorterThanIntervalIsRefused() {
        // THE configuration defect this class exists for, because it is invisible until load: the
        // row becomes due again while the first attempt is still running, so the next tick claims
        // it and two handlers run. Handlers are required to be idempotent precisely so that this is
        // survivable — shipping a default that guarantees it would be spending that contract.
        assertThatThrownBy(() -> with(Duration.ofMinutes(1), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delivered twice");
    }

    @Test
    @DisplayName("a lease equal to the interval is allowed — the boundary is inclusive")
    void leaseEqualToIntervalIsAllowed() {
        assertThat(with(Duration.ofMinutes(1), Duration.ofMinutes(1)).lease())
                .isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("a non-positive interval is refused")
    void nonPositiveIntervalIsRefused() {
        assertThatThrownBy(() -> with(Duration.ZERO, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interval");
    }

    @Test
    @DisplayName("a batch size below one is refused — it is a loop that never delivers")
    void batchSizeBelowOneIsRefused() {
        assertThatThrownBy(() ->
                new OutboxProperties(true, Duration.ofMinutes(1), 0, Duration.ofMinutes(5), 8, BACKOFF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    @DisplayName("an attempt ceiling below one is refused — nothing would ever be delivered once")
    void maxAttemptsBelowOneIsRefused() {
        assertThatThrownBy(() ->
                new OutboxProperties(true, Duration.ofMinutes(1), 100, Duration.ofMinutes(5), 0, BACKOFF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-attempts");
    }

    @Test
    @DisplayName("the backoff's last entry repeats for every attempt beyond the list")
    void backoffSaturatesRatherThanOverflowing() {
        // The arithmetic that would otherwise be an IndexOutOfBounds on attempt 6 of a
        // five-entry list — thrown inside the FAILURE path, which is the one path that has to
        // survive, and it would take the row's error message down with it.
        OutboxProperties properties = new OutboxProperties(
                true, Duration.ofMinutes(1), 100, Duration.ofMinutes(5), 8,
                List.of(Duration.ofMinutes(1), Duration.ofMinutes(5)));

        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backoffAfter(3)).as("saturates").isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backoffAfter(99)).isEqualTo(Duration.ofMinutes(5));
    }
}
