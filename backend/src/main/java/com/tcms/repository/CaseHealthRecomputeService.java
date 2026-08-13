package com.tcms.repository;

import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-derives the health signals this module can see for the company currently in context — two
 * since §53, <strong>four since M3 slice 5</strong>, which added §6c's automation-drift pair now
 * that ingestion exists to make "no ingested result in N days" mean something, <strong>five since
 * §157</strong>, which added §6g's stale approval, <strong>six since §158</strong>, which added
 * §6f's condition drift, <strong>eight since §178</strong>, which added §6i's spec-changed
 * marker and §6o's unlinked one, <strong>nine since §181</strong>, which added the owner check,
 * and <strong>ten since §182</strong>, which added §6o's completeness check — the one member of
 * {@link HealthSignal} that did not exist until the slice that derived it.
 *
 * <p><strong>"The signals this module can see" is no longer a narrowing.</strong> Ten of the eleven
 * members of {@link HealthSignal} are computed here and the eleventh ({@code FAILING_REPEATEDLY}) in
 * {@code reporting.ExecutionSource}, so between the two jobs every signal on the wire has a
 * derivation — see {@link OwnerStanding}, which closed the last writerless <em>column</em>, and
 * {@link CaseCompleteness}, which closed the last writerless one of any kind by making it a signal,
 * and {@code CaseHealth}'s header for what that changes about reading a {@code false}.
 *
 * <p>The word "execution-driven" was in this sentence until §157 and is gone rather than qualified:
 * {@code STALE_APPROVAL} is derived from {@code review} and {@code test_case.current_version_no}
 * and has nothing to do with running anything. What the signals here have in common is that
 * <em>this module can see their cause</em> — through {@code ExecutionFacts} for the four that live
 * behind a forbidden module edge, through {@code ApprovalFacts} and {@link OwnerStanding} for the
 * two that live behind permitted ones ({@code project} and {@code identity}), through
 * {@code ConditionDrift} for the one whose cause is in this module
 * outright, and through {@code SpecLinkage} for the two whose cause reached this module as a
 * <em>table</em> — {@code case_doc_link}, which §167 built for the case detail's panel and which
 * turned out to be everything the two documentation signals had been waiting for. §181's edge is
 * the second permitted one and the least eventful of the six: {@code identity} holds
 * {@code company_membership}, this module already crosses to it for the owner's <em>name</em>
 * ({@code CaseOwners}), and what made {@code ORPHANED} the last signal standing was never a
 * boundary — it was a decision, which {@link OwnerStanding}'s header takes.
 *
 * <p>§182's is the module edge that is not one at all: {@link CaseCompleteness} reads
 * {@code test_case}, {@code test_step} and {@code case_classification}, all three of them this
 * module's own, which makes it {@code ConditionDrift}'s shape rather than any of the other five.
 * What blocked it was neither a boundary nor a decision about the domain but <em>a sentence about
 * the contract that was false the day it was written</em> — the account is at
 * {@link HealthSignal#INCOMPLETE}'s column.
 *
 * <h2>Why this exists at all, and what it stops the dashboard from claiming</h2>
 *
 * <p>{@code case_health} has had an entity, a repository, an enum, four partial indexes and a
 * dashboard panel reading it since §47 — and <strong>no writer</strong>. {@code DashboardService}
 * says so in its own javadoc, and draws the consequence precisely: an empty panel headed "Needs
 * attention" is a page telling the reader everything is fine, and it is not, because nobody has
 * looked. {@code healthComputedAt} exists solely so the SPA can render "not computed yet" instead.
 *
 * <p>This is the first thing that makes that timestamp non-null. ~~Two of the nine signals become
 * real; the other seven keep their honest zero, because approvals, documentation links and
 * ingestion still have no writer of their own~~ → <strong>four of the ten, and ingestion is no
 * longer on the list</strong>: §117 gave {@code test_run.source = 'INGESTED'} a writer, which is the
 * whole predicate {@code STALE_AUTOMATION} was blocked on. <del>Approvals</del>, <del>documentation
 * links</del> and <del>the
 * owner check</del> keep their <del>honest</del> zero (see {@code ExecutionFacts}' note on why this
 * job must not touch their columns), as does {@code FAILING_REPEATEDLY}, which is a sibling job's
 * and not absent. <strong>→ nothing is left on that list as of §181</strong>; every clause of this
 * paragraph is now struck through except the one about the sibling job.
 *
 * <p><strong>§157 took approvals off that list</strong> — the first of §156's five to get a
 * derivation, and the cheapest of the five, because {@code review} belongs to {@code project} and
 * {@code project} is a module this one may simply depend on. <strong>§158 took condition drift off
 * it too</strong>, and that one crossed no module boundary at all: {@code test_case_version} and
 * {@code condition} are both this module's. <del><strong>Three are left</strong>, and each needs a
 * module this one may not depend on or a decision nobody has taken — documentation links
 * ({@code UNLINKED} and {@code OUTDATED_SPEC_CHANGED}) and the owner check behind
 * {@code ORPHANED}.</del>
 *
 * <p><strong>→ §178 took both documentation signals, and the module edge it names had stopped being
 * the obstacle a slice earlier.</strong> {@code SpecLinkage} derives them from {@code case_doc_link}
 * — a table in <em>this</em> module, which §167 created to serve the case detail's Traceability
 * panel and which happens to hold, per case, both facts these two signals ask for. So the sentence
 * above was right about the boundary and wrong about what it blocked: nothing had to cross. <del>
 * {@link HealthSignal#ORPHANED} is the last one</del>, and it is the one that needs a decision
 * before it needs any code.
 *
 * <p><strong>→ §181 took it, and the decision was the whole of the work.</strong> The statement is
 * one {@code LEFT JOIN} pair and shorter than {@code ApprovalFacts}'; the four slices that deferred
 * it were right that it was blocked and right about what on. The decision — <em>membership, not
 * grants</em> — is argued at {@link OwnerStanding}, and it rests on this repository's oldest rule
 * rather than on anything about health signals: deriving "left the project" from
 * {@code role_assignment} is a second implementation of {@code PermissionEvaluator}, and it reports
 * every Company Admin as having left the projects they administer.
 *
 * <p><strong>§156: the word "honest" was load-bearing and this service is what took it away.</strong>
 * A zero is honest while the row is absent — the dashboard's whole three-state design rests on
 * that. The first run of this job writes a row and a {@code computed_at} for every live case, so
 * from then on the signals nobody derives report a zero the read path cannot tell from a check
 * that passed — five when §156 wrote that sentence, <del>three</del> <del>one</del>
 * <strong>none</strong> now, and
 * that one was no less misleading for the count having come down. Nothing here is wrong; the consequence just belongs written down at the thing that
 * causes it. {@code CaseHealth}'s header has the account.
 *
 * <p><strong>§182 kept it stopped while adding a member</strong>, which is a different property and
 * the one worth having: {@link HealthSignal#INCOMPLETE} and its derivation landed in one slice, so
 * the enum has never named a column this job leaves at its default. Adding a constant first and a
 * writer later is exactly the state the paragraphs above took five slices to leave.
 *
 * <p><strong>§181 is where that paragraph stops applying to any column</strong> and becomes history
 * rather than a caveat — which is worth stating plainly, because the state it describes lasted from
 * §53 to §181 and the read path never learned to distinguish the two zeroes. It did not have to;
 * the job caught up instead.
 *
 * <h2>Why a scheduled recompute and not an event handler</h2>
 *
 * <p>{@code HealthSignal}'s header calls {@code case_health} "a read model fed by events from
 * {@code execution} and {@code documentation}", and for seven of the signals that is right. It
 * cannot be the whole story for these two: <strong>{@code LONG_UNEXECUTED} becomes true through the
 * passage of time, not through anything happening.</strong> No event fires on the day a case's last
 * result turns ninety days old. An event-only design would leave the signal correct exactly until
 * the moment it should have been raised, which is the one moment it exists for.
 *
 * <p>So the schedule is the floor. Events remain the right way to make a <em>freshly</em> executed
 * case stop reporting {@code NEVER_EXECUTED} promptly, and this service is deliberately the shape an
 * event handler would call — one public method, company taken from context, clock injected — so
 * that landing {@code ResultRecorded} later adds a handler and changes nothing here.
 *
 * @see CaseHealthScheduler which company this runs for, and how that company is established
 */
@Service
public class CaseHealthRecomputeService {

    private static final Logger log = LoggerFactory.getLogger(CaseHealthRecomputeService.class);

    private final ExecutionFacts executionFacts;
    private final ApprovalFacts approvalFacts;
    private final ConditionDrift conditionDrift;
    private final DocumentationFacts documentationFacts;
    private final SpecLinkage specLinkage;
    private final OwnerStanding ownerStanding;
    private final CaseCompleteness caseCompleteness;
    private final CaseHealthProperties thresholds;
    private final Clock clock;

    CaseHealthRecomputeService(
            ExecutionFacts executionFacts,
            ApprovalFacts approvalFacts,
            ConditionDrift conditionDrift,
            DocumentationFacts documentationFacts,
            SpecLinkage specLinkage,
            OwnerStanding ownerStanding,
            CaseCompleteness caseCompleteness,
            CaseHealthProperties thresholds,
            Clock clock) {
        this.executionFacts = executionFacts;
        this.approvalFacts = approvalFacts;
        this.conditionDrift = conditionDrift;
        this.documentationFacts = documentationFacts;
        this.specLinkage = specLinkage;
        this.ownerStanding = ownerStanding;
        this.caseCompleteness = caseCompleteness;
        this.thresholds = thresholds;
        this.clock = clock;
    }

    /**
     * Recompute every live case in the open company.
     *
     * <p>{@code @Transactional} without {@code readOnly}, and that is what makes the isolation work
     * rather than merely what makes the write legal: {@code CompanyAwareTransactionManager} sets
     * {@code app.current_company} in {@code doBegin}, so the statement inside is the only thing that
     * ever sees this company's rows. A recompute run outside a transaction would write with the GUC
     * unset, and V4's {@code nullif} makes that match zero rows rather than all of them — the job
     * would report success having done nothing, which is precisely the fail-closed shape §15
     * documents.
     *
     * @throws IllegalStateException via {@link CompanyContext#require()} if no company is open. Loud
     *     on purpose: the alternative is a job that quietly recomputes nothing, and "the numbers
     *     stopped updating" is far harder to trace back here than a stack trace is.
     */
    @Transactional
    public int recompute() {
        CompanyContext.require();
        Instant now = clock.instant();
        Instant cutoff = now.minus(thresholds.longUnexecuted());

        int cases = executionFacts.recomputeExecutionSignals(cutoff, now);
        // All three statements in one transaction, and therefore under one GUC and one company.
        // Split up, a company whose second transaction failed would be left with half a tick's
        // signals stamped with this tick's computed_at — a row claiming to have been derived just
        // now, half of which was not.
        executionFacts.recomputeAutomationDrift(now.minus(thresholds.staleAutomation()), now);
        // Third statement, same transaction, same reason as the second — and no cutoff of its own,
        // because a stale approval is not a matter of age. §6g's question is whether the case has
        // been edited past the version somebody signed, which is true from the first save and does
        // not become truer in ninety days.
        approvalFacts.recomputeStaleApproval(now);
        // Fourth statement, same transaction, and the first whose inputs are entirely this
        // module's own — the newest version snapshot against the live condition library. No
        // cutoff either: a condition edited an hour ago has changed the case exactly as much as
        // one edited last year, and ageing the signal would mean an ERROR that arrives late.
        conditionDrift.recomputeConditionDrift(now);
        // Fifth statement pair, same transaction, and the only one that is not a signal: it
        // replaces this company's mirror of its documentation links (§167), which is what
        // TestCase.relatedDocuments is served from. It is here rather than in a service of its own
        // because the two documentation-derived SIGNALS are computed from the rows it writes, and
        // those two derivations must see one state of the table — a mirror refreshed by one job and
        // read by another is the case detail's traceability panel disagreeing with the chip beside
        // it about whether a spec moved.
        int links = documentationFacts.refreshCaseDocLinks();
        // Sixth statement, same transaction, and it MUST come after the pair above rather than
        // beside them: it derives both documentation signals from the rows that pair just wrote.
        // Ordered earlier it would read the previous tick's mirror, so a case linked a minute ago
        // would keep its UNLINKED chip while the panel an inch away already listed the link — the
        // two-answers-one-screen defect this whole arrangement is against, arriving from the one
        // direction one transaction does not close. SpecLinkage's header has the account.
        specLinkage.recomputeLinkageSignals(now);
        // Seventh statement, same transaction, and its position does not matter — unlike the pair
        // above, it reads no row this job writes. It is last because it is the newest, and because
        // "who owns this case" is the one question here that nothing else in the transaction has
        // an opinion about.
        ownerStanding.recomputeOrphaned(now);
        // Eighth statement, same transaction, position not load-bearing for the same reason as the
        // seventh: it reads no row this job writes. It is last because it is the newest, and
        // because "is this approved case finished" is the one question here whose inputs are the
        // case's own authored content — the thing every other statement in this transaction reads
        // AROUND rather than reads.
        caseCompleteness.recomputeIncomplete(now);
        log.debug("Recomputed execution, automation-drift, approval, condition-drift, "
                + "documentation-linkage, owner-standing and completeness health signals for {} "
                + "cases, and mirrored {} documentation links", cases, links);
        return cases;
    }
}
