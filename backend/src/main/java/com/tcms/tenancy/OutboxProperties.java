package com.tcms.tenancy;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How hard, how often and how long the outbox tries (decision round 3 #52).
 *
 * <p>Every number here is a deployment's policy rather than this product's opinion, which is the
 * argument {@code CaseHealthProperties} makes at length and it applies unchanged: a literal beside
 * the retry is the version that gets copied to a second call site and then disagrees with itself, in
 * a self-hosted product whose operator cannot reach either copy.
 *
 * @param enabled     whether {@link OutboxScheduler} is registered at all.
 *                    <p><strong>Its real job is the test suite</strong>, exactly as
 *                    {@code tcms.case-health.enabled}'s is: this is a <em>write</em> on a timer over
 *                    every company in the database, and left on under {@code @SpringBootTest} it
 *                    fires partway through a multi-minute run and delivers whichever fixtures have
 *                    been seeded by then — moving state under test classes that never mentioned the
 *                    outbox. The symptom is a test that passes alone and fails in the suite at a
 *                    different point each run, which is §19's first defect and the hardest kind to
 *                    attribute. {@code IntegrationTestBase} turns it off; the dispatcher is driven
 *                    directly instead, which is how a scheduled job's logic should be tested anyway.
 * @param interval    how often the company loop runs. The floor on how late a delivery can be, and
 *                    deliberately not tuned down: the outbox is what makes a delivery <em>certain</em>,
 *                    not what makes it instant, and an operator who needs a shorter tail here is
 *                    usually asking for something an outbox is the wrong shape for.
 * @param batchSize   how many of one company's due events are taken per tick. A bound rather than a
 *                    target: it is what stops a company that generated fifty thousand events
 *                    overnight from holding the loop while every other company waits its turn.
 * @param lease       how long a claimed delivery may run before the row becomes due again. See
 *                    {@link OutboxEntry#claim} — this is the price of a crash, and it is also the
 *                    window in which a very slow handler will be double-delivered, which is why
 *                    {@link OutboxHandler} requires idempotence rather than recommending it.
 * @param maxAttempts how many times delivery is started before the row stops being retried. Counted
 *                    at the claim, so a payload that kills the process still exhausts.
 * @param backoff     the wait after each failed attempt, in order; the last entry repeats for every
 *                    attempt beyond its length. A list rather than a formula because the shape that
 *                    matters — try again almost immediately, then back right off — is two lines of
 *                    configuration and an exponent nobody can read.
 */
@ConfigurationProperties(prefix = "tcms.outbox")
@Validated
public record OutboxProperties(
        boolean enabled,
        @NotNull Duration interval,
        int batchSize,
        @NotNull Duration lease,
        int maxAttempts,
        @NotEmpty List<Duration> backoff) {

    /**
     * Compact constructor, because Bean Validation cannot express any of this.
     *
     * <p>{@code @Positive} is defined over numbers and annotating a {@link Duration} with it fails
     * the <em>context startup</em> with {@code HV000030} rather than the value —
     * {@code CaseHealthProperties} records paying for that discovery, and the rest of these are
     * relations between two fields, which no field-level annotation can see at all.
     */
    public OutboxProperties {
        if (interval != null && (interval.isZero() || interval.isNegative())) {
            throw new IllegalArgumentException(
                    "tcms.outbox.interval must be a positive duration, was " + interval);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "tcms.outbox.batch-size must be at least 1, was " + batchSize);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "tcms.outbox.max-attempts must be at least 1, was " + maxAttempts);
        }
        // A lease shorter than the interval is the configuration that turns every delivery slower
        // than one tick into a double delivery, quietly and only under load: the row becomes due
        // again while the first attempt is still running, the next tick claims it, and both
        // handlers run. Handlers are required to be idempotent precisely so that this is survivable
        // — but shipping a default that guarantees it would be relying on that contract rather than
        // holding it in reserve.
        if (lease != null && interval != null && lease.compareTo(interval) < 0) {
            throw new IllegalArgumentException(
                    "tcms.outbox.lease (" + lease + ") must be at least tcms.outbox.interval ("
                            + interval + "), or every delivery slower than one tick is delivered "
                            + "twice");
        }
        if (backoff != null
                && backoff.stream().anyMatch(wait -> wait.isNegative())) {
            throw new IllegalArgumentException(
                    "tcms.outbox.backoff must not contain a negative duration, was " + backoff);
        }
    }

    /**
     * How long to wait after the given attempt number before trying again.
     *
     * @param attempt the attempt that just failed, 1-based — so attempt 1 takes the first entry
     */
    Duration backoffAfter(int attempt) {
        int index = Math.min(Math.max(attempt, 1), backoff.size()) - 1;
        return backoff.get(index);
    }
}
