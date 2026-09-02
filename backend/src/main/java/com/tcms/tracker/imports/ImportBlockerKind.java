package com.tcms.tracker.imports;

/**
 * Why an import cannot proceed — the {@code kind} of the contract's {@code ImportBlocker}.
 *
 * <p>Every member is a condition the <em>preview</em> can see, which is the whole point of
 * computing them here: each one would otherwise be discovered by a run, at minute four, as a
 * constraint violation with nothing written and no sentence anybody can act on.
 *
 * <p>The contract declares these inline on {@code ImportBlocker.kind} rather than as a named
 * schema, so {@code ContractEnumTest} cannot see this type. That is a reason to keep the members
 * in step by hand, not a reason to make it a bare string here: the switch that words each one is
 * exhaustive under {@code -Werror}, and a fourth condition then cannot be added without the
 * sentence for it.
 */
public enum ImportBlockerKind {

    /**
     * A source key already exists in this company. {@code uq_issue_key} is
     * {@code (company_id, issue_key)} and it counts soft-deleted rows, so this is checked
     * company-wide and without a {@code deleted_at} filter — the run would fail on the constraint
     * either way, and a preview that missed a deleted collision would be promising something the
     * database refuses.
     */
    KEY_COLLISION,

    /**
     * The source project holds more issues than {@code tcms.tracker.imports.max-issues}. Refused
     * here, naming the number, because D-b's read pass holds the whole snapshot in heap.
     */
    TOO_MANY_ISSUES,

    /**
     * This source project has already been imported into this project. There is no delta import
     * and no reconciliation (D-m), and {@code uq_import_run_completed_source} is the constraint
     * that would otherwise say so as a 409 after the read has run.
     */
    ALREADY_IMPORTED
}
