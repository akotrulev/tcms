package com.tcms.repository;

import com.tcms.project.StatusKind;
import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * The derived health of one case — a <strong>read model</strong>, not authored state (plan §6o).
 *
 * <p><del>Nine booleans, one per {@link HealthSignal}</del> <strong>Eleven booleans: one per
 * {@link HealthSignal}, of which there are ten, plus {@link #incomplete}, which is a column and not
 * a signal</strong> — keyed on the case. The struck sentence counted the signals (nine, then) and
 * called the answer the number of columns, which was already ten; {@code V37}'s
 * {@code UNDECLARED_AUTOMATION} then moved both and neither was corrected. §161. Denormalized into columns
 * rather than held as a set of signal rows because every use is a predicate over a specific signal
 * — "which cases in this project have a stale approval" is a partial index away in this shape and a
 * join plus a filter in the other, and {@code V1} already ships four such indexes.
 *
 * <p><strong>And since {@code V45} it is not only booleans: {@link #lastStatusKind},
 * {@link #lastExecutedAt} and {@link #lastReleaseVersion} carry the case's newest recorded
 * execution.</strong> That makes the class name narrower than its contents, which is deliberate and
 * argued at V45's header — the short form is that {@link #neverExecuted}, {@link #longUnexecuted}
 * and those three are <em>one fact</em> read at three altitudes, they are rendered inches apart on
 * one grid row, and one statement deriving all five is the only arrangement in which they cannot
 * contradict each other. The honest alternative was renaming the table, not splitting it.
 *
 * <h2><del>Nothing writes this yet, and the endpoint is correct anyway</del> → <del>FIVE</del>
 * <del>SIX</del> NINE OF
 * THE TEN SIGNALS ARE WRITTEN NOW, AND THAT IS WHAT MAKES THE <del>OTHER FIVE</del> <del>FOUR</del>
 * LAST ONE MISLEADING</h2>
 *
 * <p><del>The signals are computed from executions and documentation links, and neither module
 * exists. So the table is empty, every case reports no signals, and every health facet counts zero.
 * That is the truthful answer rather than a stub: no signal <em>has</em> been computed.</del>
 * <strong>→ superseded 2026-08-09 (§156), and the reversal is the point.</strong> Both modules
 * exist. {@code ExecutionFacts} writes {@link #neverExecuted}, {@link #longUnexecuted},
 * {@link #staleAutomation} and {@link #undeclaredAutomation} (the last two are §120's),
 * {@code ExecutionSource} writes {@link #failingRepeatedly}, and all three stamp
 * {@link #computedAt} in the same statement. So the table is <em>not</em> empty, and the argument
 * above — which was sound while it was empty — no longer holds for the five signals that stayed
 * behind.
 *
 * <p><strong>What changed is what a {@code false} means.</strong> On an absent row, every signal
 * reads "not computed", which is honest. On a row that exists,
 * <del>{@link #outdatedSpecChanged}</del>, <del>{@link #staleApproval}</del>, <del>{@link #conditionDrift}</del>,
 * <del>{@link #unlinked}</del> and {@link #orphaned} are {@code NOT NULL} columns holding their default, and
 * the read path cannot tell that from a computation that ran and found nothing wrong.
 * <strong>§157 closed the first of them</strong> — {@code ApprovalFacts} derives
 * {@link #staleApproval} now — <strong>§158 the second</strong>, {@code ConditionDrift} for
 * {@link #conditionDrift}, <strong>and §178 the third and fourth together</strong>,
 * {@code SpecLinkage} for the two documentation signals — so <strong>{@link #orphaned} is the only
 * one left</strong> and this paragraph is one-fifth as true as it was.
 * The chip list omits it,
 * the facet counts it zero, and {@code computedAt} — the column mapped precisely so
 * "nothing is wrong" and "nobody has looked" render as different sentences — now says
 * <em>looked</em> for the whole row. The distinction it was added for survives per row and has been
 * lost for that one signal. {@code ArchitectureRulesTest.everyMappedColumnHasAWriter} is what names
 * the remainder; closing it is <del>three derivations</del> <strong>one</strong>, not a schema
 * change, and it needs
 * a decision about what "left the project" means before any module can be asked. <strong>The four
 * that closed cost one class each and not one migration between them</strong>, which is what the
 * paragraph above predicted and is worth having on the record now that the prediction is spent.
 *
 * <p>Which is also why there is no factory here and no mutator anywhere: a signal that a human can
 * set is a signal that can be set to false while its cause is still true, and the self-healing
 * property is the entire reason these are derived. It is also why the remainder are not "fixed" by
 * making them nullable — a three-valued signal moves the problem onto every reader.
 *
 * <h2>Two schema properties worth stating, because both look like mistakes</h2>
 *
 * <p>{@code case_health} carries {@code company_id} and {@code project_id} with <em>no</em> foreign
 * keys — deliberate for a read model, which must be rebuildable and truncatable without fighting
 * referential integrity. It is nonetheless under {@code FORCE ROW LEVEL SECURITY} like every other
 * company-owned table, so the missing FK costs nothing in isolation terms.
 *
 * <p><del>{@code computed_at} is not mapped. It matters to whatever recomputes this and to nothing
 * on the read side; a summary that showed "health as of…" would be reporting on the freshness of a
 * job the user cannot see or trigger.</del> <strong>→ superseded by the dashboard.</strong> The
 * reasoning held while the only reader was a per-case chip list, where absent signals and
 * uncomputed signals render identically and harmlessly. It fails for a panel headed <em>"Needs
 * attention"</em>: empty there is a <em>claim</em> that nothing needs attention, and with no writer
 * for this table that claim is false in every deployment. The column is what tells the two apart,
 * so it is mapped and {@code DashboardView.healthComputedAt} carries it. The original objection is
 * still right about what not to do with it — the SPA reports "not computed yet", never a job's
 * freshness.
 *
 * <p><strong>And the sentence above with "no writer for this table" in it is the one §156
 * falsified</strong> — there are five writers now (§178 added the fifth), so {@code computed_at}
 * is non-null wherever a recompute has run, the dashboard stops saying "not computed yet", and the
 * signals nothing derives are covered by that answer without being part of it. Which is the section
 * above, and the reason it is above this one.
 */
@Entity
@Table(name = "case_health")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CaseHealth {

    /** Also the primary key: one row per case, or none. */
    @Id
    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /**
     * <del><strong>No writer</strong> (§156, {@code ArchitectureRulesTest.WRITERLESS_BY_DECISION}), like
     * {@link #unlinked} and {@link #orphaned} below it — two, after §157 and §158
     * each took one out of that map by deriving it. Its cause is a document change under a linked
     * specification, which is
     * {@code documentation}'s to see and {@code repository}'s to record — so it is an event and a
     * derivation, not a column to fill in here.</del>
     *
     * <p><strong>→ §178 gave it one</strong>, the fourth of the five and the first of the two the map
     * had held longest: {@code SpecLinkage} reads {@code case_doc_link.stale} — the mirror §167
     * built for the Traceability panel — so it is <em>both</em> an event and a derivation, exactly as
     * the struck sentence said, and neither of them had to cross a module boundary. Every
     * relationship counts, not only {@code VERIFIES}; the reason is at the statement.
     *
     * <p><strong>A zero here is a check that ran.</strong> See the class header for the state that
     * made the other reading possible, and {@link #orphaned} for the one column it still describes.
     */
    @Column(name = "outdated_spec_changed", nullable = false)
    private boolean outdatedSpecChanged;

    /**
     * <del>No writer (§156)</del> → <strong>§157 gave it one</strong>, the first of the five to get
     * a derivation: {@code ApprovalFacts} compares the highest {@code APPROVED}
     * {@code review.subject_version_no} against {@code test_case.current_version_no}, which is the
     * comparison {@code ReviewPanel} has drawn its stale banner from since §76.
     *
     * <p><strong>A zero here is now a check that ran</strong>, unlike {@link #outdatedSpecChanged},
     * {@link #unlinked} and {@link #orphaned} — which is the distinction the class header says the
     * read path cannot draw, drawn for one column at a time.
     */
    @Column(name = "stale_approval", nullable = false)
    private boolean staleApproval;

    /**
     * <del>No writer (§156)</del> → <strong>§158 gave it one</strong>, the second of the five and
     * the first whose cause is entirely inside this module: {@code ConditionDrift} compares the
     * conditions recorded in the newest {@code test_case_version.snapshot} against the live
     * {@code condition} rows they name — <em>content</em>, not {@code updated_at}, because a
     * timestamp comparison can never go false and this is an ERROR-severity signal.
     *
     * <p><strong>A zero here is a check that ran</strong>, as on {@link #staleApproval}. Three of
     * the ten are still the other thing.
     */
    @Column(name = "condition_drift", nullable = false)
    private boolean conditionDrift;

    @Column(name = "stale_automation", nullable = false)
    private boolean staleAutomation;

    @Column(name = "never_executed", nullable = false)
    private boolean neverExecuted;

    @Column(name = "long_unexecuted", nullable = false)
    private boolean longUnexecuted;

    /**
     * <del>No writer (§156) — outdatedSpecChanged's case, and <strong>the one the first draft of that
     * rule reported as live</strong>: a fixture's unterminated {@code UPDATE} swallowed the rest of
     * its file's string literals, and an {@code @DisplayName} containing the word "unlinked" stood
     * in for a writer. The cause is a case with no {@code doc_link}, which {@code BlockLinks} can
     * see and nothing derives.</del>
     *
     * <p><strong>→ §178 gave it one</strong>, in the same statement as
     * {@link #outdatedSpecChanged} — one lateral aggregate over {@code case_doc_link}, because "does
     * this case link to anything" and "has anything it links to moved" are two readings of one row
     * set.
     *
     * <p>It is also the column that made §178's test rewrite large rather than additive: <strong>this
     * signal fires on every case nobody has linked</strong>, which in {@code CaseHealthRecomputeTest}
     * is nearly all of them, and in a young project is nearly all of a company's. Argued at
     * {@link HealthSignal#UNLINKED}, where the severity is INFO for that exact reason.
     */
    @Column(name = "unlinked", nullable = false)
    private boolean unlinked;

    @Column(name = "failing_repeatedly", nullable = false)
    private boolean failingRepeatedly;

    /** No writer (§156) — outdatedSpecChanged's case; the cause is a case in no suite and no plan. */
    @Column(name = "orphaned", nullable = false)
    private boolean orphaned;

    /**
     * {@code case_health.undeclared_automation} — {@code V37}, the mirror of
     * {@link #staleAutomation} (plan §6c).
     *
     * <p>Both are derived from one fact on opposite sides of one cutoff, so a case can never raise
     * both. That is a property of the computation and not of the schema, which is why
     * {@code ExecutionFacts} writes them in a single statement rather than two.
     */
    @Column(name = "undeclared_automation", nullable = false)
    private boolean undeclaredAutomation;

    /**
     * {@code case_health.incomplete} — mapped so {@code ddl-auto: validate} has the whole row in
     * view, and absent from the server's {@link HealthSignal} because
     * <strong>nothing derives it</strong>: there is no computation to name, so there is no constant.
     *
     * <p><del>The contract's enum has no such member, and inventing one would put a value on the
     * wire the SPA cannot label.</del> <strong>→ corrected 2026-08-10 (§161), and the correction
     * reverses what closing this costs.</strong> {@code openapi.yaml}'s {@code HealthSignal} has
     * declared {@code INCOMPLETE} since <em>2026-07-30</em> — the day before this comment was
     * written — {@code lib/labels.ts} has read <em>"Missing required fields"</em> for it just as
     * long, {@code StatusChip} has a sentence for it, and both copies of {@code schema.d.ts} carry
     * it. The contract states the arrangement in its own words at the schema and calls it
     * deliberate: the member is kept so a client's labels stay complete. So the wire side is
     * already paid for and always was. What is missing is <em>this end</em> — a derivation, and
     * before it a decision about what "incomplete" means for a case, which is
     * {@link #orphaned}'s shape rather than {@link #staleApproval}'s.
     *
     * <p>The direction matters because it is what a reader acts on. Stated as a contract problem,
     * closing this reads as a wire change — the expensive kind, two generated copies and every
     * client. It is not one.
     *
     * <p>§154 made this the reference entry in
     * {@code ArchitectureRulesTest.READERLESS_BY_DECISION}, because the comment above was already
     * in the shape that rule asks of the other twelve: what the column is, why it is mapped
     * anyway, and why it stops there. It was the right shape carrying a false premise, copied
     * twelve times before anybody checked it; {@code ContractEnumTest} is the check that reads the
     * contract instead of trusting a sentence about it. §156 put it in
     * {@code WRITERLESS_BY_DECISION} as well, which is the harmless overlap: unlike the signals
     * above it, nothing reads this one either, so there is no reader to be misled by the default
     * it holds.
     */
    @Column(name = "incomplete", nullable = false)
    private boolean incomplete;

    /**
     * When this row was last derived.
     *
     * <p>Mapped for the dashboard, which reports the newest of these across a project so that "no
     * signal is raised" and "no signal has been computed" render as different sentences.
     * <del>Nothing writes it yet, so the aggregate over an empty table is null — which is exactly
     * the state the dashboard needs to be able to distinguish.</del>
     *
     * <p><strong>That sentence was written in §47 and was false from §53 onward; §156 corrected the
     * class header and left this one standing, which is the same slice-shape it was about.</strong>
     * Every writer of this table stamps this column in the same statement, and the recompute is
     * scheduled by default — so the null branch is the one no running deployment is in, and what
     * the dashboard actually needs to distinguish is per-signal rather than per-row. See the class
     * header.
     */
    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    /**
     * {@code case_health.last_status_kind} — the first of {@code V45}'s three, and the first
     * columns on this row that are <strong>not signals</strong> beyond {@link #computedAt} and
     * {@link #incomplete}.
     *
     * <p>They are here rather than in a {@code case_last_result} table because
     * {@link #neverExecuted}, {@link #longUnexecuted} and these are <em>one fact</em> —
     * "the newest recorded execution of this case" — read at three altitudes.
     * {@code ExecutionFacts.recomputeExecutionSignals} computes it once and derives all five
     * columns from it in a single statement, so the health chips and the Last result cell of one
     * grid row cannot disagree about whether the case has ever run. Two tables would have made that
     * agreement a property of two jobs staying in step; V45's header has the full argument and what
     * it costs.
     *
     * <p>{@code EnumType.STRING}, matching {@code case_status.kind}, which is TEXT with a CHECK.
     * Nullable — a case that has never been executed has no last result, and that is the same
     * condition {@link #neverExecuted} asserts.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_status_kind")
    private StatusKind lastStatusKind;

    /** {@code case_health.last_executed_at} — see {@link #lastStatusKind}. */
    @Column(name = "last_executed_at")
    private Instant lastExecutedAt;

    /** {@code case_health.last_release_version} — see {@link #lastStatusKind}. Null outside a release. */
    @Column(name = "last_release_version")
    private String lastReleaseVersion;

    /** JPA only. */
    protected CaseHealth() {
    }

    public UUID getCaseId() {
        return caseId;
    }

    /**
     * The case's newest recorded execution, or <strong>null when it has never been executed</strong>
     * — which is also the state a row written before {@code V45} is in, until the recompute next
     * runs.
     *
     * <p>Keyed on {@link #lastExecutedAt} rather than on the kind, and the choice is not arbitrary:
     * the moment is the fact the whole derivation is about, and the two columns beside it are
     * things that were true of the row carrying that moment. A case executed into a project whose
     * statuses were later re-kinded could in principle be found with a moment and no kind; keying
     * on the moment renders that as an incomplete-but-real result rather than as "never run", and
     * keying the other way would silently reinstate the exact falsehood §165 measured.
     *
     * <p>Never a partially-filled {@link LastResultView}: the contract makes the object nullable
     * and its members required, and the three columns are written by one statement from one
     * execution row, so there is no code path that fills some of them.
     */
    LastResultView lastResult() {
        return lastExecutedAt == null
                ? null
                : new LastResultView(lastStatusKind, lastExecutedAt, lastReleaseVersion);
    }

    /**
     * The signals this row asserts, in the enum's declaration order.
     *
     * <p>Reflection-free on purpose even though {@link HealthSignal} names an attribute: that name
     * exists for Criteria, which needs a string, and a second string-driven path here would turn a
     * typo into a silently missing chip instead of the startup failure the mapped field already
     * gives. The switch is exhaustive over the enum, so a new signal is a compile error until it is
     * handled — which is the property that keeps the three uses in step.
     */
    List<HealthSignal> signals() {
        List<HealthSignal> raised = new ArrayList<>();
        for (HealthSignal signal : HealthSignal.values()) {
            boolean set = switch (signal) {
                case OUTDATED_SPEC_CHANGED -> outdatedSpecChanged;
                case STALE_APPROVAL -> staleApproval;
                case CONDITION_DRIFT -> conditionDrift;
                case STALE_AUTOMATION -> staleAutomation;
                case NEVER_EXECUTED -> neverExecuted;
                case LONG_UNEXECUTED -> longUnexecuted;
                case UNLINKED -> unlinked;
                case FAILING_REPEATEDLY -> failingRepeatedly;
                case ORPHANED -> orphaned;
                case UNDECLARED_AUTOMATION -> undeclaredAutomation;
            };
            if (set) {
                raised.add(signal);
            }
        }
        return raised;
    }
}
