package com.tcms.execution;

import java.util.UUID;

/**
 * {@code execution.run_completed}, as this module writes it.
 *
 * <h2>This is the producer half of a pair, and there is no way to make it one type</h2>
 *
 * <p>{@code com.tcms.integration.RunCompletedPayload} declares the same shape on the other side of
 * the boundary. Plan §3 forbids that module from importing anything of this one's — with no
 * exemption for an event type, which {@code ArchitectureRulesTest} checks over every import — so
 * the payload crosses as a JSON document and the compiler is structurally unable to compare the
 * two declarations.
 *
 * <p>{@code Outbox#record}'s javadoc has stated since §131 what the first real caller of the
 * payload arm would owe: <strong>a test on each side pinning the shape</strong>, because a test is
 * what replaces the type check. {@code RunCompletedPayloadTest} exists here and in
 * {@code integration}, and {@code NotificationDeliveryTest} drives one through the other so that
 * updating both pins in step is not enough to hide a drift.
 *
 * <h2>Field names are a wire format</h2>
 *
 * <p>Jackson serializes these component names verbatim into {@code outbox.payload}, and rows
 * written before a rename are still in the table when the consumer that expects the new name
 * deploys. Renaming a component here is a data migration, exactly as renaming an event type is
 * ({@code OutboxEntry#getEventType}).
 *
 * @param eventId  minted per occurrence, and the consumer's idempotency key: outbox delivery is at
 *                 least once, so a notifier needs a stable name for "this event" that survives a
 *                 re-delivery. Deliberately not the outbox row's id, which no handler is given.
 * @param status   always {@code COMPLETED} today. Carried rather than implied by the event name so
 *                 that a subscriber wanting aborted runs too becomes a field's second value rather
 *                 than a second event type.
 * @param total    every execution in the run. The counts do not sum to it — a run holds executions
 *                 with no verdict yet — and a consumer that assumed they did would render a
 *                 percentage nobody can reproduce.
 */
record RunCompletedEvent(
        UUID eventId,
        UUID runId,
        String runKey,
        String runName,
        UUID projectId,
        String status,
        int passed,
        int failed,
        int blocked,
        int total) {

    /** The aggregate noun {@code outbox.aggregate_type} carries for this event. */
    static final String AGGREGATE = "run";

    static RunCompletedEvent of(UUID eventId, UUID projectId, TestRunView run) {
        return new RunCompletedEvent(
                eventId,
                run.id(),
                run.key(),
                run.name(),
                projectId,
                run.status().name(),
                Math.toIntExact(run.counts().passed()),
                Math.toIntExact(run.counts().failed()),
                Math.toIntExact(run.counts().blocked()),
                Math.toIntExact(run.counts().total()));
    }
}
