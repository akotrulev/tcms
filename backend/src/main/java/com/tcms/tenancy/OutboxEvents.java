package com.tcms.tenancy;

/**
 * The names of the events that cross module boundaries — the whole vocabulary, in one file.
 *
 * <h2>Why the names live in {@code tenancy} when the meanings do not</h2>
 *
 * <p>An event type is matched by string: the producer writes it into a column and
 * {@link OutboxHandler#eventType()} claims it back, because plan §3 forbids the two modules from
 * sharing a type at all (see {@link OutboxEntry}). Left as literals, a producer's
 * {@code "execution.results_changed"} and a handler's {@code "execution.result_changed"} compile,
 * pass review, and produce a {@link OutboxStatus#DEAD} row in production — which is at least
 * visible, and is still a defect found by an operator rather than by a build.
 *
 * <p>So the names are constants and they live in the one module every other module may import.
 * That is a real cost — adding an event type means editing a foundation module for a feature
 * module's reason — and it buys the one property that matters here: <strong>there is exactly one
 * spelling of each name in the codebase</strong>, and the compiler enforces it on both sides of a
 * boundary the compiler is otherwise forbidden to see.
 *
 * <p>{@code tenancy} holds the <em>names</em> and nothing else. What the event means, what its
 * payload contains and what happens when it arrives all stay in the modules that produce and
 * consume it. The alternative reading — that this is the beginning of a domain vocabulary in the
 * isolation module — is the thing to refuse when the second and third entries arrive.
 *
 * <h2>The naming convention carries a behaviour</h2>
 *
 * <p>A name ending in a <strong>past-tense verb</strong> ({@code case.deleted}) is an occurrence:
 * each one is its own event, with its own payload, recorded through {@link Outbox#record}. A name
 * saying that something <strong>changed</strong> ({@code execution.results_changed}) is a state,
 * recorded through {@link Outbox#recordCoalesced} — several of them collapse into one, and it
 * therefore carries no payload and names no aggregate. Getting that backwards is not a compile
 * error: a coalesced occurrence silently loses events, and an uncoalesced state event delivers one
 * whole-company recompute per row.
 *
 * <p><strong>The vocabulary is one name, and it is the coalesced half (§153).</strong> The
 * past-tense form above is a convention with no instance in it — {@code case.deleted} is an
 * example, not an entry — so the rule stated here has never once discriminated anything. Read the
 * paragraph as what the second entry has to decide rather than as a description of a practice this
 * codebase follows; {@link Outbox#record} carries what else that entry owes.
 */
public final class OutboxEvents {

    /**
     * A company's execution results have moved — recorded, amended or ingested.
     *
     * <p>Coalesced, because its handlers are whole-company recomputes that take no arguments by
     * design ({@code ExecutionFactRefreshService}, {@code CaseHealthRecomputeService}), so fifty
     * verdicts in one run must not be fifty projections. It says which company and nothing else,
     * which is all a recompute needs and all a coalesced event can honestly carry.
     */
    public static final String RESULTS_CHANGED = "execution.results_changed";

    private OutboxEvents() {
    }
}
