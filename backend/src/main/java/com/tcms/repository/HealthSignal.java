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
 * <p>~~<strong>Nothing populates it yet</strong>, because neither module exists.~~ →
 * <strong>superseded, and the prediction held: each computation landed with no change here except
 * its own constant.</strong> {@code NEVER_EXECUTED} and {@code LONG_UNEXECUTED} got a writer in §53
 * ({@code ExecutionFacts}), {@code FAILING_REPEATEDLY} in {@code reporting.ExecutionSource},
 * {@code STALE_AUTOMATION} + {@code UNDECLARED_AUTOMATION} in M3 slice 5,
 * {@link #STALE_APPROVAL} in §157 ({@code ApprovalFacts}), {@link #CONDITION_DRIFT} in §158
 * ({@code ConditionDrift}) and {@link #UNLINKED} + {@link #OUTDATED_SPEC_CHANGED} in §178
 * ({@code SpecLinkage}) — <strong><del>five</del><del>six</del><del>seven</del> nine of the ten
 * are real</strong>. <del>The other five still report an honest zero</del> → <strong>and §156 is
 * where "honest" stopped being the right word.</strong> A zero is honest on an <em>absent</em> row,
 * which is what this sentence was written against; the recompute that made the five real also
 * writes a row and a {@code computed_at} for every live case, and on a row that exists the other
 * five report a zero indistinguishable from a check that ran and passed. The dashboard drew exactly
 * that conclusion until §156 narrowed its copy. See {@code CaseHealth}'s header for the full
 * account and for why nullable columns are not the fix.
 *
 * <p><strong><del>Four, since §157</del> <del>Three, since §158</del> One, since §178.</strong>
 * {@link #STALE_APPROVAL} is
 * derived from {@code review} and the case's current version number, {@link #CONDITION_DRIFT} from
 * the newest version's snapshot against the live library, and each derivation cost one class —
 * which is the shape the remaining three <del>are expected to have</del> <strong>two of the three
 * turned out to have</strong>, one module at a time, rather than a
 * schema change. <del>Two of the three ({@link #UNLINKED}, {@link #OUTDATED_SPEC_CHANGED}) need
 * {@code documentation}, which this module may not depend on, so those two are a seam and not
 * merely a statement</del> — <strong>and that prediction was wrong in the direction worth
 * recording.</strong> Both are one class ({@code SpecLinkage}) and <em>no</em> seam: §167 built
 * {@code case_doc_link} in this module for the case detail's panel, so by §178 the rows the two
 * signals need were already here and the module edge had stopped being the obstacle. A sentence
 * naming a real constraint can go stale by something else being built.
 * {@link #ORPHANED} needs a decision about what "left the project" means
 * before it needs any code, and it is the last.
 *
 * <h2>{@code incomplete} is on the table and not in this enum</h2>
 *
 * <p>{@code case_health.incomplete} exists in the schema with no constant here. Left out rather
 * than added: no computation asserts it, and a member whose column no writer fills is a facet that
 * always tallies zero.
 *
 * <p><strong>The contract went the other way and this header did not notice.</strong>
 * {@code openapi.yaml}'s {@code HealthSignal} <em>does</em> list {@code INCOMPLETE}, and
 * {@code lib/labels.ts} labels it — so the original reasoning here ("putting it on the wire would
 * invent a member the SPA has no label for") describes a state that has not been true for some
 * time. The two enums are therefore <strong>not</strong> the same set: the wire is a superset by
 * one member this server can never send. Left as it is rather than tidied in either direction,
 * because both tidyings are worse — deleting it from the contract breaks a label the SPA has, and
 * adding it here invents the writerless facet above.
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
    /**
     * §6i's linked-spec marker — {@code SpecLinkage} since §178, derived from
     * {@code case_doc_link.stale} rather than from {@code doc_link} a second time.
     *
     * <p><strong>ERROR, and the first constant here whose severity was decided before anything could
     * raise it and holds up unchanged.</strong> A statement the case was written against has been
     * rewritten: whoever runs the case today is testing what the spec said last quarter, and no
     * amount of reading the case reveals it. {@link #CONDITION_DRIFT}'s argument, one table further
     * out.
     *
     * <p><strong>Every relationship counts, unlike the two coverage sites</strong> — a case
     * {@code DERIVED_FROM} a moved statement is as wrong as one claiming to verify it. Argued at
     * {@code SpecLinkage.recomputeLinkageSignals}, beside the opposite call for {@link #UNLINKED}.
     */
    OUTDATED_SPEC_CHANGED("outdatedSpecChanged", Severity.ERROR, "Outdated — spec changed"),
    /**
     * §6g's edit-after-approval marker, and since §157 the one signal here derived from neither an
     * execution nor a document — {@code ApprovalFacts} reads {@code review}, which
     * {@code repository} may depend on directly.
     *
     * <p><strong>WARNING and not ERROR</strong>, unchanged from M0 and worth stating now that it
     * can actually fire: nothing is broken. The case says what its author last saved, and the only
     * thing out of date is who has read it. {@code EditAfterApproval}'s default is {@code RETAIN}
     * precisely because "forcing full re-approval on a typo fix is how review processes get
     * abandoned" — a signal that shouted would be arguing with the setting it reports on.
     */
    STALE_APPROVAL("staleApproval", Severity.WARNING, "Stale approval"),
    /**
     * §6f's reference-while-live, copy-once-versioned split, observed from the side where the two
     * disagree — {@code ConditionDrift} since §158.
     *
     * <p><strong>ERROR and not WARNING</strong>, which is the opposite call from
     * {@link #STALE_APPROVAL} and worth stating beside it now that both fire. A stale approval
     * means the case is right and somebody has not read it. This one means the case a tester is
     * being handed today is not the case anybody recorded: the steps changed under it, or —
     * loudest — a referenced entry was deleted and the live case quietly renders one instruction
     * fewer than the snapshot says it should. Nothing about that self-corrects by being read.
     */
    CONDITION_DRIFT("conditionDrift", Severity.ERROR, "Condition drift"),
    STALE_AUTOMATION("staleAutomation", Severity.ERROR, "Stale automation"),
    NEVER_EXECUTED("neverExecuted", Severity.WARNING, "Never executed"),
    LONG_UNEXECUTED("longUnexecuted", Severity.WARNING, "Not run in 90 days"),
    /**
     * §6o's traceability gap — {@code SpecLinkage} since §178, and the only signal here that will
     * fire on <em>most</em> cases of a project that has not started linking. Plan §6o's own layout
     * mock-up says as much: {@code Unlinked 156} beside {@code Approved 891}.
     *
     * <p><strong>INFO, and the volume is why.</strong> Nothing is broken and nothing is decaying; a
     * case with no link is a case whose coverage cannot be reported on, which is a project-level
     * omission rather than a fault in the case. A WARNING on 156 cases is a panel people learn to
     * ignore, and then the two ERROR signals go with it.
     *
     * <p><strong>Any link clears it, including an external one</strong> — and a case linked only by
     * {@code REFERENCES} is therefore not unlinked, though it covers nothing. That is the one place
     * this enum deliberately says less than the coverage matrix does; the argument is at
     * {@code SpecLinkage.recomputeLinkageSignals} and it turns on the chip being rendered inches
     * from the list of the very links it would be denying.
     */
    UNLINKED("unlinked", Severity.INFO, "Unlinked"),
    FAILING_REPEATEDLY("failingRepeatedly", Severity.WARNING, "Failing repeatedly"),
    ORPHANED("orphaned", Severity.INFO, "Orphaned"),

    /**
     * §6c's second drift direction, added in {@code V37} — CI is posting results for a case whose
     * {@code automationStatus} says no automation exists.
     *
     * <p><strong>Appended last, and that is the only safe place to add one.</strong>
     * {@code CaseSearch.tallyHealth} selects one aggregate per constant and reads the results back
     * <em>by position</em>; inserting this beside {@link #STALE_AUTOMATION}, where it belongs
     * thematically, would silently shift every facet count after it onto the wrong tile.
     *
     * <p><strong>WARNING rather than ERROR, unlike its mirror, and the asymmetry is the point.</strong>
     * {@code STALE_AUTOMATION} means a result the team believes it is getting is not arriving — a
     * red test can be invisible for as long as it lasts. This one means results are arriving that no
     * coverage number counts: the automation-coverage percentage is understated, which is wrong in
     * the direction that costs nobody a missed defect. Both need fixing; only one of them can be
     * hiding a failure.
     */
    UNDECLARED_AUTOMATION(
            "undeclaredAutomation", Severity.WARNING, "Automated, but not marked so");

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
