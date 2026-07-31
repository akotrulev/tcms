package com.tcms.repository;

/**
 * {@code HealthSignal} in {@code docs/specs/openapi.yaml} — what is wrong with a case, derived
 * and never authored (plan §6o).
 *
 * <p>Every member names one boolean field on {@link CaseHealth}, and the constant carries that
 * field's name so the filter, the facet and the per-row list all resolve the same source rather
 * than each spelling out its own mapping. Three hand-written mappings is three chances to pair a
 * signal with the wrong column, and the symptom would be a filter that selects one set of cases
 * while the chips on those cases claim something else — plausible on both sides, failing nothing.
 *
 * <p>The name is the <strong>JPA attribute</strong>, not the database column, so every use goes
 * through Criteria and keeps the Hibernate company filter applied. A native query would drop it
 * and leave RLS as the only thing between one client's cases and another's, which is the
 * single-point-of-defence trade {@code ProjectRepository}'s header refuses.
 * {@code CaseHealthAttributesTest} asserts every constant resolves, because a typo here is a
 * runtime {@code IllegalArgumentException} on one filter path and nothing at all on the others.
 *
 * <h2>Derived means there is nothing to write here</h2>
 *
 * <p>No endpoint sets a signal and none ever will: a "mark as not stale" button is a button for
 * lying, and the point of computing these is that they self-heal when the cause is fixed.
 * {@code case_health} is a read model in the §3-lesson-4 sense, fed by events from
 * {@code execution} and {@code documentation}.
 *
 * <p><strong>Nothing populates it yet</strong>, because neither module exists. So every case
 * currently reports {@code []} and every facet count is zero — the honest answer ("no signal has
 * been computed"), not a placeholder. It starts working when the computation lands, with no change
 * here.
 *
 * <h2>{@code incomplete} is on the table and not in this enum</h2>
 *
 * <p>{@code case_health.incomplete} exists in the schema with no counterpart in the contract's
 * enum. Left out rather than added: putting it on the wire would invent a member the SPA has no
 * label for (in {@code lib/labels.ts} a new member is a compile error until it is labelled), and
 * adding it to {@code openapi.yaml} is a contract change with no consumer on the other side.
 */
public enum HealthSignal {

    OUTDATED_SPEC_CHANGED("outdatedSpecChanged"),
    STALE_APPROVAL("staleApproval"),
    CONDITION_DRIFT("conditionDrift"),
    STALE_AUTOMATION("staleAutomation"),
    NEVER_EXECUTED("neverExecuted"),
    LONG_UNEXECUTED("longUnexecuted"),
    UNLINKED("unlinked"),
    FAILING_REPEATEDLY("failingRepeatedly"),
    ORPHANED("orphaned");

    private final String attribute;

    HealthSignal(String attribute) {
        this.attribute = attribute;
    }

    /** The {@link CaseHealth} field this signal is stored in. */
    String attribute() {
        return attribute;
    }
}
