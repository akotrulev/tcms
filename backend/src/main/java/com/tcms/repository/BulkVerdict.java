package com.tcms.repository;

/**
 * What a bulk operation decided about <strong>one</strong> case.
 *
 * <h2>Three buckets, and every requested id lands in exactly one</h2>
 *
 * <p>That total is the invariant the whole response rests on:
 * {@code changed + unchanged + skipped == total}. The dialog renders "38 changed, 4 already matched,
 * 2 skipped" over a selection of 44, and a fourth outcome — or a silent drop — makes those numbers
 * not add up on the one screen whose purpose is telling the user exactly what happened to their
 * selection.
 *
 * <p><strong>The MSW mock violated this and it is the defect this type exists to prevent.</strong>
 * Its loop did {@code if (!row) continue} for an id it could not find, so a case that had been
 * deleted, or that belonged to another project, vanished from the report while still counting
 * toward {@code total} — a user who selected 44 was shown an accounting of 42 and told nothing about
 * the other two. An id the server cannot act on is {@link Skipped} with a reason, never dropped.
 *
 * <p>Sealed for {@link com.tcms.project.GateResult}'s reason: the caller's switch is exhaustive at
 * compile time, so a fourth outcome added later cannot be handled in the counting loop and forgotten
 * in the audit detail.
 */
sealed interface BulkVerdict {

    /** The case will change (preview) or did change (commit). */
    record Changed() implements BulkVerdict {
    }

    /**
     * Already in the requested state — compliant, not failed.
     *
     * <p>Distinct from {@link Skipped} deliberately. A case already approved is not a problem the
     * user needs to act on, and folding the two together would put a warning on an operation that
     * did exactly what was asked.
     */
    record Unchanged() implements BulkVerdict {
    }

    /**
     * The case could not be changed, with a sentence the UI shows beside its key.
     *
     * @param reason user-facing and specific — "A draft must be reviewed before it can be approved"
     *               rather than "invalid transition". This string is rendered verbatim in the
     *               preview, next to the case it is about, and it is the only thing telling the user
     *               what to do differently.
     */
    record Skipped(String reason) implements BulkVerdict {
    }
}
