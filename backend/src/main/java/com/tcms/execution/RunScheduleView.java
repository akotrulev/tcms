package com.tcms.execution;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's {@code RunSchedule} (§247).
 *
 * <h2>Three resolved fields, and none of them costs a query per row</h2>
 *
 * <p>{@code assigneeName}, {@code createdByName} and {@code lastRunKey} are all lookups, and
 * {@code RunScheduleService} resolves each of them <strong>once for the whole list</strong> —
 * {@code ExecutionPeople#resolve} for the two people, {@code TestRunRepository#findRunKeys} for the
 * runs. A project with thirty schedules is three queries, not ninety-one; the N+1 that looks
 * harmless because each query is fast is what {@code ExecutionPeople}'s own header refuses.
 *
 * <p>All three are <strong>nullable and a null is not evidence about the id beside it</strong>.
 * {@code createdByName} is null for an owner whose membership row is gone, and the id is still the
 * answer to "whose authority does this fire under" — read the id. {@code lastRunKey} is null for a
 * run somebody has since deleted, which is what tells a client not to offer a link to a screen that
 * would 404.
 *
 * <h2>{@code createdBy} is the authority, and the reader has to be told</h2>
 *
 * <p>It is what {@code run:create} is re-asked of on every fire, so a schedule outlives neither the
 * grant nor the account — and it is <strong>not</strong> who created the runs. Those carry a null
 * {@code createdBy} and an audit row with no actor. The contract says so at the field, because a
 * screen that rendered this name beside a scheduled run would state that a person did something on
 * a night they did nothing.
 *
 * <p>{@code lockVersion} is deliberately absent. There is no {@code If-Match} on the {@code PUT} —
 * the 409 comes from the entity's own {@code @Version} at flush, which is a genuine interleaving
 * rather than a precondition a client could have checked — so serving the counter would offer a
 * header nothing reads, which is §205's finding from the client side.
 */
public record RunScheduleView(
        UUID id,
        UUID projectId,
        String name,
        String cronExpression,
        String zoneId,
        RunScopeRequestView scope,
        UUID configurationId,
        UUID assigneeId,
        String assigneeName,
        boolean enabled,
        Instant nextFireAt,
        Instant lastFiredAt,
        UUID lastRunId,
        String lastRunKey,
        String lastFireError,
        Instant createdAt,
        UUID createdBy,
        String createdByName) {
}
