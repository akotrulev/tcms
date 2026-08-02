package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code BulkOperation} in {@code docs/specs/openapi.yaml} — one action, one argument, over a
 * selection of cases.
 *
 * <p>The bound shape only. Everything about whether it makes sense — which payload member this
 * action needs, whether the selection is a sane size, whether the target status is reachable — is
 * {@link BulkOperations#parse}'s, so this record stays a faithful transcription of the wire and the
 * validation lives in one readable place rather than being spread across binding annotations.
 *
 * <p>{@code mode} and {@code dedupe} were declared here until this slice and are <strong>deleted
 * rather than implemented</strong>, on §50's rule: nothing sent them, no handler would have bound
 * them, and a parameter that exists in the reviewed spec and in {@code schema.d.ts} while the server
 * ignores it is worse than an absent one — every artifact agrees with the caller and only the
 * behaviour disagrees. What they were reaching for is now stated as behaviour instead:
 * {@code caseIds} is always de-duplicated, {@code ADD_TAGS} is always additive and set-valued, and
 * an attachment always appends.
 *
 * @param dryRun nullable on the wire with a default of {@code true}, and therefore a {@link Boolean}
 *               rather than a {@code boolean}. The default is the safe direction: a client that
 *               forgets the field gets a preview, not a mass edit. Unboxing it with a primitive
 *               would default a missing field to {@code false} and commit.
 */
record BulkOperationRequest(
        BulkAction action,
        List<UUID> caseIds,
        Boolean dryRun,
        BulkPayloadInput payload) {

    /** Whether this request wants a preview. Absent means yes — see the record header. */
    boolean isPreview() {
        return dryRun == null || dryRun;
    }
}
