package com.tcms.execution;

import java.util.UUID;

/**
 * {@code RunScheduleInput} on the wire — the contract's seven properties and no more (§247).
 *
 * <p>Served by both verbs: {@code POST} creates and {@code PUT} replaces, and the body is a
 * <strong>total statement</strong> in each. An omitted {@code assigneeId} or
 * {@code configurationId} on a {@code PUT} clears it, on the same terms as the rest of this API's
 * writes.
 *
 * <p><strong>{@code scope} is {@link RunScopeRequestView} and not a shape of its own.</strong> That
 * record is already the read side of {@code test_run.scope_request} and is already paired with the
 * contract's {@code RunScopeRequest}; declaring a second record with the same four components here
 * would be a second wire shape for the document whose whole point is that there is one. It is used
 * as an input binding rather than only as an output view, which Jackson handles as it does any
 * record — and the alternative, four flat arms on this record, would not match the schema and would
 * redden {@code ContractConventionTest}'s member comparison.
 *
 * <p>Its list components arrive <strong>null</strong> for an omitted key, unlike the ones
 * {@link RunScopeRequestView#parse} builds, so every reader of an inbound {@code scope} goes
 * through {@link RunScheduleEdit}'s normalisation rather than dereferencing them.
 *
 * <p>{@code nextFireAt}, {@code lastFiredAt}, {@code lastRunId} and {@code lastFireError} are all
 * absent for {@link RunWriteRequest}'s reason: they are the server's to decide, and a field the
 * client can set is a claim the client can make. {@code enabled} is <em>not</em> in that group — it
 * is the operator's own switch, and pausing a cadence is a thing only a person can mean.
 */
record RunScheduleWriteRequest(
        String name,
        String cronExpression,
        String zoneId,
        RunScopeRequestView scope,
        UUID configurationId,
        UUID assigneeId,
        Boolean enabled) {
}
