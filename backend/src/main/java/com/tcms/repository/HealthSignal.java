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
 *
 * <h2>Severity and label are here for the same reason the attribute is</h2>
 *
 * <p>The dashboard's "Needs attention" panel sorts its tiles by severity rather than alphabetically,
 * because the panel is the one people act on and the two error-severity signals mean something is
 * wrong <em>now</em>. Both properties sit beside the attribute so that adding a tenth signal is one
 * line here and nothing anywhere else — the alternative, a severity map in {@code DashboardService},
 * is a second list to keep in step and the symptom of missing an entry would be a genuinely broken
 * case tiled as neutral.
 *
 * <p>The split: <strong>ERROR</strong> is "the case is now wrong" — its spec moved, or its
 * automation is failing against a case that has since changed. <strong>WARNING</strong> is "this is
 * decaying" — an approval or an execution old enough to stop meaning anything. <strong>INFO</strong>
 * is "something is missing but nothing is broken" — no requirement link, no home.
 */
public enum HealthSignal {

    // Declaration order is unchanged and load-bearing: CaseSearch.tallyHealth selects one
    // aggregate per constant and reads the results back BY POSITION. Sorting for display is
    // severity()'s job and happens where the tiles are built.
    OUTDATED_SPEC_CHANGED("outdatedSpecChanged", Severity.ERROR, "Outdated — spec changed"),
    STALE_APPROVAL("staleApproval", Severity.WARNING, "Stale approval"),
    CONDITION_DRIFT("conditionDrift", Severity.ERROR, "Condition drift"),
    STALE_AUTOMATION("staleAutomation", Severity.ERROR, "Stale automation"),
    NEVER_EXECUTED("neverExecuted", Severity.WARNING, "Never executed"),
    LONG_UNEXECUTED("longUnexecuted", Severity.WARNING, "Not run in 90 days"),
    UNLINKED("unlinked", Severity.INFO, "Unlinked"),
    FAILING_REPEATEDLY("failingRepeatedly", Severity.WARNING, "Failing repeatedly"),
    ORPHANED("orphaned", Severity.INFO, "Orphaned");

    /**
     * {@code DashboardTile.severity} in {@code openapi.yaml}. Ordered worst first, which is the
     * order the "Needs attention" panel sorts by.
     *
     * <p>Public because it is on the wire — {@link DashboardView.TileView} exposes it, and a
     * package-private type behind a public accessor is a shape Jackson has no reason to be asked to
     * reflect over.
     */
    public enum Severity {
        ERROR,
        WARNING,
        INFO,
        NEUTRAL
    }

    private final String attribute;
    private final Severity severity;
    private final String label;

    HealthSignal(String attribute, Severity severity, String label) {
        this.attribute = attribute;
        this.severity = severity;
        this.label = label;
    }

    /** The {@link CaseHealth} field this signal is stored in. */
    String attribute() {
        return attribute;
    }

    /** How loudly a dashboard tile for this signal shouts. */
    Severity severity() {
        return severity;
    }

    /** How this signal is written on a dashboard tile — see {@link LifecycleStatus#label()}. */
    String label() {
        return label;
    }
}
