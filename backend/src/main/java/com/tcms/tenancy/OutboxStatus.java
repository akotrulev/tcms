package com.tcms.tenancy;

/**
 * Where one {@link OutboxEntry} is in its delivery life, and the four values are {@code V1}'s
 * {@code CHECK} rather than a set this class chose.
 *
 * <h2>The partial index decides the semantics</h2>
 *
 * <p>{@code V1} ships {@code CREATE INDEX outbox_pending_idx ON outbox (next_attempt_at) WHERE
 * status = 'PENDING'}, and that one line settles a question every outbox design has to answer:
 * <strong>what status does a row wear between a failed attempt and its retry?</strong> Anything but
 * {@code PENDING} and the retry is invisible to the only index the schema has for finding it, so the
 * dispatcher would fall back to a sequential scan of a table nothing prunes. A retrying row
 * therefore stays {@code PENDING} with {@code next_attempt_at} pushed into the future — the column
 * is the schedule, and the status is only "is this still ours to deliver".
 *
 * <h2>{@code FAILED} and {@code DEAD} are not two words for the same thing</h2>
 *
 * <p>Both are terminal for the dispatcher and they differ in what an operator can do about them,
 * which is the only distinction worth storing:
 *
 * <ul>
 *   <li>{@code FAILED} — the attempt ceiling was reached against a failure that <em>might</em> stop
 *       happening: a handler throwing, a lock timeout, a service that was down. Replaying it after
 *       fixing the cause is a sensible act.</li>
 *   <li>{@code DEAD} — nothing about retrying can change the outcome. No {@link OutboxHandler}
 *       claims the event type, or two claim it, or the payload cannot be read. Replaying is
 *       pointless until the <em>deployment</em> changes.</li>
 * </ul>
 *
 * <p>Collapsing them would cost the one question an operator actually asks of this table — "is this
 * worth another go?" — and the answer would then have to be re-derived from {@code last_error}
 * prose, which is written for a human and parsed by nobody.
 */
public enum OutboxStatus {

    /**
     * Awaiting delivery: due when {@code next_attempt_at <= now}, and scheduled for later when it
     * is not. The only status the dispatcher reads, and the only one the index covers.
     */
    PENDING,

    /** A handler returned normally. Terminal, and the only outcome that means anything happened. */
    SENT,

    /** Transient failure, ceiling reached. Terminal here; an operator may replay it. */
    FAILED,

    /** Structurally undeliverable. Terminal, and retrying it cannot help — see the class comment. */
    DEAD
}
