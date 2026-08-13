package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code case_health} recompute — the first writer this read model has ever had, and since M3
 * slice 5 the writer of <strong>four</strong> signals rather than two: §6c's automation-drift pair
 * joined the execution pair once §117 made {@code test_run.source = 'INGESTED'} a thing that
 * exists. <strong>Five since §157</strong>, which added §6g's stale approval — the first signal
 * here that is not about running anything, and the first of the five §156 found writerless to get
 * a derivation. <strong>Six since §158</strong>, which added §6f's condition drift: the second of
 * the five, and the first whose cause is neither an execution nor another module's table but this
 * module's own version history read against its own library. <strong>Eight since §178</strong>,
 * which added §6i's spec-changed marker and §6o's unlinked one together — the third and fourth of
 * the five, taken in one slice because both are read off {@code case_doc_link}, the mirror §167
 * built for a panel. <strong>Nine since §181</strong>, which added §6o's owner check — the fifth
 * and last of them, and the first fixture in this class that needs <em>people</em>: five accounts
 * and four memberships, where the review fixtures deliberately seed none because their derivation
 * asks which version was signed and never by whom.
 *
 * <strong>Ten since §182</strong>, which added §6o's completeness check — the one signal here whose
 * enum member did not exist before the slice that derived it, and the first needing a case's own
 * authored content (steps, expected results, a classification) as fixture rather than something
 * that happened to the case.
 *
 * <p>That last pair is why this class's assertions grew rather than merely gained a group:
 * <strong>{@code unlinked} fires on every case nobody has linked</strong>, which here is nearly all
 * of them. <strong>{@code incomplete} is the second signal with that property</strong>, on a
 * narrower population: the five APPROVED cases of the stale-approval block are unfinished by this
 * derivation's reading and their assertions say so, which is the same call §178 made and for the
 * same reason — an assertion that filtered the sibling out would pass against an implementation
 * raising it wrongly. Every {@code containsExactly} names it, for the reason the stale-approval fixtures give
 * about {@code never_executed} — an assertion that filtered a sibling signal out would pass against
 * an implementation raising it wrongly.
 *
 * <h2>What this class is really guarding</h2>
 *
 * <p>The dashboard has rendered a "Needs attention" panel since §47 with nothing behind it, and
 * {@code DashboardService} already draws the consequence in its own javadoc: an empty panel is a
 * <em>claim</em> that nothing needs attention, and that claim was false in every deployment because
 * nobody had looked. This job is what makes it true or false on purpose. So the failures worth
 * catching here are all failures that <strong>look like data</strong>:
 *
 * <ol>
 *   <li><strong>An unrecorded execution counted as an execution.</strong> Creating a run writes one
 *       empty execution per case in scope (§42), so if "has a {@code test_execution} row" were the
 *       predicate, planning a run would silently mark every case in it as executed — and
 *       {@code NEVER_EXECUTED} would drop to zero across the project the moment somebody planned
 *       work they had not done. The predicate is {@code status_id IS NOT NULL}, which is the same
 *       definition of "recorded" the case grid, plan statistics and §52's suite timeline use.</li>
 *   <li><strong>The wrong execution winning.</strong> A case executed 200 days ago and again last
 *       week is not stale. Taking any row rather than the newest inverts the answer for exactly the
 *       cases that are being actively maintained.</li>
 *   <li><strong>A result counted as evidence about the wrong thing.</strong> The drift pair's
 *       predicate is the newest <em>ingested</em> result, and the statement directly above it in
 *       {@code ExecutionFacts} reads the newest result of any kind. A case CI abandoned but a
 *       person still runs by hand satisfies one and not the other, and copying the neighbouring
 *       lateral — the likeliest edit anybody makes here — reports it as healthy.</li>
 *   <li><strong>The job asserting signals it did not compute.</strong> <del>Five</del> <del>four</del>
 *       <del>one</del> <strong>none</strong> of the
 *       ten signals has no writer, and one ({@code failing_repeatedly}) has a writer in
 *       another module writing the same rows on its own schedule. An {@code ON CONFLICT} that set every column would make this job the author
 *       of claims it has no evidence for, and would blank the neighbouring computation's work on
 *       every tick. The columns are owned individually even though the row is shared. <strong>The
 *       column {@code Boundaries.otherSignalsAreNotClobbered} uses to prove it has had to move
 *       three times</strong> — §157 took {@code stale_approval}, §178 took {@code unlinked} and
 *       §181 took {@code orphaned}, each in this very service — so the fourth spelling is not a
 *       signal at all: {@code incomplete}, the one {@code case_health} boolean outside the enum and
 *       the only one left that nothing writes. A column with no owner cannot acquire one by a
 *       sibling module deciding to run, which is the objection that ruled {@code failing_repeatedly}
 *       out twice. <strong>→ §182 derived that one too, so there is no witness left and no way to
 *       make another.</strong> The assertion drives each statement alone now and reads the property
 *       off its siblings' own columns, which is stronger than any witness was: it can see two
 *       statements inside this job both writing one column, and the witness shape structurally
 *       could not.</li>
 *   <li><strong>A signal derived from a table this job rewrote a statement earlier.</strong> §178's
 *       two are read off {@code case_doc_link}, so they are the only signals here whose correctness
 *       depends on where their statement <em>sits</em> rather than on what it says. One test asserts
 *       the ordering directly, because with the two statements swapped every other assertion in this
 *       class still passes.</li>
 *   <li><strong>Writing across companies.</strong> The statement carries no company predicate — it
 *       relies entirely on {@code FORCE ROW LEVEL SECURITY} applying to both the {@code SELECT} and
 *       the {@code INSERT}. That is a correct design and an untested one is indistinguishable from
 *       a leak, so it is asserted with two companies rather than argued in a comment.</li>
 *   <li><strong>A soft-deleted case getting a row.</strong> It is absent from every count on the
 *       dashboard; a health row for it would be a signal nothing can navigate to.</li>
 * </ol>
 *
 * <h2>Why it drives the service instead of the scheduler</h2>
 *
 * <p>{@code CaseHealthScheduler} is disabled in the suite ({@code IntegrationTestBase} says why: a
 * write on a timer rewrites other test classes' fixtures partway through a run). What the scheduler
 * adds over the service is the company loop, and that is asserted here directly by opening two
 * companies in turn — which is also the only part of it that could be wrong.
 *
 * <p>Ids are unique to this class — {@code ce…}. Sharing them with another class is how §19's first
 * defect happened: green alone, red in the full suite.
 *
 * <p><strong>And the ids are not the only thing that has to be unique — this class proved it the
 * hard way.</strong> Its first draft took {@code ch…} ids and {@code ch-acme} / {@code ch-umbra}
 * slugs; the ids were renamed to {@code ce…} for an unrelated reason and the <em>slugs were left
 * behind</em>, where {@code execution.CaseHistoryEndpointTest} already owned them.
 * {@code uq_company_slug} then failed all eleven tests — in the full suite only, exactly the §19
 * shape, and with a stack trace pointing at an {@code INSERT INTO company} that looks like the base
 * class's fault. Every naturally-keyed fixture value needs the class prefix, not just the primary
 * keys.
 */
@DisplayName("Case-health recompute — the read model's first writer")
class CaseHealthRecomputeTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("ce000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("ce000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("ce001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("ce001000-0000-4000-8000-000000000002");
    private static final UUID ACME_SUITE = UUID.fromString("ce002000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_SUITE = UUID.fromString("ce002000-0000-4000-8000-000000000002");
    private static final UUID ACME_RUN = UUID.fromString("ce003000-0000-4000-8000-000000000001");
    /**
     * A second run, and it is not fixture padding.
     *
     * <p>{@code uq_execution_run_case} makes a case appear at most once per run, so "executed long
     * ago and again recently" is <em>structurally</em> two runs — there is no way to express it
     * otherwise. The first attempt at this fixture put both results in one run and the constraint
     * refused it, which is the schema being right about the domain.
     */
    private static final UUID ACME_OLD_RUN = UUID.fromString("ce003000-0000-4000-8000-000000000002");
    /**
     * A run CI posted — {@code source = 'INGESTED'}, which is the whole predicate of the automation
     * drift pair. §117 pinned that column to <em>the door the request came through</em>, so this is
     * what {@code IngestionService} writes and what nothing else does.
     */
    private static final UUID ACME_CI_RUN = UUID.fromString("ce003000-0000-4000-8000-000000000003");
    /** The same, long ago. Separate because {@code uq_execution_run_case} allows a case one row per run. */
    private static final UUID ACME_OLD_CI_RUN = UUID.fromString("ce003000-0000-4000-8000-000000000004");

    /** No execution row at all. */
    private static final UUID NEVER = UUID.fromString("ce004000-0000-4000-8000-000000000001");
    /** An execution row with no result — a planned run, not a performed one. */
    private static final UUID PLANNED = UUID.fromString("ce004000-0000-4000-8000-000000000002");
    /** Recorded well inside the window. */
    private static final UUID RECENT = UUID.fromString("ce004000-0000-4000-8000-000000000003");
    /** Recorded well outside it. */
    private static final UUID ANCIENT = UUID.fromString("ce004000-0000-4000-8000-000000000004");
    /** Recorded long ago AND recently — the newest must win. */
    private static final UUID REVISITED = UUID.fromString("ce004000-0000-4000-8000-000000000005");
    /** Soft-deleted, never executed. Must get no row at all. */
    private static final UUID REMOVED = UUID.fromString("ce004000-0000-4000-8000-000000000006");
    /** Umbra's only case. Never executed, and must stay invisible to Acme's recompute. */
    private static final UUID FOREIGN = UUID.fromString("ce004000-0000-4000-8000-000000000007");

    // ---- the last result (§166) -------------------------------------------------------------
    //
    // These share the two booleans' fixtures wherever the discrimination is the same one — NEVER,
    // PLANNED and REVISITED already express "no row", "a row with no result" and "two results, the
    // newest wins", and asserting the stored VALUE over them is what proves the five columns come
    // out of one statement rather than merely agreeing today. Only the two facts the booleans never
    // carried need fixtures of their own.

    /**
     * Executed once, with a status whose kind is pinned and a release version set.
     *
     * <p>The two things {@code never_executed} and {@code long_unexecuted} throw away. Without a
     * case whose kind is <em>known</em>, every assertion about {@code last_status_kind} would have
     * to accept whatever {@code LIMIT 1} handed the fixture, which is an assertion about nothing.
     */
    private static final UUID EXECUTED_IN_A_RELEASE =
            UUID.fromString("ce004000-0000-4000-8000-00000000001d");

    // ---- the automation-drift pair (M3 slice 5, plan §6c) ----------------------------------
    //
    // Every one of these is AUTOMATED-or-not crossed with an ingested result inside-or-outside the
    // window, and each is exercised ALONE — §117's ladder rule. A fixture where one case satisfies
    // two rungs passes against an implementation that only computes one of them.

    /** AUTOMATED, CI last reported outside the window. The signal's whole point. */
    private static final UUID CI_STOPPED = UUID.fromString("ce004000-0000-4000-8000-000000000008");
    /** AUTOMATED, CI reported inside the window. Clean. */
    private static final UUID CI_REPORTING = UUID.fromString("ce004000-0000-4000-8000-000000000009");
    /**
     * AUTOMATED, CI silent for 120 days, but a person ran it by hand 10 days ago.
     *
     * <p>The case that separates the two statements in {@code ExecutionFacts}. It is <em>not</em>
     * long-unexecuted (its newest recorded result is recent) and it <em>is</em> stale automation
     * (its newest INGESTED result is not) — so an implementation reading "the last result" for both
     * signals gets exactly one of the two answers wrong, whichever it picks.
     */
    private static final UUID CI_STOPPED_RUN_BY_HAND =
            UUID.fromString("ce004000-0000-4000-8000-00000000000a");
    /** AUTOMATED, CI has NEVER reported. D3's hole, asserted rather than left to be discovered. */
    private static final UUID CI_NEVER_REPORTED =
            UUID.fromString("ce004000-0000-4000-8000-00000000000b");
    /** MANUAL, taking CI results right now. §6c's second direction, as §6c words it. */
    private static final UUID UNDECLARED = UUID.fromString("ce004000-0000-4000-8000-00000000000c");
    /** MANUAL, took CI results 120 days ago and none since. Expired, by the same cutoff. */
    private static final UUID UNDECLARED_LAPSED =
            UUID.fromString("ce004000-0000-4000-8000-00000000000d");
    /** NOT_AUTOMATABLE, taking CI results. The state §6c did not name and the coverage denominator does. */
    private static final UUID UNDECLARED_IMPOSSIBLE =
            UUID.fromString("ce004000-0000-4000-8000-00000000000e");

    // ---- stale approval (§157, plan §6g) ----------------------------------------------------
    //
    // Every one of these is a lifecycle status crossed with a review history, and each is exercised
    // alone for §117's ladder reason. None of them has an execution row, so they all report
    // NEVER_EXECUTED too — asserted rather than filtered out, because a containsExactly that
    // quietly ignored a sibling signal would pass against an implementation that raised it wrongly.

    /** APPROVED at v1, edited twice since. The signal's whole point. */
    private static final UUID APPROVED_THEN_EDITED =
            UUID.fromString("ce004000-0000-4000-8000-00000000000f");
    /** APPROVED at the version it is still on. Clean. */
    private static final UUID APPROVED_AND_UNTOUCHED =
            UUID.fromString("ce004000-0000-4000-8000-000000000010");
    /**
     * {@code APPROVED} on the ladder, with no review row at all — which is <em>most</em> approved
     * cases, because §6g's opt-in is off by default and {@code ReviewService.moved} opens nothing
     * for a project that has not turned it on.
     */
    private static final UUID APPROVED_WITHOUT_A_REVIEW =
            UUID.fromString("ce004000-0000-4000-8000-000000000011");
    /**
     * A stale approval on the record and the case back in {@code DRAFT} — what
     * {@code EditAfterApproval.RE_REVIEW} produces on every edit, and what rescinding an approval
     * by hand produces once. The review row stands; the case is not claiming to be approved.
     */
    private static final UUID DEMOTED_AFTER_APPROVAL =
            UUID.fromString("ce004000-0000-4000-8000-000000000012");
    /** Rejected at v1, approved by nobody, and {@code APPROVED} on the ladder. A rejection is not a sign-off. */
    private static final UUID REJECTED_AT_AN_OLD_VERSION =
            UUID.fromString("ce004000-0000-4000-8000-000000000013");
    /** Approved at v1, edited, approved again at v3, and still on v3. Not stale — the newest sign-off covers it. */
    private static final UUID APPROVED_TWICE =
            UUID.fromString("ce004000-0000-4000-8000-000000000014");

    // ---- condition drift (§158, plan §6f) ---------------------------------------------------
    //
    // Every one of these is a version snapshot crossed with the state of the library entry it
    // names, and each is exercised alone for §117's ladder reason. None has an execution row, so
    // they all report NEVER_EXECUTED too — asserted rather than filtered out, for the reason the
    // stale-approval block gives.

    /** Its entry's body was edited after the snapshot was taken. The signal's whole point. */
    private static final UUID CONDITION_EDITED =
            UUID.fromString("ce004000-0000-4000-8000-000000000015");
    /** Its entry says exactly what the snapshot recorded. Clean. */
    private static final UUID CONDITION_UNCHANGED =
            UUID.fromString("ce004000-0000-4000-8000-000000000016");
    /** Same steps, different name. Drift — the title is on the screen the tester reads. */
    private static final UUID CONDITION_RETITLED =
            UUID.fromString("ce004000-0000-4000-8000-000000000017");
    /**
     * Its entry was soft-deleted. The loudest drift there is: {@code CaseConditions.forCase} drops
     * an attachment that no longer resolves, so the live case renders one instruction fewer than
     * the snapshot says it should — silently, and with nothing on the case itself changed.
     */
    private static final UUID CONDITION_DELETED =
            UUID.fromString("ce004000-0000-4000-8000-000000000018");
    /** Drift in the POST arm only — an implementation reading {@code preconditions} alone calls it clean. */
    private static final UUID POSTCONDITION_EDITED =
            UUID.fromString("ce004000-0000-4000-8000-000000000019");
    /** A snapshot with both arms empty. Clean, and the case the {@code coalesce} exists for. */
    private static final UUID CASE_WITHOUT_CONDITIONS =
            UUID.fromString("ce004000-0000-4000-8000-00000000001a");
    /** No {@code test_case_version} row at all — {@code CaseVersions}' v1 gap. Nothing to have drifted from. */
    private static final UUID CASE_WITHOUT_A_VERSION =
            UUID.fromString("ce004000-0000-4000-8000-00000000001b");
    /** v2's snapshot drifted, v3's does not. The newest snapshot is the one that decides. */
    private static final UUID DRIFT_IN_AN_OLD_VERSION =
            UUID.fromString("ce004000-0000-4000-8000-00000000001c");

    // ---- the documentation links (§167, plan §6i) -------------------------------------------
    //
    // The mirror, not a signal: case_doc_link is what TestCase.relatedDocuments is served from, and
    // these fixtures are one link each crossed with the one thing that decides whether it is
    // copied — §117's ladder rule again. Every one of them is a case with no execution, so they all
    // report NEVER_EXECUTED as well; the assertions read the mirror rather than the signal set.

    /** One link into a page whose content has not moved since the case was authored. Not stale. */
    private static final UUID LINKED = UUID.fromString("ce004000-0000-4000-8000-00000000001e");
    /** Linked to a page edited after the case's newest version. The signal's whole point. */
    private static final UUID LINKED_TO_A_MOVED_SPEC =
            UUID.fromString("ce004000-0000-4000-8000-00000000001f");
    /**
     * Two links, and the second one is to the long block.
     *
     * <p>A case with exactly one link cannot tell "the mirror copies the link" from "the mirror
     * copies <em>a</em> link", and the panel renders a list.
     */
    private static final UUID LINKED_TWICE = UUID.fromString("ce004000-0000-4000-8000-000000000020");
    /** Linked into a page somebody soft-deleted. Must vanish from the case. */
    private static final UUID LINKED_TO_A_DELETED_PAGE =
            UUID.fromString("ce004000-0000-4000-8000-000000000021");
    /**
     * Linked into a page of a <em>sibling project</em> — a row the write path refuses to create and
     * RLS happily returns, because a sibling project is the same company.
     */
    private static final UUID LINKED_ACROSS_PROJECTS =
            UUID.fromString("ce004000-0000-4000-8000-000000000022");
    /** Linked, soft-deleted. Its links must not be mirrored at all. */
    private static final UUID LINKED_AND_REMOVED =
            UUID.fromString("ce004000-0000-4000-8000-000000000023");
    /**
     * Linked, with no {@code test_case_version} row — {@code CaseVersions}' v1 gap.
     *
     * <p>The {@code coalesce} the staleness comparison falls back through. Without it the
     * comparison is NULL, and NULL into a {@code NOT NULL boolean} aborts the whole refresh — so
     * this fixture's failure mode is the entire class going red rather than one wrong answer.
     */
    private static final UUID LINKED_WITHOUT_A_VERSION =
            UUID.fromString("ce004000-0000-4000-8000-000000000024");
    /** Umbra's case, linked to Umbra's page. The company the DELETE must not reach. */
    private static final UUID FOREIGN_LINKED =
            UUID.fromString("ce004000-0000-4000-8000-000000000025");
    /**
     * §169's second arm — one {@code EXTERNAL_GIT} link and one {@code EXTERNAL_URL} link, on a
     * case that has a block link too.
     *
     * <p>All three on one case deliberately: the arms are a {@code UNION ALL}, and a case carrying
     * only external links could not tell "both arms ran" from "the first arm matched nothing".
     */
    private static final UUID LINKED_OUTSIDE =
            UUID.fromString("ce004000-0000-4000-8000-000000000026");
    /** A suite's external link. Mirrored by neither arm — {@code case_doc_link} is per case. */
    private static final UUID EXTERNALLY_LINKED_SUITE =
            UUID.fromString("ce004000-0000-4000-8000-000000000027");
    /** Soft-deleted, with an external link: the one predicate the second arm keeps. */
    private static final UUID LINKED_OUTSIDE_AND_REMOVED =
            UUID.fromString("ce004000-0000-4000-8000-000000000028");

    // ---- the two linkage signals (§178, plan §6i and §6o) -----------------------------------
    //
    // Three fixtures, and every other case in this class is already one of the two inputs: a case
    // with no link at all is UNLINKED, and the mirror block above supplies "linked and clean",
    // "linked and moved", "linked into a page nobody can see" and "linked from a suite". These
    // three are the discriminations no existing fixture can make.

    /**
     * Two links, one into a page that moved and one into a page that did not.
     *
     * <p>The {@code bool_or} fixture. {@code LINKED_TWICE} has two clean links and
     * {@code LINKED_TO_A_MOVED_SPEC} has one dirty one, so between them they cannot tell "any link
     * moved" from "the case's link moved" — an implementation reading one row, or folding with
     * {@code bool_and}, reports this case clean while its own panel marks a link stale.
     */
    private static final UUID LINKED_TO_ONE_MOVED_SPEC_OF_TWO =
            UUID.fromString("ce004000-0000-4000-8000-000000000029");
    /**
     * One {@code EXTERNAL_URL} link and nothing else.
     *
     * <p>{@code LINKED_OUTSIDE} carries a block link as well — deliberately, for the {@code UNION
     * ALL} — so it cannot show that an external target <em>alone</em> takes a case out of
     * {@code UNLINKED}. Its newest version is old enough that a staleness comparison wrongly applied
     * to this arm would fire.
     */
    private static final UUID LINKED_ONLY_OUTSIDE =
            UUID.fromString("ce004000-0000-4000-8000-00000000002a");
    /**
     * One link, and its relationship is {@code REFERENCES} — context, not coverage.
     *
     * <p>THE decision's fixture. This case contributes to no coverage marker and no matrix cell
     * ({@code BlockCoverageView} and {@code CoverageMatrixReport} both count {@code VERIFIES} only),
     * and it is <strong>not</strong> {@code UNLINKED} — because the chip renders inches from the
     * panel listing the link it would be denying. Argued at
     * {@code SpecLinkage.recomputeLinkageSignals}; a later "consistency" edit that filters the
     * relationship here fails on this case alone.
     */
    private static final UUID LINKED_BY_REFERENCE_ONLY =
            UUID.fromString("ce004000-0000-4000-8000-00000000002b");

    // ---- §181, the owner check ---------------------------------------------------------------
    //
    // THE FIRST PEOPLE THIS CLASS HAS EVER SEEDED. The review fixtures leave approved_by null and
    // say why: the derivation asks WHICH VERSION was signed off, never by whom. This signal asks
    // only about whom, so it is the first that cannot be expressed without accounts and
    // memberships — five of each, because the predicate has three terms and two of them have a
    // near neighbour that must NOT fire (an invitee is not an ex-colleague; an active member is
    // not a deactivated account).
    /** ACTIVE account, ACTIVE membership — the case that must stay clean. */
    private static final UUID OWNED_BY_A_COLLEAGUE =
            UUID.fromString("ce004000-0000-4000-8000-00000000002c");
    /** The instance-wide off switch: ACTIVE membership, DEACTIVATED account. */
    private static final UUID OWNED_BY_A_DEACTIVATED_ACCOUNT =
            UUID.fromString("ce004000-0000-4000-8000-00000000002d");
    /**
     * Offboarded — membership {@code REMOVED}, account untouched, which is exactly what
     * {@code MembershipRemovalService} writes and the only one of the three terms a running
     * deployment can currently reach.
     */
    private static final UUID OWNED_BY_AN_OFFBOARDED_COLLEAGUE =
            UUID.fromString("ce004000-0000-4000-8000-00000000002e");
    /**
     * Owned by an account with no membership row in this company at all.
     *
     * <p>Not reachable through the API — assignment goes through the company's own directory — and
     * seeded anyway, because it is what a hard-deleted membership, a restored backup or a
     * cross-company data repair leaves behind, and because it is the term {@code m.user_id IS NULL}
     * exists for. An implementation that only checked {@code status = 'REMOVED'} passes every other
     * test in this block.
     */
    private static final UUID OWNED_BY_A_STRANGER =
            UUID.fromString("ce004000-0000-4000-8000-00000000002f");
    /** Membership {@code INVITED} — somebody arriving, not somebody gone. Must stay clean. */
    private static final UUID OWNED_BY_AN_INVITEE =
            UUID.fromString("ce004000-0000-4000-8000-000000000030");
    /**
     * {@code owner_id} null, which most cases in this class already are — named here so the
     * assertion that an unowned case is not an orphan reads as a decision rather than as an
     * incidental property of a fixture seeded for something else.
     */
    private static final UUID UNOWNED = UUID.fromString("ce004000-0000-4000-8000-000000000031");
    /**
     * Umbra's case, owned by the same person Acme offboarded — the one fixture that can tell
     * "this owner is gone" from "this owner is gone <em>from this company</em>".
     */
    private static final UUID FOREIGN_OWNED =
            UUID.fromString("ce004000-0000-4000-8000-000000000032");

    // ---- §182, the completeness check --------------------------------------------------------
    //
    // Every case above is STRUCTURED with no steps, no expected outcome and no classification, so
    // by this signal's predicate the only thing keeping them clean is their lifecycle status —
    // which is the derivation's gate and therefore exactly the term a fixture must not lean on by
    // accident. The five APPROVED cases in the stale-approval block DO raise it, and their
    // assertions say so rather than filtering it out; that is the same call §178 made when
    // `unlinked` started firing on nearly all of them.

    /** APPROVED, one step with an expected result, one classification. The clean case. */
    private static final UUID COMPLETE = UUID.fromString("ce004000-0000-4000-8000-000000000033");
    /**
     * APPROVED, classified, states its overall outcome — and has no steps at all.
     *
     * <p>Not producible through the API: {@code CaseEdit.validate} refuses a STRUCTURED case with
     * an empty step list on both the create and the update path. Seeded because the predicate
     * carries the term anyway, for the reason at {@code CaseCompleteness.recomputeIncomplete} — a
     * validator is a rule about new writes and says nothing about rows already stored.
     */
    private static final UUID APPROVED_WITHOUT_STEPS =
            UUID.fromString("ce004000-0000-4000-8000-000000000034");
    /** APPROVED and classified, with a step — and nothing anywhere saying what should happen. */
    private static final UUID APPROVED_WITHOUT_AN_EXPECTED_RESULT =
            UUID.fromString("ce004000-0000-4000-8000-000000000035");
    /**
     * APPROVED and classified, steps with no expected result of their own, and a case-level
     * outcome. Clean — the two spellings are alternatives, not a checklist.
     */
    private static final UUID APPROVED_WITH_A_CASE_LEVEL_OUTCOME =
            UUID.fromString("ce004000-0000-4000-8000-000000000036");
    /**
     * The same, with both fields present and made of spaces.
     *
     * <p>{@code btrim} rather than {@code IS NOT NULL} is the whole of the difference, and a
     * whitespace-only field is what a rich-text editor leaves behind when somebody clears one.
     */
    private static final UUID APPROVED_WITH_A_BLANK_EXPECTED_RESULT =
            UUID.fromString("ce004000-0000-4000-8000-000000000037");
    /** APPROVED, steps, expected results — and no {@code case_classification} row. */
    private static final UUID APPROVED_WITHOUT_A_CLASSIFICATION =
            UUID.fromString("ce004000-0000-4000-8000-000000000038");
    /**
     * DRAFT, missing all three. Must stay clean: a draft missing things is a draft, and the gate
     * is what stops this signal firing on the whole of an active project's authoring queue.
     */
    private static final UUID A_DRAFT_MISSING_EVERYTHING =
            UUID.fromString("ce004000-0000-4000-8000-000000000039");
    /** DEPRECATED, missing all three. Retired cases are not going to be finished. */
    private static final UUID A_DEPRECATED_CASE_MISSING_EVERYTHING =
            UUID.fromString("ce004000-0000-4000-8000-00000000003a");
    /**
     * APPROVED, classified, {@code GHERKIN} with a written scenario and — legitimately — <em>no
     * {@code test_step} rows and no expected outcome</em>.
     *
     * <p>THE fixture of this group. A predicate that read {@code test_step} alone, which is the
     * obvious spelling of "has steps", reports every Gherkin case in the product as unfinished, and
     * every other assertion here passes while it does.
     */
    private static final UUID AN_APPROVED_SCENARIO =
            UUID.fromString("ce004000-0000-4000-8000-00000000003b");
    /** The same with an empty scenario — the Gherkin spelling of "no steps". */
    private static final UUID AN_APPROVED_EMPTY_SCENARIO =
            UUID.fromString("ce004000-0000-4000-8000-00000000003c");

    /** One MULTI dimension and one value, so a case can be classified without a trigger's help. */
    private static final UUID FEATURE_DIMENSION =
            UUID.fromString("ce009000-0000-4000-8000-000000000001");
    private static final UUID CHECKOUT =
            UUID.fromString("ce009000-0000-4000-8000-000000000002");

    /** ACTIVE account, ACTIVE membership in Acme. */
    private static final UUID COLLEAGUE = UUID.fromString("ce008000-0000-4000-8000-000000000001");
    /** DEACTIVATED account, and its Acme membership is left ACTIVE so the account is the cause. */
    private static final UUID DEACTIVATED_ACCOUNT =
            UUID.fromString("ce008000-0000-4000-8000-000000000002");
    /** INVITED account, INVITED membership — the near neighbour of REMOVED that must not fire. */
    private static final UUID INVITEE = UUID.fromString("ce008000-0000-4000-8000-000000000003");
    /** ACTIVE account, no membership row in either company. */
    private static final UUID STRANGER = UUID.fromString("ce008000-0000-4000-8000-000000000004");
    /**
     * ACTIVE account, {@code REMOVED} in Acme and {@code ACTIVE} in Umbra — one person, two
     * clients, which is plan §6s's whole point and the state the membership join's company
     * predicate is written for.
     */
    private static final UUID CONTRACTOR = UUID.fromString("ce008000-0000-4000-8000-000000000005");

    private static final UUID ACME_OTHER_PROJECT =
            UUID.fromString("ce001000-0000-4000-8000-000000000003");
    private static final UUID ACME_SPACE = UUID.fromString("ce007000-0000-4000-8000-000000000001");
    private static final UUID ACME_OTHER_SPACE =
            UUID.fromString("ce007000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SPACE = UUID.fromString("ce007000-0000-4000-8000-000000000003");

    /** The page every clean link points into. Its newest version predates the cases. */
    private static final UUID SPEC_PAGE = UUID.fromString("ce007001-0000-4000-8000-000000000001");
    /** Same, but its newest version was written after the case's. */
    private static final UUID MOVED_SPEC_PAGE =
            UUID.fromString("ce007001-0000-4000-8000-000000000002");
    private static final UUID DELETED_PAGE = UUID.fromString("ce007001-0000-4000-8000-000000000003");
    private static final UUID OTHER_PROJECT_PAGE =
            UUID.fromString("ce007001-0000-4000-8000-000000000004");
    private static final UUID UMBRA_PAGE = UUID.fromString("ce007001-0000-4000-8000-000000000005");

    private static final UUID SPEC_BLOCK = UUID.fromString("ce007002-0000-4000-8000-000000000001");
    /** Longer than the excerpt bound, and with newlines in it. */
    private static final UUID LONG_BLOCK = UUID.fromString("ce007002-0000-4000-8000-000000000002");
    /** A block with no words — a spacer somebody linked to. */
    private static final UUID EMPTY_BLOCK = UUID.fromString("ce007002-0000-4000-8000-000000000003");
    private static final UUID MOVED_BLOCK = UUID.fromString("ce007002-0000-4000-8000-000000000004");
    private static final UUID DELETED_PAGE_BLOCK =
            UUID.fromString("ce007002-0000-4000-8000-000000000005");
    private static final UUID OTHER_PROJECT_BLOCK =
            UUID.fromString("ce007002-0000-4000-8000-000000000006");
    private static final UUID UMBRA_BLOCK = UUID.fromString("ce007002-0000-4000-8000-000000000007");

    /** The link ids, so a test can withdraw one by naming it. */
    private static final UUID LINKED_LINK = UUID.fromString("ce007003-0000-4000-8000-000000000001");
    /**
     * The link {@code LinkageSignals} creates <em>during</em> a test, to prove the signal sees it on
     * the same tick.
     *
     * <p>A constant rather than a random id, and that is not tidiness: nothing in this class rolls
     * back between tests — {@link #seed()} re-inserts with {@code ON CONFLICT DO NOTHING} — so a link
     * made by one test with an unnamed id would still be there for the next one, and every assertion
     * about an unlinked case would then depend on JUnit's method order. {@code seed()} deletes this
     * id explicitly for that reason.
     */
    private static final UUID LATE_LINK = UUID.fromString("ce007003-0000-4000-8000-000000000004");
    private static final UUID GIT_LINK = UUID.fromString("ce007003-0000-4000-8000-000000000002");
    private static final UUID URL_LINK = UUID.fromString("ce007003-0000-4000-8000-000000000003");

    /**
     * The text of the block every excerpt assertion reads, and it is over the 160-character bound
     * on purpose — with a newline in it, because the mirror collapses whitespace exactly as
     * {@code reporting.CoverageMatrixReport} does and a fixture on one line could not tell.
     */
    private static final String LONG_STATEMENT =
            "The system shall decline a refund when the original capture is older than one hundred "
                    + "and eighty days,\nand shall record the decline reason against the payment "
                    + "for the finance team to audit later.";

    /** The library entry every clean case names, and the one the drifted snapshots were taken of. */
    private static final UUID STABLE_ENTRY = UUID.fromString("ce006000-0000-4000-8000-000000000001");
    /** Same id in the snapshot, different body in the library. */
    private static final UUID EDITED_ENTRY = UUID.fromString("ce006000-0000-4000-8000-000000000002");
    /** Same id, same body, different title. */
    private static final UUID RETITLED_ENTRY =
            UUID.fromString("ce006000-0000-4000-8000-000000000003");
    /** Same id, same content, {@code deleted_at} set — so no live row resolves it at all. */
    private static final UUID DELETED_ENTRY =
            UUID.fromString("ce006000-0000-4000-8000-000000000004");
    /** A {@code POST} entry, edited. */
    private static final UUID EDITED_POST_ENTRY =
            UUID.fromString("ce006000-0000-4000-8000-000000000005");

    /**
     * The two bodies the drift fixtures move between, as constants rather than literals at each
     * site.
     *
     * <p>Not tidiness: the whole comparison is "does the snapshot's text still match the library's",
     * so a fixture that spelled the <em>same</em> body twice with one character different would
     * report drift for a reason no test names, and every assertion in the block would still pass.
     */
    private static final List<String> STANDARD_USER =
            List.of("Open /login", "Sign in as qa@acme.test");
    private static final List<String> ADMIN_USER =
            List.of("Open /login", "Sign in as admin@acme.test");

    /**
     * Comfortably either side of the 90-day default rather than exactly on it — <strong>and either
     * side of the 14-day stale-automation default too</strong>, which is what lets one pair of
     * offsets drive both jobs. 10 days is inside both windows and 120 is outside both; nothing here
     * lands between 14 and 90, because a fixture that did would make one signal's answer depend on
     * the other's threshold.
     *
     * <p>The boundary itself is deliberately not asserted against a live {@link java.time.Clock}: a
     * test that inserts a result at exactly {@code now - 90d} and asserts a strict {@code <} is a
     * test whose result depends on how long the seed took. Pinning the boundary properly needs a
     * fixed clock, which is a different fixture; asserting it loosely here and precisely nowhere
     * would be worse than asserting the two sides, which is what actually discriminates the signal.
     */
    private static final Duration WELL_INSIDE = Duration.ofDays(10);
    private static final Duration WELL_OUTSIDE = Duration.ofDays(120);

    /**
     * Every boolean on {@code case_health}, which since §182 is every {@link HealthSignal} and
     * nothing else.
     *
     * <p>One list rather than two: {@link #signalsOf} reads it and
     * {@code Boundaries.otherSignalsAreNotClobbered} partitions it, and those two disagreeing is
     * how a column would quietly stop being asserted over anywhere.
     */
    private static final List<String> SIGNAL_COLUMNS = List.of(
            "outdated_spec_changed", "stale_approval", "condition_drift", "stale_automation",
            "never_executed", "long_unexecuted", "unlinked", "failing_repeatedly", "orphaned",
            "undeclared_automation", "incomplete");

    @Autowired
    private CaseHealthRecomputeService recompute;

    // The individual statements, for Boundaries.otherSignalsAreNotClobbered, which drives each one
    // alone. Every other test in this class goes through the service — the job's own ordering is
    // part of what is being asserted (SpecLinkage after the mirror refresh), and seven fields here
    // would be seven chances to assemble a recompute the product does not run.
    @Autowired
    private ExecutionFacts executionFacts;

    @Autowired
    private ApprovalFacts approvalFacts;

    @Autowired
    private ConditionDrift conditionDrift;

    @Autowired
    private SpecLinkage specLinkage;

    @Autowired
    private OwnerStanding ownerStanding;

    @Autowired
    private CaseCompleteness caseCompleteness;

    @Autowired
    private CaseHealthProperties thresholds;

    @Autowired
    private Clock clock;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        company(ACME, "cehealth-acme", "Acme", now);
        company(UMBRA, "cehealth-umbra", "Umbra", now);

        inCompany(ACME, () -> {
            seedProject(ACME_PROJECT, ACME, "CEHAC", "Acme Product", now);
            // A second project of the SAME company (§167): RLS returns its pages to a statement run
            // in Acme's context, so it is the only fixture that can prove the mirror's project
            // predicate does anything. Written here rather than beside its doc fixtures because
            // seedProject goes through JPA and therefore needs an open company — the SQL helpers
            // below do not.
            seedProject(ACME_OTHER_PROJECT, ACME, "CEHAC2", "Acme Other", now);
            return null;
        });
        inCompany(UMBRA, () -> {
            seedProject(UMBRA_PROJECT, UMBRA, "CEHUM", "Umbra Product", now);
            return null;
        });

        clearHealthAndExecutions();

        try (Connection acme = appConnection(ACME)) {
            // Without this the project has no case_status rows, every execution below would insert
            // status_id NULL, and EVERY case would report never-executed — a fixture that makes the
            // whole class pass for the wrong reason while looking like it discriminates.
            seedProjectDefaults(acme, ACME, ACME_PROJECT);
            suite(acme, ACME_SUITE, ACME, ACME_PROJECT, "Root");
            testCase(acme, NEVER, ACME, ACME_PROJECT, ACME_SUITE, "TC-1", 1, false, "MANUAL");
            testCase(acme, PLANNED, ACME, ACME_PROJECT, ACME_SUITE, "TC-2", 2, false, "MANUAL");
            testCase(acme, RECENT, ACME, ACME_PROJECT, ACME_SUITE, "TC-3", 3, false, "MANUAL");
            testCase(acme, ANCIENT, ACME, ACME_PROJECT, ACME_SUITE, "TC-4", 4, false, "MANUAL");
            testCase(acme, REVISITED, ACME, ACME_PROJECT, ACME_SUITE, "TC-5", 5, false, "MANUAL");
            testCase(acme, REMOVED, ACME, ACME_PROJECT, ACME_SUITE, "TC-6", 6, true, "MANUAL");
            testCase(acme, EXECUTED_IN_A_RELEASE, ACME, ACME_PROJECT, ACME_SUITE, "TC-28", 28,
                    false, "MANUAL");

            testCase(acme, CI_STOPPED, ACME, ACME_PROJECT, ACME_SUITE, "TC-7", 7, false,
                    "AUTOMATED");
            testCase(acme, CI_REPORTING, ACME, ACME_PROJECT, ACME_SUITE, "TC-8", 8, false,
                    "AUTOMATED");
            testCase(acme, CI_STOPPED_RUN_BY_HAND, ACME, ACME_PROJECT, ACME_SUITE, "TC-9", 9, false,
                    "AUTOMATED");
            testCase(acme, CI_NEVER_REPORTED, ACME, ACME_PROJECT, ACME_SUITE, "TC-10", 10, false,
                    "AUTOMATED");
            testCase(acme, UNDECLARED, ACME, ACME_PROJECT, ACME_SUITE, "TC-11", 11, false, "MANUAL");
            testCase(acme, UNDECLARED_LAPSED, ACME, ACME_PROJECT, ACME_SUITE, "TC-12", 12, false,
                    "MANUAL");
            testCase(acme, UNDECLARED_IMPOSSIBLE, ACME, ACME_PROJECT, ACME_SUITE, "TC-13", 13, false,
                    "NOT_AUTOMATABLE");

            // ---- stale approval ---------------------------------------------------------------
            authoredCase(acme, APPROVED_THEN_EDITED, ACME_PROJECT, "TC-14", 14, "APPROVED", 3);
            approvedReview(acme, ACME_PROJECT, APPROVED_THEN_EDITED, 1, "ce005000-0000-4000-8000-000000000001");

            authoredCase(acme, APPROVED_AND_UNTOUCHED, ACME_PROJECT, "TC-15", 15, "APPROVED", 2);
            approvedReview(acme, ACME_PROJECT, APPROVED_AND_UNTOUCHED, 2, "ce005000-0000-4000-8000-000000000002");

            authoredCase(acme, APPROVED_WITHOUT_A_REVIEW, ACME_PROJECT, "TC-16", 16, "APPROVED", 4);

            authoredCase(acme, DEMOTED_AFTER_APPROVAL, ACME_PROJECT, "TC-17", 17, "DRAFT", 3);
            approvedReview(acme, ACME_PROJECT, DEMOTED_AFTER_APPROVAL, 1, "ce005000-0000-4000-8000-000000000003");

            authoredCase(acme, REJECTED_AT_AN_OLD_VERSION, ACME_PROJECT, "TC-18", 18, "APPROVED", 3);
            review(acme, ACME_PROJECT, REJECTED_AT_AN_OLD_VERSION, 1, "REJECTED",
                    "ce005000-0000-4000-8000-000000000004");

            authoredCase(acme, APPROVED_TWICE, ACME_PROJECT, "TC-19", 19, "APPROVED", 3);
            approvedReview(acme, ACME_PROJECT, APPROVED_TWICE, 1, "ce005000-0000-4000-8000-000000000005");
            approvedReview(acme, ACME_PROJECT, APPROVED_TWICE, 3, "ce005000-0000-4000-8000-000000000006");

            // ---- condition drift --------------------------------------------------------------
            //
            // No case_condition rows anywhere in this block, and that is the fixture stating the
            // derivation's actual shape rather than a convenient shortcut. The comparison is
            // between the SNAPSHOT and the LIBRARY; the live attachment set is not a term in it,
            // because attaching or detaching is authored content on CaseEdit and therefore writes
            // a new version whose snapshot re-baselines. Seeding attachments would assert a
            // dependency the statement does not have.
            condition(acme, STABLE_ENTRY, "Log in as standard user", "PRE", STANDARD_USER, false);
            condition(acme, EDITED_ENTRY, "Log in as standard user", "PRE", ADMIN_USER, false);
            condition(acme, RETITLED_ENTRY, "Seed billing data (v2)", "PRE", STANDARD_USER, false);
            condition(acme, DELETED_ENTRY, "Log in as standard user", "PRE", STANDARD_USER, true);
            condition(acme, EDITED_POST_ENTRY, "Tear down the tenant", "POST", ADMIN_USER, false);

            authoredCase(acme, CONDITION_EDITED, ACME_PROJECT, "TC-20", 20, "DRAFT", 1);
            version(acme, CONDITION_EDITED, 1, snapshot(
                    entry(EDITED_ENTRY, "Log in as standard user", STANDARD_USER), ""));

            authoredCase(acme, CONDITION_UNCHANGED, ACME_PROJECT, "TC-21", 21, "DRAFT", 1);
            version(acme, CONDITION_UNCHANGED, 1, snapshot(
                    entry(STABLE_ENTRY, "Log in as standard user", STANDARD_USER), ""));

            authoredCase(acme, CONDITION_RETITLED, ACME_PROJECT, "TC-22", 22, "DRAFT", 1);
            version(acme, CONDITION_RETITLED, 1, snapshot(
                    entry(RETITLED_ENTRY, "Seed billing data", STANDARD_USER), ""));

            authoredCase(acme, CONDITION_DELETED, ACME_PROJECT, "TC-23", 23, "DRAFT", 1);
            version(acme, CONDITION_DELETED, 1, snapshot(
                    entry(DELETED_ENTRY, "Log in as standard user", STANDARD_USER), ""));

            authoredCase(acme, POSTCONDITION_EDITED, ACME_PROJECT, "TC-24", 24, "DRAFT", 1);
            version(acme, POSTCONDITION_EDITED, 1, snapshot(
                    entry(STABLE_ENTRY, "Log in as standard user", STANDARD_USER),
                    entry(EDITED_POST_ENTRY, "Tear down the tenant", STANDARD_USER)));

            authoredCase(acme, CASE_WITHOUT_CONDITIONS, ACME_PROJECT, "TC-25", 25, "DRAFT", 1);
            version(acme, CASE_WITHOUT_CONDITIONS, 1, snapshot("", ""));

            // No version() call at all — the v1 gap, which is every case authored before §6j's
            // write path existed.
            authoredCase(acme, CASE_WITHOUT_A_VERSION, ACME_PROJECT, "TC-26", 26, "DRAFT", 1);

            authoredCase(acme, DRIFT_IN_AN_OLD_VERSION, ACME_PROJECT, "TC-27", 27, "DRAFT", 2);
            version(acme, DRIFT_IN_AN_OLD_VERSION, 1, snapshot(
                    entry(EDITED_ENTRY, "Log in as standard user", STANDARD_USER), ""));
            version(acme, DRIFT_IN_AN_OLD_VERSION, 2, snapshot(
                    entry(EDITED_ENTRY, "Log in as standard user", ADMIN_USER), ""));

            run(acme, ACME_RUN, ACME, ACME_PROJECT, "TR-1", 1, "MANUAL");
            run(acme, ACME_OLD_RUN, ACME, ACME_PROJECT, "TR-2", 2, "MANUAL");
            run(acme, ACME_CI_RUN, ACME, ACME_PROJECT, "TR-3", 3, "INGESTED");
            run(acme, ACME_OLD_CI_RUN, ACME, ACME_PROJECT, "TR-4", 4, "INGESTED");

            // The discrimination that matters most: a row, no result.
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, PLANNED, null);
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, RECENT, now.minus(WELL_INSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, ANCIENT, now.minus(WELL_OUTSIDE));
            // Two results across two runs. Inserted oldest-last, so "the last row wins" and "any
            // row wins" both produce the stale answer that theNewestResultWins() rejects.
            //
            // The KINDS are pinned in opposite directions for §166: the stale row is the passing
            // one, so an implementation that takes the wrong row reports a green case rather than
            // merely an old date. A test whose two candidate rows carry the same value cannot tell
            // "the newest wins" from "there was only ever one answer".
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, REVISITED, now.minus(WELL_INSIDE),
                    "FAILED", null);
            execution(acme, ACME, ACME_PROJECT, ACME_OLD_RUN, REVISITED, now.minus(WELL_OUTSIDE),
                    "PASSED", "1.0.0");

            // The two facts the booleans never carried: a pinned kind and a release version.
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, EXECUTED_IN_A_RELEASE,
                    now.minus(WELL_INSIDE), "BLOCKED", "4.2.0");

            // ---- the drift pair --------------------------------------------------------------
            execution(acme, ACME, ACME_PROJECT, ACME_OLD_CI_RUN, CI_STOPPED, now.minus(WELL_OUTSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_CI_RUN, CI_REPORTING, now.minus(WELL_INSIDE));
            // Old from CI, recent by hand. The MANUAL row is the one that must NOT count.
            execution(acme, ACME, ACME_PROJECT, ACME_OLD_CI_RUN, CI_STOPPED_RUN_BY_HAND,
                    now.minus(WELL_OUTSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_RUN, CI_STOPPED_RUN_BY_HAND,
                    now.minus(WELL_INSIDE));
            // Declared AUTOMATED, and CI has never once posted for it.
            execution(acme, ACME, ACME_PROJECT, ACME_OLD_RUN, CI_NEVER_REPORTED,
                    now.minus(WELL_OUTSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_CI_RUN, UNDECLARED, now.minus(WELL_INSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_OLD_CI_RUN, UNDECLARED_LAPSED,
                    now.minus(WELL_OUTSIDE));
            execution(acme, ACME, ACME_PROJECT, ACME_CI_RUN, UNDECLARED_IMPOSSIBLE,
                    now.minus(WELL_INSIDE));

            // ---- documentation links (§167) --------------------------------------------------
            //
            // The staleness comparison is newest page version against newest case version, so BOTH
            // sides are dated explicitly here. testCase() writes v1 at now(); leaving the page's
            // version at now() as well would put the two within microseconds of each other and make
            // every assertion in the block depend on insert order.
            space(acme, ACME_SPACE, ACME, ACME_PROJECT, "Acme docs");
            page(acme, SPEC_PAGE, ACME, ACME_SPACE, ACME_PROJECT, "DOC-1", 1, "Checkout spec",
                    false);
            pageVersion(acme, SPEC_PAGE, 1, now.minus(WELL_OUTSIDE));
            block(acme, SPEC_BLOCK, ACME, SPEC_PAGE, 0, "A refund may be issued within 180 days.");
            block(acme, LONG_BLOCK, ACME, SPEC_PAGE, 1, LONG_STATEMENT);
            block(acme, EMPTY_BLOCK, ACME, SPEC_PAGE, 2, "");

            page(acme, MOVED_SPEC_PAGE, ACME, ACME_SPACE, ACME_PROJECT, "DOC-2", 2, "Payments spec",
                    false);
            // Edited five days ago; the cases below were authored ten days ago.
            pageVersion(acme, MOVED_SPEC_PAGE, 1, now.minus(WELL_OUTSIDE));
            pageVersion(acme, MOVED_SPEC_PAGE, 2, now.minus(Duration.ofDays(5)));
            block(acme, MOVED_BLOCK, ACME, MOVED_SPEC_PAGE, 0, "Captures expire after 7 days.");

            page(acme, DELETED_PAGE, ACME, ACME_SPACE, ACME_PROJECT, "DOC-3", 3, "Withdrawn spec",
                    true);
            pageVersion(acme, DELETED_PAGE, 1, now.minus(WELL_OUTSIDE));
            block(acme, DELETED_PAGE_BLOCK, ACME, DELETED_PAGE, 0, "Superseded statement.");

            // The sibling project itself is seeded above, through JPA, where a company is open.
            space(acme, ACME_OTHER_SPACE, ACME, ACME_OTHER_PROJECT, "Other docs");
            page(acme, OTHER_PROJECT_PAGE, ACME, ACME_OTHER_SPACE, ACME_OTHER_PROJECT, "DOC-4", 4,
                    "Another product's spec", false);
            pageVersion(acme, OTHER_PROJECT_PAGE, 1, now.minus(WELL_OUTSIDE));
            block(acme, OTHER_PROJECT_BLOCK, ACME, OTHER_PROJECT_PAGE, 0, "Not this product.");

            authoredCase(acme, LINKED, ACME_PROJECT, "TC-29", 29, "DRAFT", 1);
            datedVersion(acme, LINKED, now.minus(WELL_INSIDE));
            link(acme, LINKED_LINK, ACME, LINKED, SPEC_BLOCK, "VERIFIES");

            authoredCase(acme, LINKED_TO_A_MOVED_SPEC, ACME_PROJECT, "TC-30", 30, "DRAFT", 1);
            datedVersion(acme, LINKED_TO_A_MOVED_SPEC, now.minus(WELL_INSIDE));
            link(acme, UUID.randomUUID(), ACME, LINKED_TO_A_MOVED_SPEC, MOVED_BLOCK, "VERIFIES");

            authoredCase(acme, LINKED_TWICE, ACME_PROJECT, "TC-31", 31, "DRAFT", 1);
            datedVersion(acme, LINKED_TWICE, now.minus(WELL_INSIDE));
            link(acme, UUID.randomUUID(), ACME, LINKED_TWICE, LONG_BLOCK, "DERIVED_FROM");
            link(acme, UUID.randomUUID(), ACME, LINKED_TWICE, EMPTY_BLOCK, "REFERENCES");

            authoredCase(acme, LINKED_TO_A_DELETED_PAGE, ACME_PROJECT, "TC-32", 32, "DRAFT", 1);
            datedVersion(acme, LINKED_TO_A_DELETED_PAGE, now.minus(WELL_INSIDE));
            // Unreachable through the API: DocPageWriteService#delete answers 409 WouldBreak when
            // anything links to the page's blocks, so only a fixture can produce this row. The
            // predicate is written for the day that guard changes or a project is hard-deleted.
            link(acme, UUID.randomUUID(), ACME, LINKED_TO_A_DELETED_PAGE, DELETED_PAGE_BLOCK,
                    "VERIFIES");

            authoredCase(acme, LINKED_ACROSS_PROJECTS, ACME_PROJECT, "TC-33", 33, "DRAFT", 1);
            datedVersion(acme, LINKED_ACROSS_PROJECTS, now.minus(WELL_INSIDE));
            link(acme, UUID.randomUUID(), ACME, LINKED_ACROSS_PROJECTS, OTHER_PROJECT_BLOCK,
                    "VERIFIES");

            testCase(acme, LINKED_AND_REMOVED, ACME, ACME_PROJECT, ACME_SUITE, "TC-34", 34, true,
                    "MANUAL");
            link(acme, UUID.randomUUID(), ACME, LINKED_AND_REMOVED, SPEC_BLOCK, "VERIFIES");

            // No datedVersion() and no version(): the v1 gap, so the comparison falls back to the
            // case's created_at, which is now() and therefore newer than every page above.
            authoredCase(acme, LINKED_WITHOUT_A_VERSION, ACME_PROJECT, "TC-35", 35, "DRAFT", 1);
            link(acme, UUID.randomUUID(), ACME, LINKED_WITHOUT_A_VERSION, SPEC_BLOCK, "VERIFIES");

            // §169 — the two kinds V1 designed and ExternalLinkService finally writes. Written as
            // rows rather than driven through the endpoint for seedCoverageFixtures' reason one
            // class over: what is under test here is the statement that mirrors them.
            authoredCase(acme, LINKED_OUTSIDE, ACME_PROJECT, "TC-36", 36, "DRAFT", 1);
            datedVersion(acme, LINKED_OUTSIDE, now.minus(WELL_INSIDE));
            link(acme, UUID.randomUUID(), ACME, LINKED_OUTSIDE, SPEC_BLOCK, "VERIFIES");
            gitLink(acme, GIT_LINK, ACME, "TEST_CASE", LINKED_OUTSIDE);
            urlLink(acme, URL_LINK, ACME, "TEST_CASE", LINKED_OUTSIDE);

            authoredCase(acme, EXTERNALLY_LINKED_SUITE, ACME_PROJECT, "TC-37", 37, "DRAFT", 1);
            // The source is the SUITE, not the case above — that case exists only so the assertion
            // has something to be empty about.
            gitLink(acme, UUID.randomUUID(), ACME, "SUITE", ACME_SUITE);

            testCase(acme, LINKED_OUTSIDE_AND_REMOVED, ACME, ACME_PROJECT, ACME_SUITE, "TC-38", 38,
                    true, "MANUAL");
            urlLink(acme, UUID.randomUUID(), ACME, "TEST_CASE", LINKED_OUTSIDE_AND_REMOVED);

            // ---- the linkage signals (§178) --------------------------------------------------
            authoredCase(acme, LINKED_TO_ONE_MOVED_SPEC_OF_TWO, ACME_PROJECT, "TC-39", 39,
                    "DRAFT", 1);
            datedVersion(acme, LINKED_TO_ONE_MOVED_SPEC_OF_TWO, now.minus(WELL_INSIDE));
            link(acme, UUID.randomUUID(), ACME, LINKED_TO_ONE_MOVED_SPEC_OF_TWO, SPEC_BLOCK,
                    "VERIFIES");
            // DERIVED_FROM, and that is the second half of THE decision rather than variety: the
            // stale link is the one whose relationship is NOT a coverage claim, so this fixture
            // fails if somebody narrows the OUTDATED_SPEC_CHANGED term to VERIFIES — the mirror of
            // the edit LINKED_BY_REFERENCE_ONLY guards on the UNLINKED term. Written this way
            // because the first draft made both links VERIFIES, at which point that mutation could
            // not fail and the queue row for it would have been unfalsifiable.
            link(acme, UUID.randomUUID(), ACME, LINKED_TO_ONE_MOVED_SPEC_OF_TWO, MOVED_BLOCK,
                    "DERIVED_FROM");

            authoredCase(acme, LINKED_ONLY_OUTSIDE, ACME_PROJECT, "TC-40", 40, "DRAFT", 1);
            datedVersion(acme, LINKED_ONLY_OUTSIDE, now.minus(WELL_INSIDE));
            urlLink(acme, UUID.randomUUID(), ACME, "TEST_CASE", LINKED_ONLY_OUTSIDE);

            authoredCase(acme, LINKED_BY_REFERENCE_ONLY, ACME_PROJECT, "TC-41", 41, "DRAFT", 1);
            datedVersion(acme, LINKED_BY_REFERENCE_ONLY, now.minus(WELL_INSIDE));
            link(acme, UUID.randomUUID(), ACME, LINKED_BY_REFERENCE_ONLY, SPEC_BLOCK, "REFERENCES");

            // ---- the owner check (§181) ------------------------------------------------------
            //
            // People and memberships are the only fixtures in this class that survive
            // clearHealthAndExecutions() — app_user is instance-level and company_membership names
            // no case — so both helpers UPDATE the status on conflict rather than DO NOTHING. Every
            // other fixture here is re-inserted from nothing each time and can therefore leave the
            // seed alone; these two would otherwise carry one test's mutation into the next, which
            // is precisely the failure theSignalSelfHeals() creates on purpose.
            person(acme, COLLEAGUE, "cehealth-colleague@acme.test", "Cass Ollie", "ACTIVE");
            person(acme, DEACTIVATED_ACCOUNT, "cehealth-gone@acme.test", "Dee Activated",
                    "DEACTIVATED");
            person(acme, INVITEE, "cehealth-invitee@acme.test", "Ivy Newcomer", "INVITED");
            person(acme, STRANGER, "cehealth-stranger@acme.test", "Stan Ranger", "ACTIVE");
            person(acme, CONTRACTOR, "cehealth-contractor@acme.test", "Con Tractor", "ACTIVE");

            membership(acme, ACME, COLLEAGUE, "ACTIVE");
            // ACTIVE here on purpose: the offboarding path and the account path must be separable,
            // and a fixture that removed the membership too would pass against an implementation
            // that never reads app_user at all.
            membership(acme, ACME, DEACTIVATED_ACCOUNT, "ACTIVE");
            membership(acme, ACME, INVITEE, "INVITED");
            membership(acme, ACME, CONTRACTOR, "REMOVED");
            // STRANGER gets no row in either company — that is the fixture.

            ownedCase(acme, OWNED_BY_A_COLLEAGUE, ACME, ACME_PROJECT, ACME_SUITE, "TC-42", 42,
                    COLLEAGUE);
            ownedCase(acme, OWNED_BY_A_DEACTIVATED_ACCOUNT, ACME, ACME_PROJECT, ACME_SUITE,
                    "TC-43", 43, DEACTIVATED_ACCOUNT);
            ownedCase(acme, OWNED_BY_AN_OFFBOARDED_COLLEAGUE, ACME, ACME_PROJECT, ACME_SUITE,
                    "TC-44", 44, CONTRACTOR);
            ownedCase(acme, OWNED_BY_A_STRANGER, ACME, ACME_PROJECT, ACME_SUITE, "TC-45", 45,
                    STRANGER);
            ownedCase(acme, OWNED_BY_AN_INVITEE, ACME, ACME_PROJECT, ACME_SUITE, "TC-46", 46,
                    INVITEE);
            ownedCase(acme, UNOWNED, ACME, ACME_PROJECT, ACME_SUITE, "TC-47", 47, null);

            // ---- the completeness check (§182) -----------------------------------------------
            //
            // One dimension and one value, MULTI rather than SINGLE: V2's integrity trigger runs
            // the cardinality branch only for SINGLE, and nothing in this group is about that
            // trigger. Seeded rather than taken from tcms_seed_project_defaults so the fixture
            // states what it depends on.
            dimension(acme, FEATURE_DIMENSION, ACME, ACME_PROJECT, "cehealth-feature", "MULTI");
            classificationValue(acme, CHECKOUT, ACME, FEATURE_DIMENSION, "checkout", "Checkout");

            authoredCase(acme, COMPLETE, ACME_PROJECT, "TC-48", 48, "APPROVED", 1);
            step(acme, "ce00a000-0000-4000-8000-000000000001", COMPLETE, 0, "Pay", "A receipt");
            classify(acme, COMPLETE);

            authoredCase(acme, APPROVED_WITHOUT_STEPS, ACME_PROJECT, "TC-49", 49, "APPROVED", 1);
            expectedOutcome(acme, APPROVED_WITHOUT_STEPS, "The order is placed");
            classify(acme, APPROVED_WITHOUT_STEPS);

            authoredCase(acme, APPROVED_WITHOUT_AN_EXPECTED_RESULT, ACME_PROJECT, "TC-50", 50,
                    "APPROVED", 1);
            step(acme, "ce00a000-0000-4000-8000-000000000002",
                    APPROVED_WITHOUT_AN_EXPECTED_RESULT, 0, "Pay", null);
            classify(acme, APPROVED_WITHOUT_AN_EXPECTED_RESULT);

            authoredCase(acme, APPROVED_WITH_A_CASE_LEVEL_OUTCOME, ACME_PROJECT, "TC-51", 51,
                    "APPROVED", 1);
            step(acme, "ce00a000-0000-4000-8000-000000000003",
                    APPROVED_WITH_A_CASE_LEVEL_OUTCOME, 0, "Pay", null);
            expectedOutcome(acme, APPROVED_WITH_A_CASE_LEVEL_OUTCOME, "The order is placed");
            classify(acme, APPROVED_WITH_A_CASE_LEVEL_OUTCOME);

            authoredCase(acme, APPROVED_WITH_A_BLANK_EXPECTED_RESULT, ACME_PROJECT, "TC-52", 52,
                    "APPROVED", 1);
            step(acme, "ce00a000-0000-4000-8000-000000000004",
                    APPROVED_WITH_A_BLANK_EXPECTED_RESULT, 0, "Pay", "   ");
            expectedOutcome(acme, APPROVED_WITH_A_BLANK_EXPECTED_RESULT, "  ");
            classify(acme, APPROVED_WITH_A_BLANK_EXPECTED_RESULT);

            authoredCase(acme, APPROVED_WITHOUT_A_CLASSIFICATION, ACME_PROJECT, "TC-53", 53,
                    "APPROVED", 1);
            step(acme, "ce00a000-0000-4000-8000-000000000005",
                    APPROVED_WITHOUT_A_CLASSIFICATION, 0, "Pay", "A receipt");

            authoredCase(acme, A_DRAFT_MISSING_EVERYTHING, ACME_PROJECT, "TC-54", 54, "DRAFT", 1);
            authoredCase(acme, A_DEPRECATED_CASE_MISSING_EVERYTHING, ACME_PROJECT, "TC-55", 55,
                    "DEPRECATED", 1);

            authoredCase(acme, AN_APPROVED_SCENARIO, ACME_PROJECT, "TC-56", 56, "APPROVED", 1);
            scenario(acme, AN_APPROVED_SCENARIO, "Given a basket\nWhen I pay\nThen I get a receipt");
            classify(acme, AN_APPROVED_SCENARIO);

            authoredCase(acme, AN_APPROVED_EMPTY_SCENARIO, ACME_PROJECT, "TC-57", 57, "APPROVED", 1);
            scenario(acme, AN_APPROVED_EMPTY_SCENARIO, "   ");
            classify(acme, AN_APPROVED_EMPTY_SCENARIO);
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT, "Root");
            testCase(umbra, FOREIGN, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-1", 1, false, "MANUAL");

            // Umbra's own linked case — what Acme's unqualified DELETE FROM case_doc_link must not
            // be able to reach.
            testCase(umbra, FOREIGN_LINKED, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-2", 2, false,
                    "MANUAL");
            space(umbra, UMBRA_SPACE, UMBRA, UMBRA_PROJECT, "Umbra docs");
            page(umbra, UMBRA_PAGE, UMBRA, UMBRA_SPACE, UMBRA_PROJECT, "DOC-1", 1, "Umbra spec",
                    false);
            pageVersion(umbra, UMBRA_PAGE, 1, now.minus(WELL_OUTSIDE));
            block(umbra, UMBRA_BLOCK, UMBRA, UMBRA_PAGE, 0, "Umbra's statement.");
            link(umbra, UUID.randomUUID(), UMBRA, FOREIGN_LINKED, UMBRA_BLOCK, "VERIFIES");

            // §181 — the same account Acme removed, working here. The membership row is Umbra's and
            // is written on Umbra's connection, because company_membership is under FORCE ROW LEVEL
            // SECURITY and an insert on Acme's connection would be refused by the policy rather
            // than land in the wrong company.
            membership(umbra, UMBRA, CONTRACTOR, "ACTIVE");
            ownedCase(umbra, FOREIGN_OWNED, UMBRA, UMBRA_PROJECT, UMBRA_SUITE, "TC-3", 3,
                    CONTRACTOR);
        }

        // Nothing here rolls back, so a link one test MAKES outlives it. LATE_LINK is the only such
        // row and this is what keeps every "this case is unlinked" assertion independent of the
        // order JUnit happens to run the methods in — §19's fixture-collision failure arriving
        // through time rather than through an id.
        withdrawLink(ACME, LATE_LINK);
    }

    // =====================================================================

    @Nested
    @DisplayName("The two execution signals this job owns")
    class Signals {

        @Test
        @DisplayName("a case with no execution row at all is NEVER_EXECUTED")
        void noExecutionsAtAll() {
            recomputeFor(ACME);
            assertThat(signalsOf(NEVER)).containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a case whose only execution has no result is STILL never executed")
        void aPlannedRunIsNotAnExecution() {
            // THE defect this class exists for. Creating a run writes one empty execution per case
            // in scope (§42). If the predicate were "an execution row exists", planning work would
            // mark it done — NEVER_EXECUTED would fall to zero across a project the day somebody
            // scheduled a run, and the dashboard would report improvement caused by nothing.
            recomputeFor(ACME);
            assertThat(signalsOf(PLANNED))
                    .as("an execution with status_id NULL is work outstanding, not history")
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a recently recorded case raises nothing this group owns")
        void recentlyExecuted() {
            // "raises nothing" until §178, and the change is the fixture's rather than the
            // statement's: this case is linked to no specification, which is now a signal. Written
            // out rather than filtered, for the reason at the class's UNLINKED note.
            recomputeFor(ACME);
            assertThat(signalsOf(RECENT)).containsExactly("unlinked");
        }

        @Test
        @DisplayName("a case last recorded outside the window is LONG_UNEXECUTED, not NEVER")
        void executedLongAgo() {
            // Both, or the wrong one, is the plausible failure: "never executed" and "not executed
            // lately" are one predicate away from each other, and a case that HAS been run is not
            // one that never has. The panel would show the same case under two headings.
            recomputeFor(ACME);
            assertThat(signalsOf(ANCIENT)).containsExactly("long_unexecuted", "unlinked");
        }

        @Test
        @DisplayName("the NEWEST result decides, not any result")
        void theNewestResultWins() {
            // Executed 120 days ago and again 10 days ago. Anything other than MAX(executed_at)
            // reports the actively-maintained case as stale — inverting the signal for exactly the
            // cases somebody is looking after.
            recomputeFor(ACME);
            assertThat(signalsOf(REVISITED)).containsExactly("unlinked");
        }

        @Test
        @DisplayName("a soft-deleted case gets no row at all")
        void softDeletedCasesAreNotDerived() {
            recomputeFor(ACME);
            assertThat(healthRowExists(ACME, REMOVED))
                    .as("a deleted case is absent from every dashboard count; a health row for it "
                            + "would be a signal with nothing to navigate to")
                    .isFalse();
        }
    }

    // =====================================================================

    /**
     * The last result — §166, the same statement's third output and the first one that is a
     * <em>value</em> rather than a signal.
     *
     * <h2>What these are guarding that the two booleans above are not</h2>
     *
     * <p>{@code TestCaseSummary.lastResult} was declared in {@code openapi.yaml} from M0 and served
     * by nothing, and the SPA renders its absence as "Never run" — so every case in the product
     * read "Never run", executed ones included, from the day {@code execution} shipped (§117) until
     * §166. The failures worth catching here are the ones that would restore some version of that
     * silence, and every one of them <strong>looks like data</strong>:
     *
     * <ol>
     *   <li><strong>A planned run counted as a result.</strong> The value side of the predicate the
     *       booleans already guard — §42 writes one empty execution per case when a run is created,
     *       so "has a row" would put a status chip on a case nobody has run.</li>
     *   <li><strong>The wrong execution winning.</strong> {@code REVISITED}'s two rows carry
     *       <em>opposite</em> kinds on purpose: taking the stale row reports a green case, not
     *       merely an old date, and a fixture whose candidates agreed could not tell the two apart.
     *       This is the assertion {@code max(executed_at)} → {@code ORDER BY … LIMIT 1} had to keep
     *       true through a rewrite.</li>
     *   <li><strong>The five columns drifting apart.</strong> {@code never_executed},
     *       {@code long_unexecuted} and these three are one fact, which is the entire argument for
     *       them sharing a row (V45). {@link #theBooleanAndTheValueCannotDisagree} asserts it over
     *       every case in the company rather than at the two or three where it is interesting,
     *       because the failure mode is a case nobody thought about.</li>
     *   <li><strong>A result outliving its execution.</strong> {@code EXCLUDED} carries the null
     *       out of the {@code LEFT JOIN LATERAL} on update, and the plausible-looking edit that
     *       breaks it (updating these columns only when non-null) leaves a deleted run's verdict on
     *       the screen forever. Asserted by removing rows and recomputing, which is the only way to
     *       reach that branch.</li>
     * </ol>
     *
     * <p><strong>What is deliberately not asserted:</strong> the {@code id DESC} tiebreak between
     * two executions recorded in the same microsecond. It exists so the value does not flicker
     * between recomputes, and a test for it cannot fail — without the tiebreak Postgres may return
     * either row, so an assertion naming one would be flaky rather than red, and an assertion that
     * two recomputes agree passes on a small fixture whichever way the plan goes. Recorded in the
     * mutation queue instead, where "no test can catch this" is a legitimate entry.
     */
    @Nested
    @DisplayName("The last result — the value the same statement carries (§166)")
    class TheLastResult {

        @Test
        @DisplayName("a case with no execution row at all has no last result")
        void noExecutionsAtAll() {
            recomputeFor(ACME);
            assertThat(lastResultOf(NEVER))
                    .isEqualTo(new StoredLastResult(null, null, null, true));
        }

        @Test
        @DisplayName("a planned-but-unrecorded execution is not a last result")
        void aPlannedRunIsNotAResult() {
            // The value side of the booleans' most important discrimination. If "has a row" were
            // the predicate here, planning a run would put a status chip on every case in it — the
            // grid would show a verdict for work nobody has done, which is worse than the "Never
            // run" this slice was fixing because it is not obviously wrong on the screen.
            recomputeFor(ACME);
            assertThat(lastResultOf(PLANNED))
                    .isEqualTo(new StoredLastResult(null, null, null, true));
        }

        @Test
        @DisplayName("the kind and the release come off the winning row, not the project's names")
        void theKindAndReleaseAreCarried() {
            recomputeFor(ACME);
            StoredLastResult stored = lastResultOf(EXECUTED_IN_A_RELEASE);
            assertThat(stored.kind())
                    .as("case_status.kind, which is fixed — not the project's own name for the "
                            + "status, which a client may rename to \"Environment down\"")
                    .isEqualTo("BLOCKED");
            assertThat(stored.releaseVersion()).isEqualTo("4.2.0");
            assertThat(stored.executedAt()).isNotNull();
            assertThat(stored.neverExecuted()).isFalse();
        }

        @Test
        @DisplayName("the newest execution wins, and the stale one is the PASSING one")
        void theNewestExecutionWins() {
            // REVISITED was executed 120 days ago (PASSED, release 1.0.0) and 10 days ago (FAILED,
            // no release). An implementation that takes any row but the newest reports a case as
            // green that is currently red — and reports a release it has not been run in since.
            recomputeFor(ACME);
            StoredLastResult stored = lastResultOf(REVISITED);
            assertThat(stored.kind()).isEqualTo("FAILED");
            assertThat(stored.releaseVersion())
                    .as("null while the object is present — a run outside a release. The stale row "
                            + "HAS a version, so a non-null here names the row that lost")
                    .isNull();
            assertThat(stored.neverExecuted()).isFalse();
        }

        @Test
        @DisplayName("never_executed and last_executed_at agree for EVERY case in the company")
        void theBooleanAndTheValueCannotDisagree() {
            // The invariant V45's header is built on, and the reason those columns share a row:
            // the health chips and the Last result cell are inches apart on one CaseGrid row, so
            // two derivations of "has this case ever run" is two answers on one screen with
            // nothing saying which is right.
            //
            // It cannot be a CHECK constraint — every row that existed before V45 violates it the
            // instant the columns are added, and a migration cannot reach those rows to fix them
            // (Flyway runs as tcms_owner, which does not bypass FORCE ROW LEVEL SECURITY, so the
            // backfill would report a confident zero). So it is asserted here, over rows a
            // recompute has actually written.
            //
            // Over every case rather than the interesting ones: a derivation that agrees on the
            // cases somebody thought about and disagrees on the twenty-eighth is the exact shape
            // of every finding §152-§165 turned up.
            recomputeFor(ACME);
            for (UUID caseId : liveAcmeCases()) {
                StoredLastResult stored = lastResultOf(caseId);
                assertThat(stored)
                        .as("every live case gets a row from the recompute: %s", caseId)
                        .isNotNull();
                assertThat(stored.neverExecuted())
                        .as("never_executed must be exactly \"last_executed_at is null\" for %s "
                                + "— stored %s", caseId, stored)
                        .isEqualTo(stored.executedAt() == null);
                assertThat(stored.kind() == null)
                        .as("the kind and the moment are written by one statement from one row, "
                                + "so a half-populated last result is unreachable: %s", caseId)
                        .isEqualTo(stored.executedAt() == null);
            }
        }

        @Test
        @DisplayName("removing the executions clears the last result on the next tick")
        void aRemovedExecutionClearsTheResult() throws SQLException {
            recomputeFor(ACME);
            assertThat(lastResultOf(RECENT).kind()).isNotNull();

            try (Connection connection = appConnection(ACME);
                    PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM test_execution WHERE case_id = CAST(? AS uuid)")) {
                statement.setString(1, RECENT.toString());
                statement.executeUpdate();
            }
            recomputeFor(ACME);

            // The ON CONFLICT branch, which the first recompute never reaches. Updating these
            // columns only when EXCLUDED is non-null is the tidy-looking edit that leaves a deleted
            // run's verdict on the case forever — and it would leave never_executed correct, so
            // the two would disagree on one grid row.
            assertThat(lastResultOf(RECENT))
                    .isEqualTo(new StoredLastResult(null, null, null, true));
        }
    }

    @Nested
    @DisplayName("Automation drift — §6c, both directions")
    class AutomationDrift {

        @Test
        @DisplayName("AUTOMATED, and CI stopped reporting, is STALE_AUTOMATION")
        void ciStopped() {
            recomputeFor(ACME);
            // Also long-unexecuted: nothing has run it at all in 120 days. Both are true and both
            // are this job's, so the assertion names both rather than filtering to the one under
            // test — a containsExactly that quietly ignored a sibling signal would pass against an
            // implementation that raised it wrongly.
            assertThat(signalsOf(CI_STOPPED))
                    .containsExactlyInAnyOrder("stale_automation", "long_unexecuted", "unlinked");
        }

        @Test
        @DisplayName("AUTOMATED, and CI reported inside the window, raises nothing")
        void ciReporting() {
            recomputeFor(ACME);
            assertThat(signalsOf(CI_REPORTING)).containsExactly("unlinked");
        }

        @Test
        @DisplayName("a MANUAL re-run does not clear stale automation")
        void aPersonRunningItByHandProvesNothingAboutTheAutomation() {
            // THE test of this pair. The case was run 10 days ago, so it is not long-unexecuted;
            // CI has not posted for 120, so its automation IS stale. An implementation that read
            // "the newest result" for both signals — which is the shape of the statement directly
            // above it in ExecutionFacts, and therefore the one somebody will copy — reports this
            // case as healthy, and the automation nobody is watching stays unwatched.
            recomputeFor(ACME);
            assertThat(signalsOf(CI_STOPPED_RUN_BY_HAND))
                    .as("the predicate is the newest INGESTED result, not the newest result")
                    .containsExactly("stale_automation", "unlinked");
        }

        @Test
        @DisplayName("AUTOMATED with no ingested result EVER is not stale")
        void automationThatNeverStartedHasNotStopped() {
            // D3, and it is a deliberate hole rather than an oversight. §6c makes automationKey
            // optional precisely so a case can be marked AUTOMATED before its automation exists;
            // without this clause an ERROR-severity signal fires on every case in the project the
            // day somebody starts declaring intent, loudest on the teams doing the work.
            recomputeFor(ACME);
            assertThat(signalsOf(CI_NEVER_REPORTED))
                    .as("'stopped reporting' presupposes started; no results at all is "
                            + "NEVER_EXECUTED's question, and only manual ones is nobody's yet")
                    .containsExactly("long_unexecuted", "unlinked");
        }

        @Test
        @DisplayName("a MANUAL case taking CI results is UNDECLARED_AUTOMATION")
        void undeclaredAutomation() {
            recomputeFor(ACME);
            assertThat(signalsOf(UNDECLARED)).containsExactly("unlinked", "undeclared_automation");
        }

        @Test
        @DisplayName("NOT_AUTOMATABLE taking CI results is undeclared too, though §6c says MANUAL")
        void undeclaredIsWiderThanManual() {
            // The widening, tested alone because it is the arm a literal reading of §6c drops. It
            // is also the loudest: automation coverage is denominated AUTOMATED / (total −
            // NOT_AUTOMATABLE), so this case is subtracted from the denominator of the very number
            // the signal family exists to keep honest.
            recomputeFor(ACME);
            assertThat(signalsOf(UNDECLARED_IMPOSSIBLE))
                    .containsExactly("unlinked", "undeclared_automation");
        }

        @Test
        @DisplayName("undeclared automation expires on the same cutoff staleness uses")
        void undeclaredLapses() {
            // D5. Unbounded, this signal could never go false: a case automated, discovered, and
            // then legitimately returned to manual when the automation was deleted would stay
            // flagged for ever with nothing anybody could do — HealthSignal's "no button for
            // lying" rule arriving from the other side, as a claim with no route back.
            recomputeFor(ACME);
            assertThat(signalsOf(UNDECLARED_LAPSED))
                    .as("CI last posted 120 days ago; whatever was automating this has stopped too")
                    .containsExactly("long_unexecuted", "unlinked");
        }

        @Test
        @DisplayName("no case can raise both halves of the pair")
        void theTwoAreMutuallyExclusive() {
            // A property of the statement rather than of the schema — one fact, two sides of one
            // cutoff — so it is worth asserting over the whole fixture rather than per case. Two
            // thresholds, or an automation_status predicate dropped from either arm, and a case
            // reads as both "the automation stopped" and "the automation is undeclared".
            recomputeFor(ACME);

            for (UUID caseId : List.of(NEVER, PLANNED, RECENT, ANCIENT, REVISITED, CI_STOPPED,
                    CI_REPORTING, CI_STOPPED_RUN_BY_HAND, CI_NEVER_REPORTED, UNDECLARED,
                    UNDECLARED_LAPSED, UNDECLARED_IMPOSSIBLE)) {
                List<String> raised = signalsOf(caseId);
                assertThat(raised.contains("stale_automation")
                                && raised.contains("undeclared_automation"))
                        .as("case %s raised both halves of a pair that reads one fact on two sides "
                                + "of one cutoff: %s", caseId, raised)
                        .isFalse();
            }
        }
    }

    /**
     * <strong>Every case in this group raises {@code incomplete} as well, and that is §182 rather
     * than an accident.</strong> These five are the class's only APPROVED fixtures, none of them
     * carries a step, an expected result or a classification, and the completeness derivation reads
     * exactly that. Written out in each assertion rather than filtered, for the reason the class
     * header gives about {@code unlinked}: a {@code containsExactly} that dropped the sibling would
     * pass against an implementation raising it on the wrong rows.
     */
    @Nested
    @DisplayName("Stale approval — §6g, and §157's derivation of it")
    class StaleApproval {

        @Test
        @DisplayName("APPROVED at a version the case has since moved past is STALE_APPROVAL")
        void approvedThenEdited() {
            recomputeFor(ACME);
            assertThat(signalsOf(APPROVED_THEN_EDITED))
                    .containsExactlyInAnyOrder("stale_approval", "never_executed", "unlinked",
                            "incomplete");
        }

        @Test
        @DisplayName("APPROVED at the version it is still on raises nothing")
        void approvedAndUntouched() {
            recomputeFor(ACME);
            assertThat(signalsOf(APPROVED_AND_UNTOUCHED))
                    .containsExactlyInAnyOrder("never_executed", "unlinked", "incomplete");
        }

        @Test
        @DisplayName("an APPROVED case with no review at all is not stale")
        void noReviewIsNotAStaleReview() {
            // Not an edge case: §6g's opt-in is OFF by default and ReviewService.moved opens no
            // review for a project that has not turned it on, so this is the state most approved
            // cases in most projects are in. The alternative reading — "approved, and nobody has
            // signed anything, so flag it" — would raise a WARNING on every case in every project
            // that uses the status ladder without the reviewer apparatus, which §6g explicitly
            // permits. There is nothing to be stale.
            recomputeFor(ACME);
            assertThat(signalsOf(APPROVED_WITHOUT_A_REVIEW))
                    .containsExactlyInAnyOrder("never_executed", "unlinked", "incomplete");
        }

        @Test
        @DisplayName("a case sent back to DRAFT is not stale, even with the old approval on record")
        void demotedAfterApproval() {
            // What EditAfterApproval.RE_REVIEW produces on every edit. The review row stands — it
            // is a record of something that happened — but the case is no longer claiming to be
            // approved, so there is no claim to be out of date.
            recomputeFor(ACME);
            assertThat(signalsOf(DEMOTED_AFTER_APPROVAL))
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a REJECTED review at an old version is not a stale approval")
        void aRejectionIsNotASignOff() {
            // The predicate needs state = 'APPROVED' and not merely "a review exists at an older
            // version". Dropping that clause is the likeliest edit here, and it reports a case
            // somebody REFUSED to sign as one whose sign-off has aged.
            recomputeFor(ACME);
            assertThat(signalsOf(REJECTED_AT_AN_OLD_VERSION))
                    .containsExactlyInAnyOrder("never_executed", "unlinked", "incomplete");
        }

        @Test
        @DisplayName("the HIGHEST approved version decides, not the latest sign-off")
        void reApprovalClearsIt() {
            // Approved at v1, edited, approved again at v3, sitting on v3. Any implementation that
            // takes one review rather than the maximum reports a case whose current content was
            // signed off as stale — and it self-heals only if re-approval is what clears the
            // signal, which is the whole "no button for lying" property.
            recomputeFor(ACME);
            assertThat(signalsOf(APPROVED_TWICE))
                    .containsExactlyInAnyOrder("never_executed", "unlinked", "incomplete");
        }

        @Test
        @DisplayName("a stale flag left behind by an earlier tick is cleared, not merely not set")
        void theSignalSelfHeals() throws SQLException {
            recomputeFor(ACME);
            setSignal(ACME, APPROVED_AND_UNTOUCHED, "stale_approval", true);

            recomputeFor(ACME);

            assertThat(signalsOf(APPROVED_AND_UNTOUCHED))
                    .as("the case's status is a term in the expression, not a filter in the WHERE "
                            + "— a filter would skip the row and leave the stale flag for ever")
                    .containsExactlyInAnyOrder("never_executed", "unlinked", "incomplete");
        }
    }

    @Nested
    @DisplayName("Condition drift — §6f, and §158's derivation of it")
    class ConditionDriftSignal {

        @Test
        @DisplayName("an entry edited since the snapshot drifts the case that references it")
        void aLibraryEditReachesTheCase() {
            recomputeFor(ACME);
            assertThat(signalsOf(CONDITION_EDITED))
                    .containsExactlyInAnyOrder("condition_drift", "never_executed", "unlinked");
        }

        @Test
        @DisplayName("an entry still saying what the snapshot recorded raises nothing")
        void anUnchangedEntryIsClean() {
            recomputeFor(ACME);
            assertThat(signalsOf(CONDITION_UNCHANGED))
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a rename with the same steps is drift too")
        void aRenameIsDrift() {
            // The title is not metadata here: CaseConditionRefView carries it because the contract
            // requires one, and the case detail renders it above the steps. "Log in as standard
            // user" becoming "Log in as admin" changes what the case tells a tester to do quite as
            // much as editing the body does, and a comparison over body alone calls it clean.
            recomputeFor(ACME);
            assertThat(signalsOf(CONDITION_RETITLED))
                    .containsExactlyInAnyOrder("condition_drift", "never_executed", "unlinked");
        }

        @Test
        @DisplayName("a soft-deleted entry is drift, and it is the case an inner join would miss")
        void aDeletedEntryIsTheLoudestDrift() {
            // THE test of the join type. This entry's title and body are byte-identical to what the
            // snapshot recorded — the ONLY difference is deleted_at — so every content comparison
            // in the statement answers "unchanged", and an inner join would drop the row before any
            // of them ran and report the case clean. Meanwhile CaseConditions.forCase has stopped
            // resolving it, so the live case renders one instruction fewer than the snapshot says
            // it should. The case that lost content outright must not be the one that reads well.
            recomputeFor(ACME);
            assertThat(signalsOf(CONDITION_DELETED))
                    .as("lib.id IS NULL is a term in the expression, not an accident of LEFT JOIN")
                    .containsExactlyInAnyOrder("condition_drift", "never_executed", "unlinked");
        }

        @Test
        @DisplayName("the postcondition arm counts, not only the preconditions")
        void bothArmsAreCompared() {
            // Its preconditions are clean and only its postcondition drifted, so an implementation
            // that unnested one array passes every other test in this class. A postcondition is
            // cleanup a tester is told to perform; drifting silently is the same defect.
            recomputeFor(ACME);
            assertThat(signalsOf(POSTCONDITION_EDITED))
                    .containsExactlyInAnyOrder("condition_drift", "never_executed", "unlinked");
        }

        @Test
        @DisplayName("a snapshot with no conditions raises nothing rather than failing")
        void aCaseWithNoConditionsIsClean() {
            // bool_or over zero rows is NULL, and condition_drift is NOT NULL — so without the
            // coalesce this is not a wrong answer but an aborted recompute, taking every other
            // signal in the same transaction with it. The safe direction, and invisible until
            // something asserts it.
            recomputeFor(ACME);
            assertThat(signalsOf(CASE_WITHOUT_CONDITIONS))
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a case with no version at all has nothing to have drifted from")
        void theV1GapIsNotDrift() {
            // Every case authored before §6j's write path is in this state permanently. Reporting
            // drift here would raise an ERROR on the oldest half of a real repository on the day
            // this shipped, for a comparison that was never taken.
            recomputeFor(ACME);
            assertThat(signalsOf(CASE_WITHOUT_A_VERSION))
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("the NEWEST snapshot decides, not any snapshot")
        void theNewestSnapshotWins() {
            // v1 recorded the old body and v2 the current one. An implementation folding bool_or
            // over every version reports a case as drifted for ever the moment it is edited once
            // after a library change — inverting the signal for exactly the cases somebody has
            // just brought up to date, which is theNewestResultWins()'s defect one table over.
            recomputeFor(ACME);
            assertThat(signalsOf(DRIFT_IN_AN_OLD_VERSION))
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a drift flag left behind by an earlier tick is cleared, not merely not set")
        void theSignalSelfHeals() throws SQLException {
            // The property the whole class of "outdated" signals rests on, and the one the
            // timestamp spelling of this statement (condition.updated_at > version.created_at)
            // cannot have: editing an entry and changing it straight back leaves updated_at moved
            // for ever, so the flag would outlive its cause with no action available to clear it.
            recomputeFor(ACME);
            setSignal(ACME, CONDITION_UNCHANGED, "condition_drift", true);

            recomputeFor(ACME);

            assertThat(signalsOf(CONDITION_UNCHANGED))
                    .as("the comparison is content, so reverting the library reverts the signal")
                    .containsExactly("never_executed", "unlinked");
        }
    }

    @Nested
    @DisplayName("The documentation links — the mirror TestCase.relatedDocuments is served from")
    class TheDocumentationLinks {

        @Test
        @DisplayName("a link carries the page, the block and the relationship")
        void aLinkIsMirroredWhole() {
            // The panel renders every one of these: the relationship as a chip, the page title as
            // the link text, the page key beside it, and the block id as the fragment it jumps to.
            // Asserting the row rather than a count is what makes a mirror that copies the wrong
            // column fail here instead of on a screen.
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED)).singleElement().satisfies(link -> {
                assertThat(link.id()).as("the doc_link id, not a minted one").isEqualTo(LINKED_LINK);
                assertThat(link.targetKind()).isEqualTo("DOC_BLOCK");
                assertThat(link.relationship()).isEqualTo("VERIFIES");
                assertThat(link.pageId()).isEqualTo(SPEC_PAGE);
                assertThat(link.pageKey()).isEqualTo("DOC-1");
                assertThat(link.pageTitle()).isEqualTo("Checkout spec");
                assertThat(link.blockId()).isEqualTo(SPEC_BLOCK);
                assertThat(link.blockExcerpt())
                        .isEqualTo("A refund may be issued within 180 days.");
                assertThat(link.stale())
                        .as("the page has not been edited since the case was authored")
                        .isFalse();
            });
        }

        @Test
        @DisplayName("every link of a case is mirrored, not merely one")
        void allOfACasesLinksAreMirrored() {
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_TWICE))
                    .extracting(MirroredLink::relationship)
                    .containsExactlyInAnyOrder("DERIVED_FROM", "REFERENCES");
        }

        @Test
        @DisplayName("a long statement is cut at the bound, marked, and put on one line")
        void theExcerptIsBoundedAndMarked() {
            // Three properties in one assertion because they are one rule, and it is a SECOND
            // spelling of reporting.CoverageMatrixReport's — 160 characters, whitespace collapsed,
            // ellipsis where it was cut. Nothing checks that the two agree (DocumentationFacts says
            // so at the constant); this at least pins this side.
            recomputeFor(ACME);

            String excerpt = mirrored(ACME, LINKED_TWICE).stream()
                    .filter(link -> link.blockId().equals(LONG_BLOCK))
                    .findFirst()
                    .orElseThrow()
                    .blockExcerpt();

            // At most 160 rather than exactly: the cut is trimmed before the ellipsis is appended,
            // so a statement whose 160th character is a space comes back one shorter. That is
            // CoverageMatrixReport's behaviour too (`substring(0, LIMIT - 1).trim() + "…"`), and
            // asserting an exact length here would pin the fixture's wording rather than the rule.
            assertThat(excerpt).hasSizeLessThanOrEqualTo(160).endsWith("…");
            assertThat(excerpt.length())
                    .as("and it must actually have been cut, or this asserts nothing")
                    .isLessThan(LONG_STATEMENT.length());
            assertThat(excerpt)
                    .as("the newline in the block must not reach a panel as a newline")
                    .doesNotContain("\n")
                    .startsWith("The system shall decline a refund");
        }

        @Test
        @DisplayName("a block with no words has no excerpt, rather than an empty one")
        void anEmptyBlockHasNoExcerpt() {
            // Null and not "": the SPA renders the excerpt behind a falsy check, so an empty string
            // and an absent value already behave alike there — and two spellings of nothing on the
            // wire is the thing that eventually gets rendered differently by the second consumer.
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_TWICE).stream()
                    .filter(link -> link.blockId().equals(EMPTY_BLOCK))
                    .findFirst()
                    .orElseThrow()
                    .blockExcerpt())
                    .isNull();
        }

        @Test
        @DisplayName("a spec edited after the case was authored is stale")
        void aMovedSpecIsStale() {
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_TO_A_MOVED_SPEC))
                    .singleElement()
                    .returns(true, MirroredLink::stale);
        }

        @Test
        @DisplayName("a case with no version at all falls back to when it was created")
        void theVersionlessCaseFallsBack() {
            // Without the coalesce this row's staleness is NULL, NULL into a NOT NULL boolean
            // aborts the INSERT, and the whole refresh fails — so the failure mode of removing it
            // is every test in this class going red at once rather than one wrong answer here.
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_WITHOUT_A_VERSION))
                    .as("created now, linked to a page last edited 120 days ago")
                    .singleElement()
                    .returns(false, MirroredLink::stale);
        }

        @Test
        @DisplayName("a withdrawn link disappears on the next refresh")
        void aWithdrawnLinkDisappears() throws SQLException {
            // THE reason the refresh is delete-then-insert. An ON CONFLICT upsert passes every
            // other test in this class and leaves this row standing forever — a case that goes on
            // claiming coverage somebody explicitly withdrew, which is the exact thing
            // BlockLinkService refuses to leave in the table this one mirrors.
            recomputeFor(ACME);
            assertThat(mirrored(ACME, LINKED)).hasSize(1);

            withdrawLink(ACME, LINKED_LINK);
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED)).isEmpty();
        }

        @Test
        @DisplayName("an external git link carries its repository, path, range and commit")
        void aGitLinkIsMirroredWhole() {
            // §169's second arm. Every one of these renders: the repo and path as the label, the
            // range beside it, the sha as the pin. Asserting the row rather than a count is what
            // makes a UNION arm that copies the wrong column fail here instead of on a screen.
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_OUTSIDE))
                    .filteredOn(link -> link.targetKind().equals("EXTERNAL_GIT"))
                    .singleElement()
                    .satisfies(link -> {
                        assertThat(link.id()).isEqualTo(GIT_LINK);
                        assertThat(link.externalRepo()).isEqualTo("acme/checkout");
                        assertThat(link.externalPath()).isEqualTo("src/Refund.java");
                        assertThat(link.externalLineStart()).isEqualTo(40);
                        assertThat(link.externalLineEnd()).isEqualTo(58);
                        assertThat(link.externalCommitSha()).isEqualTo("4f9c2b1");
                        assertThat(link.externalUrl()).isNull();
                        assertThat(link.pageId()).as("no page — there is no page").isNull();
                        assertThat(link.pageKey()).isNull();
                        assertThat(link.pageTitle()).isNull();
                        assertThat(link.blockId()).isNull();
                        assertThat(link.blockExcerpt()).isNull();
                    });
        }

        @Test
        @DisplayName("an external URL link carries the URL and nothing else")
        void aUrlLinkIsMirroredWhole() {
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_OUTSIDE))
                    .filteredOn(link -> link.targetKind().equals("EXTERNAL_URL"))
                    .singleElement()
                    .satisfies(link -> {
                        assertThat(link.id()).isEqualTo(URL_LINK);
                        assertThat(link.externalUrl()).isEqualTo("https://specs.acme.test/refunds");
                        assertThat(link.relationship()).isEqualTo("REFERENCES");
                        assertThat(link.externalRepo()).isNull();
                        assertThat(link.externalPath()).isNull();
                        assertThat(link.externalCommitSha()).isNull();
                    });
        }

        @Test
        @DisplayName("both arms run — a case's block link and its external links are all mirrored")
        void bothArmsContribute() {
            // The UNION ALL, asserted as one thing. A case carrying only external links could not
            // tell "both arms ran" from "the first arm matched nothing", which is why this fixture
            // has all three kinds on one case.
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_OUTSIDE))
                    .extracting(MirroredLink::targetKind)
                    .containsExactly("DOC_BLOCK", "EXTERNAL_GIT", "EXTERNAL_URL");
        }

        @Test
        @DisplayName("an external link is never stale, however old the case's newest version is")
        void anExternalLinkIsNeverStale() {
            // V47 §3. The sha pins the lines, so nothing about the target can have changed; what
            // could fall behind is the pin, and this column does not mean that. The fixture's case
            // is dated well inside, so a comparison accidentally left on this arm would still read
            // false — the assertion that earns its keep is the URL link, which has no version on
            // either side and would be NULL, and NULL into a NOT NULL boolean aborts the refresh.
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_OUTSIDE))
                    .filteredOn(link -> !link.targetKind().equals("DOC_BLOCK"))
                    .isNotEmpty()
                    .allSatisfy(link -> assertThat(link.stale()).isFalse());
        }

        @Test
        @DisplayName("a suite's external link is mirrored onto no case")
        void aSuitesExternalLinkIsNotMirrored() {
            // source_type = 'TEST_CASE' is on BOTH arms. Dropping it from the second would insert a
            // row whose case_id is a suite id — a foreign key violation against test_case, so the
            // whole refresh would abort rather than mis-render, which is the direction to fail in.
            recomputeFor(ACME);

            assertThat(mirrored(ACME, EXTERNALLY_LINKED_SUITE)).isEmpty();
            assertThat(mirrored(ACME, ACME_SUITE)).isEmpty();
        }

        @Test
        @DisplayName("a soft-deleted case keeps no external links either")
        void aRemovedCaseKeepsNoExternalLinks() {
            // The one predicate the second arm keeps from the first. The other three are about a
            // page, and this arm joins none.
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_OUTSIDE_AND_REMOVED)).isEmpty();
        }

        @Test
        @DisplayName("a soft-deleted case keeps no links")
        void aRemovedCaseIsNotMirrored() {
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_AND_REMOVED)).isEmpty();
        }

        @Test
        @DisplayName("a link into a soft-deleted page is not shown")
        void aDeletedPageIsNotMirrored() {
            // BlockLinks drops what it cannot name for the same reason: a row with a title and no
            // destination reads as a broken product rather than as a withdrawn document.
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_TO_A_DELETED_PAGE)).isEmpty();
        }

        @Test
        @DisplayName("a page of a sibling project is not shown")
        void anotherProjectsPageIsNotMirrored() {
            // RLS returns it — a sibling project is the same company — so only the statement's own
            // project predicate refuses it. Without that predicate this case's panel would name a
            // page belonging to a project the reader may hold no grant on.
            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_ACROSS_PROJECTS)).isEmpty();
        }

        @Test
        @DisplayName("refreshing one company does not delete another's mirror")
        void theUnqualifiedDeleteCannotCrossACompany() {
            // The highest-value assertion in this class: `DELETE FROM case_doc_link` carries no
            // WHERE clause and relies entirely on FORCE ROW LEVEL SECURITY. A deployment where that
            // reliance is wrong loses another client's data rather than merely reading it.
            recomputeFor(UMBRA);
            assertThat(mirrored(UMBRA, FOREIGN_LINKED)).hasSize(1);

            recomputeFor(ACME);

            assertThat(mirrored(UMBRA, FOREIGN_LINKED))
                    .as("Acme's refresh must not be able to reach Umbra's rows")
                    .hasSize(1);
            assertThat(mirrored(ACME, LINKED))
                    .as("and must still have written its own")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a second refresh changes nothing")
        void isIdempotent() {
            recomputeFor(ACME);
            List<MirroredLink> first = mirrored(ACME, LINKED_TWICE);

            recomputeFor(ACME);

            assertThat(mirrored(ACME, LINKED_TWICE))
                    .as("the ids are doc_link's, so replacing the table must not renumber anything")
                    .isEqualTo(first);
        }
    }

    /**
     * The two linkage signals — §178, and the first group here whose subject is derived from the
     * group above rather than from a table anything else writes.
     *
     * <p>{@code UNLINKED} and {@code OUTDATED_SPEC_CHANGED} are read off {@code case_doc_link}, which
     * the mirror statement rewrote earlier in the same transaction. So these tests are the only ones
     * in this class that can fail because of a statement's <em>position</em>, and
     * {@link #aLinkMadeSinceTheLastTickCountsOnThisTick} is the assertion that pins it: with the two
     * statements in the other order every other test here still passes, because the fixture's links
     * exist before the first tick.
     *
     * <p>The mirror's four predicates are inherited rather than restated, which is the whole reason
     * the derivation reads the mirror — and the three tests that look most like duplicates of the
     * group above ({@link #aLinkIntoADeletedPageIsNoLinkAtAll},
     * {@link #aSiblingProjectsPageIsNoLinkEither}, {@link #aSuitesLinkDoesNotCoverItsCases}) are what
     * make "inherited" a fact rather than a claim: they assert the <em>signal</em>, so the day
     * somebody rewrites the derivation against {@code doc_link} to save a join, all three go red.
     */
    @Nested
    @DisplayName("The linkage signals — §6i and §6o, and §178's derivation of both")
    class LinkageSignals {

        @Test
        @DisplayName("a case linked to nothing is UNLINKED")
        void noLinksAtAll() {
            // The common case, and in a young project the majority of cases — which is why the
            // signal is INFO and why nearly every assertion in this class now names it.
            recomputeFor(ACME);
            assertThat(signalsOf(NEVER)).contains("unlinked");
        }

        @Test
        @DisplayName("a case linked to an unmoved spec raises neither")
        void linkedAndClean() {
            recomputeFor(ACME);
            assertThat(signalsOf(LINKED))
                    .as("one VERIFIES link into a page last edited before the case was authored")
                    .containsExactly("never_executed");
        }

        @Test
        @DisplayName("a case whose linked spec moved is OUTDATED_SPEC_CHANGED, and not unlinked")
        void aMovedSpecRaisesTheErrorAndNotTheInfo() {
            // The two are independent and one fixture proves it: this case has a link, so it is not
            // unlinked, and that link is stale, so it is outdated. An implementation deriving one
            // from the other — "no usable link" — would collapse them into one chip.
            recomputeFor(ACME);
            assertThat(signalsOf(LINKED_TO_A_MOVED_SPEC))
                    .containsExactlyInAnyOrder("outdated_spec_changed", "never_executed");
        }

        @Test
        @DisplayName("ONE stale link among several raises it, whatever that link claims")
        void anyStaleLinkIsEnough() {
            // bool_or, not the first row and not bool_and. A case verifying two statements is wrong
            // the moment either of them is rewritten, and the panel already marks that one link
            // stale — so an implementation that needed all of them to move would put a clean chip
            // above a panel with a stale marker in it.
            //
            // The moved link here is DERIVED_FROM, which makes this the OTHER half of the
            // relationship decision: a case written from a statement that has since been rewritten
            // is as wrong as one claiming to verify it, so narrowing THIS term to VERIFIES — the
            // edit everyRelationshipCountsAsALink refuses on the unlinked term — fails here.
            recomputeFor(ACME);
            assertThat(signalsOf(LINKED_TO_ONE_MOVED_SPEC_OF_TWO))
                    .containsExactlyInAnyOrder("outdated_spec_changed", "never_executed");
        }

        @Test
        @DisplayName("a case linked only by REFERENCES is NOT unlinked, though it covers nothing")
        void everyRelationshipCountsAsALink() {
            // THE decision, and the one edit most likely to be proposed as a consistency fix:
            // BlockCoverageView and CoverageMatrixReport both count VERIFIES only. They answer a
            // question about a STATEMENT — does anything claim to verify this paragraph — and this
            // signal answers one about a CASE, rendered inches from the list of its links. A chip
            // reading "Unlinked" above a panel showing a link is the product disagreeing with itself
            // on one screen, which is the defect §157 found and §167's arrangement exists against.
            recomputeFor(ACME);
            assertThat(signalsOf(LINKED_BY_REFERENCE_ONLY))
                    .as("\"linked, and nothing claims coverage\" is a narrower gap with no member "
                            + "in this enum — lending it UNLINKED's is what is refused")
                    .containsExactly("never_executed");
        }

        @Test
        @DisplayName("an external target clears UNLINKED and can never raise the other")
        void anExternalLinkIsALink() {
            // A case pinned to a file at a commit is not invisible to traceability. It also cannot
            // be outdated by anything this database can see: DocumentationFacts writes the literal
            // false into stale on that arm (V47 §3), so both halves of the asymmetry are asserted
            // here on one case whose only link is external.
            recomputeFor(ACME);
            assertThat(signalsOf(LINKED_ONLY_OUTSIDE)).containsExactly("never_executed");
        }

        @Test
        @DisplayName("a link into a soft-deleted page is no link at all")
        void aLinkIntoADeletedPageIsNoLinkAtAll() {
            // The strongest argument for deriving from the mirror, and it looks like a bug. This
            // case HAS a doc_link row; the mirror drops it because the page is gone, so nothing
            // renders on the panel — and the chip agrees with the panel. The alternative is a case
            // the product says is traced, to a destination it will not show you.
            recomputeFor(ACME);
            assertThat(signalsOf(LINKED_TO_A_DELETED_PAGE))
                    .as("the panel shows nothing, so the chip must say nothing is linked")
                    .containsExactlyInAnyOrder("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a page of a sibling project is no link either")
        void aSiblingProjectsPageIsNoLinkEither() {
            recomputeFor(ACME);
            assertThat(signalsOf(LINKED_ACROSS_PROJECTS))
                    .containsExactlyInAnyOrder("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a suite's link does not cover the cases in it")
        void aSuitesLinkDoesNotCoverItsCases() {
            // source_type = 'TEST_CASE'. A suite-level link is a claim about the suite, and
            // inheriting it would silence this signal for every case under a suite somebody linked
            // once — the traceability gap the signal exists to report, hidden by a single row.
            recomputeFor(ACME);
            assertThat(signalsOf(EXTERNALLY_LINKED_SUITE))
                    .containsExactlyInAnyOrder("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a link made since the last tick counts on THIS tick, not the next one")
        void aLinkMadeSinceTheLastTickCountsOnThisTick() throws SQLException {
            // THE ordering test, and the only one in this class that can fail on a statement's
            // position rather than its content. SpecLinkage must run AFTER the mirror refresh and in
            // the same transaction; ordered before it, this assertion sees the previous tick's rows
            // and the case stays flagged for an hour while its own Traceability panel — refreshed by
            // the statement that ran after — already lists the link.
            recomputeFor(ACME);
            assertThat(signalsOf(NEVER)).contains("unlinked");

            linkTo(ACME, NEVER, SPEC_BLOCK);
            recomputeFor(ACME);

            assertThat(signalsOf(NEVER))
                    .as("one recompute, not two: the mirror and the signal are one transaction in "
                            + "one order")
                    .containsExactly("never_executed");
        }

        @Test
        @DisplayName("withdrawing the last link raises UNLINKED again on the next tick")
        void bothSignalsSelfHeal() throws SQLException {
            // The other direction of the same property, and the one the delete-then-insert mirror
            // makes possible: an upsert would leave the withdrawn row standing, so this case would
            // go on reporting itself linked to a claim somebody explicitly retracted.
            recomputeFor(ACME);
            assertThat(signalsOf(LINKED)).doesNotContain("unlinked");

            withdrawLink(ACME, LINKED_LINK);
            recomputeFor(ACME);

            assertThat(signalsOf(LINKED)).contains("unlinked");
        }

        @Test
        @DisplayName("a flag left behind by an earlier tick is cleared, not merely not set")
        void aStaleFlagIsCleared() throws SQLException {
            // Both columns are in the ON CONFLICT's SET list, so a false is written and not merely
            // omitted. Set by hand because no fixture can produce a wrong flag: the only writer is
            // the statement under test, which is exactly why nothing would notice it never clearing.
            recomputeFor(ACME);
            setSignal(ACME, LINKED, "outdated_spec_changed", true);
            setSignal(ACME, LINKED, "unlinked", true);

            recomputeFor(ACME);

            assertThat(signalsOf(LINKED))
                    .as("a spec restored, or a link made, must clear the chip it raised")
                    .containsExactly("never_executed");
        }
    }

    /**
     * §6o's owner check — {@code OwnerStanding}, §181, and the last signal in the enum to acquire a
     * writer.
     *
     * <p>Every case in this block is also {@code never_executed} and {@code unlinked}, which is the
     * ordinary state of a case nobody has run or linked, so each assertion names all three columns
     * rather than the one under test. Asserting the whole set is deliberate here for the same reason
     * it is everywhere else in this class: a derivation that raised {@code orphaned} correctly and
     * cleared somebody else's column would pass a {@code contains} and fail a person.
     */
    @Nested
    @DisplayName("The owner check")
    class OwnerStandingSignal {

        @Test
        @DisplayName("an owner who is still here raises nothing")
        void anActiveOwnerIsClean() {
            recomputeFor(ACME);

            assertThat(signalsOf(OWNED_BY_A_COLLEAGUE))
                    .as("an ACTIVE membership on an ACTIVE account is the ordinary case, and it is "
                            + "most of the rows in any deployment")
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a deactivated account orphans the cases it owns")
        void aDeactivatedAccountIsAnOrphan() {
            // The instance-wide off switch, with the membership left ACTIVE — so this case fails on
            // an implementation that reads company_membership alone. That implementation is the
            // tempting one: it is one join shorter, and the term it drops is the one no production
            // path currently writes (AppUser.deactivate has four callers, all tests).
            recomputeFor(ACME);

            assertThat(signalsOf(OWNED_BY_A_DEACTIVATED_ACCOUNT))
                    .containsExactly("never_executed", "unlinked", "orphaned");
        }

        @Test
        @DisplayName("an offboarded colleague orphans the cases they owned")
        void anOffboardedMemberIsAnOrphan() {
            // THE case this signal exists for, and the only one of the three terms a running
            // deployment reaches today: MembershipRemovalService writes REMOVED and deletes
            // nothing, precisely so authorship survives. This is what that leaves behind.
            recomputeFor(ACME);

            assertThat(signalsOf(OWNED_BY_AN_OFFBOARDED_COLLEAGUE))
                    .containsExactly("never_executed", "unlinked", "orphaned");
        }

        @Test
        @DisplayName("an owner with no membership here at all is an orphan")
        void someoneWhoWasNeverAMemberHereIsAnOrphan() {
            // The m.user_id IS NULL term. An implementation checking only status = 'REMOVED' passes
            // every other test in this block and reports this case as healthy — a case owned by
            // somebody the company has no record of, rendered as owned.
            recomputeFor(ACME);

            assertThat(signalsOf(OWNED_BY_A_STRANGER))
                    .containsExactly("never_executed", "unlinked", "orphaned");
        }

        @Test
        @DisplayName("an outstanding invitation is not somebody who left")
        void anInvitationOutstandingIsNotAnOrphan() {
            // The decision, as a test. "Not ACTIVE" is the shorter predicate and it is wrong in the
            // direction that costs a team its trust in the panel: a case assigned to a colleague
            // being onboarded would be chipped "owner has left" for the whole of their first week,
            // and then heal itself the day they arrive.
            recomputeFor(ACME);

            assertThat(signalsOf(OWNED_BY_AN_INVITEE))
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a case with no owner is not an orphan")
        void anUnownedCaseIsNotAnOrphan() {
            // The other half of that decision. owner_id is nullable and unowned cases are ordinary,
            // so a predicate without the IS NOT NULL term puts an INFO chip on most rows of a young
            // project — UNLINKED's failure mode, which that signal at least has a traceability
            // argument for. "Nobody owns these" is a population, and the dashboard's by-owner
            // breakdown is where a population belongs.
            recomputeFor(ACME);

            assertThat(signalsOf(UNOWNED))
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("one person, two clients — the answer is per company")
        void theSameContractorIsJudgedPerCompany() {
            // The same account owns both cases. Acme removed them; Umbra did not. A derivation that
            // asked "is this person gone" rather than "is this person gone FROM THIS COMPANY" would
            // chip Umbra's case because of a decision taken inside a different client's tenancy —
            // and plan §6s exists to make that impossible.
            recomputeFor(ACME);
            recomputeFor(UMBRA);

            assertThat(signalsOf(OWNED_BY_AN_OFFBOARDED_COLLEAGUE))
                    .as("removed in Acme")
                    .contains("orphaned");
            assertThat(signalsOf(FOREIGN_OWNED))
                    .as("the same person, working in Umbra")
                    .doesNotContain("orphaned");
        }

        @Test
        @DisplayName("re-inviting somebody clears the signal on everything they own")
        void theSignalSelfHeals() throws SQLException {
            // HealthSignal's "no button for lying" rule from the other side: nothing sets this flag
            // false, so the only thing that can clear it is the cause going away. Re-invitation is
            // the real path — CompanyMembership.reinvite puts a REMOVED row back to INVITED — and
            // an invitee is not an orphan, so one status update clears every case at once.
            recomputeFor(ACME);
            assertThat(signalsOf(OWNED_BY_AN_OFFBOARDED_COLLEAGUE)).contains("orphaned");

            try (Connection acme = appConnection(ACME)) {
                membership(acme, ACME, CONTRACTOR, "ACTIVE");
            }
            recomputeFor(ACME);

            assertThat(signalsOf(OWNED_BY_AN_OFFBOARDED_COLLEAGUE))
                    .as("the flag is in the ON CONFLICT's SET list, so a false is WRITTEN rather "
                            + "than merely not written — without that the chip outlives its cause "
                            + "forever and nothing in the product can take it down")
                    .containsExactly("never_executed", "unlinked");
        }
    }

    @Nested
    @DisplayName("The completeness check — §6o, and §182's derivation of it")
    class Completeness {

        @Test
        @DisplayName("an approved case with steps, an expected result and a classification is clean")
        void aFinishedCaseRaisesNothing() {
            recomputeFor(ACME);
            assertThat(signalsOf(COMPLETE)).containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("approved with no steps at all is INCOMPLETE")
        void noSteps() {
            recomputeFor(ACME);
            assertThat(signalsOf(APPROVED_WITHOUT_STEPS))
                    .as("the case states its outcome and is classified; the instructions are what "
                            + "is missing, and a signed-off case nobody can follow is the loudest "
                            + "of the three terms")
                    .containsExactlyInAnyOrder("never_executed", "unlinked", "incomplete");
        }

        @Test
        @DisplayName("approved with steps but nothing saying what should happen is INCOMPLETE")
        void noExpectedResultAnywhere() {
            recomputeFor(ACME);
            assertThat(signalsOf(APPROVED_WITHOUT_AN_EXPECTED_RESULT))
                    .containsExactlyInAnyOrder("never_executed", "unlinked", "incomplete");
        }

        @Test
        @DisplayName("a case-level outcome satisfies the expected-result term on its own")
        void theCaseLevelOutcomeCounts() {
            // The two columns answer different questions — TestCase.expectedOutcome's own comment
            // says so — and EITHER is enough. Requiring both fires on almost every case in the
            // product, because almost nobody fills in both; requiring only the per-step one calls a
            // house style a gap. What the term asks is whether a tester can tell a pass from a
            // fail, and one statement of that anywhere in the case answers it.
            recomputeFor(ACME);
            assertThat(signalsOf(APPROVED_WITH_A_CASE_LEVEL_OUTCOME))
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("whitespace is not an expected result")
        void blankIsNotStated() {
            // btrim rather than IS NOT NULL, and the difference is what a rich-text editor leaves
            // behind when somebody clears a field: a present, empty string. Both columns are made
            // of spaces here, so an implementation testing either for null passes this row.
            recomputeFor(ACME);
            assertThat(signalsOf(APPROVED_WITH_A_BLANK_EXPECTED_RESULT))
                    .containsExactlyInAnyOrder("never_executed", "unlinked", "incomplete");
        }

        @Test
        @DisplayName("approved and unclassified is INCOMPLETE — the term that fires today")
        void noClassification() {
            // The only one of the three terms a running deployment reaches: CaseEdit.validate
            // refuses a case with no steps and a Gherkin case with no scenario, and nothing
            // anywhere requires a classification. "Any row" is the whole predicate, because
            // classification_dimension has cardinality, controlled, hierarchical and locked and no
            // REQUIRED — so there is no per-project spelling of "its classification" to read.
            recomputeFor(ACME);
            assertThat(signalsOf(APPROVED_WITHOUT_A_CLASSIFICATION))
                    .containsExactlyInAnyOrder("never_executed", "unlinked", "incomplete");
        }

        @Test
        @DisplayName("a DRAFT missing all three is not incomplete — it is a draft")
        void theGateIsTheSignal() {
            // Drop the lifecycle term and this signal fires on every case somebody is currently
            // writing, which is most of an active project, and reports ordinary work in progress
            // as a fault. Every other assertion in this group passes while it does.
            recomputeFor(ACME);
            assertThat(signalsOf(A_DRAFT_MISSING_EVERYTHING))
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a DEPRECATED case missing all three is not incomplete either")
        void aRetiredCaseIsNotUnfinished() {
            // "Approved but missing…" (§6o) is one status, not "anything past draft". A retired
            // case is not going to be finished, and a chip asking somebody to finish it is asking
            // for work nobody wants done.
            recomputeFor(ACME);
            assertThat(signalsOf(A_DEPRECATED_CASE_MISSING_EVERYTHING))
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("a Gherkin scenario is steps AND an expected result, with no test_step rows")
        void aScenarioIsNotMissingItsSteps() {
            // THE test of this group. step_format makes test_step rows and gherkin_source
            // alternatives (plan §6a), so a GHERKIN case legitimately has neither steps nor an
            // expected outcome — and the obvious spelling of "has steps", a lookup in test_step,
            // reports every Gherkin case in the product as unfinished. The scenario also settles
            // the expected-result term for that format: its Then clause IS the expected result, and
            // finding it means parsing Gherkin in SQL in a scheduled job.
            recomputeFor(ACME);
            assertThat(signalsOf(AN_APPROVED_SCENARIO))
                    .containsExactly("never_executed", "unlinked");
        }

        @Test
        @DisplayName("an empty scenario is the Gherkin spelling of no steps")
        void anEmptyScenarioIsIncomplete() {
            recomputeFor(ACME);
            assertThat(signalsOf(AN_APPROVED_EMPTY_SCENARIO))
                    .containsExactlyInAnyOrder("never_executed", "unlinked", "incomplete");
        }

        @Test
        @DisplayName("an incomplete flag left behind by an earlier tick is cleared, not merely not set")
        void theSignalSelfHeals() throws SQLException {
            recomputeFor(ACME);
            setSignal(ACME, COMPLETE, "incomplete", true);

            recomputeFor(ACME);

            assertThat(signalsOf(COMPLETE))
                    .as("the flag is in the ON CONFLICT's SET list, so a false is WRITTEN rather "
                            + "than merely not written — and this is the signal whose cause a "
                            + "person clears deliberately, by finishing the case")
                    .containsExactly("never_executed", "unlinked");
        }
    }

    @Nested
    @DisplayName("What the job must not touch")
    class Boundaries {

        /**
         * <strong>The witness is gone and the assertion is per-statement now.</strong>
         *
         * <p>This test proved that each {@code ON CONFLICT} names only the columns its statement
         * computes, and it proved it with a <em>witness</em>: one {@code case_health} column nothing
         * writes, set by hand, still set after a tick. The witness had to move three times —
         * {@code stale_approval} (§157), {@code unlinked} (§178), {@code orphaned} (§181) — each
         * time because the slice that moved it had given the previous one a writer, and §181 took
         * the last one that was not a signal at all ({@code incomplete}) while writing down that
         * there was no fifth.
         *
         * <p>§182 derived that one too, so the population is not merely exhausted, it is
         * <em>closed</em>: every boolean on this row is computed, and no future slice can restore a
         * witness by leaving a column alone. The honest answer is to stop needing one. Each
         * statement is driven on its own here and the property is read off its siblings' columns —
         * which needs no unwritten column anywhere, and asserts something strictly stronger than the
         * witness ever did: the old shape could not have noticed <em>two statements inside this job
         * both writing one column</em>, because it only ever watched a column outside all of them.
         *
         * <p>The reporting-owned {@code failing_repeatedly} is still not the answer, and this is now
         * the reason rather than the objection recorded twice before: it is not that asserting over
         * another module's schedule is wrong, it is that one borrowed column tests one statement's
         * blast radius and this tests all seven.
         */
        @Test
        @DisplayName("each statement writes only the columns it computes")
        void otherSignalsAreNotClobbered() throws SQLException {
            // If any ON CONFLICT named every column, this job would blank a sibling's work on every
            // tick — a defect that appears an hour after the other feature ships and looks like the
            // other feature's bug.
            //
            // RECENT is the subject because it raises exactly one signal (unlinked), so every
            // statement's own columns come back false and the surviving set is a plain expression:
            // everything this statement does not own, plus unlinked, which is true either way.
            recomputeFor(ACME);
            Instant now = clock.instant();

            record Statement(String label, List<String> owns, Runnable run) {
            }
            List<Statement> statements = List.of(
                    new Statement("ExecutionFacts.recomputeExecutionSignals",
                            List.of("never_executed", "long_unexecuted"),
                            () -> executionFacts.recomputeExecutionSignals(
                                    now.minus(thresholds.longUnexecuted()), now)),
                    new Statement("ExecutionFacts.recomputeAutomationDrift",
                            List.of("stale_automation", "undeclared_automation"),
                            () -> executionFacts.recomputeAutomationDrift(
                                    now.minus(thresholds.staleAutomation()), now)),
                    new Statement("ApprovalFacts.recomputeStaleApproval",
                            List.of("stale_approval"),
                            () -> approvalFacts.recomputeStaleApproval(now)),
                    new Statement("ConditionDrift.recomputeConditionDrift",
                            List.of("condition_drift"),
                            () -> conditionDrift.recomputeConditionDrift(now)),
                    new Statement("SpecLinkage.recomputeLinkageSignals",
                            List.of("outdated_spec_changed", "unlinked"),
                            () -> specLinkage.recomputeLinkageSignals(now)),
                    new Statement("OwnerStanding.recomputeOrphaned",
                            List.of("orphaned"),
                            () -> ownerStanding.recomputeOrphaned(now)),
                    new Statement("CaseCompleteness.recomputeIncomplete",
                            List.of("incomplete"),
                            () -> caseCompleteness.recomputeIncomplete(now)));

            // failing_repeatedly is reporting.ExecutionSource's and is deliberately absent from the
            // list above — which makes it the one column this loop never expects to change, and the
            // closest thing left to a witness. It is not the assertion, though: the assertion is
            // that the seven statements partition the rest.
            List<String> derivedHere = new ArrayList<>(SIGNAL_COLUMNS);
            derivedHere.remove("failing_repeatedly");
            assertThat(statements.stream().flatMap(statement -> statement.owns().stream()).toList())
                    .as("the seven statements must between them own every column this job derives, "
                            + "exactly once — a column owned twice is two statements racing to "
                            + "write it, and a column owned by none is one nothing here derives")
                    .containsExactlyInAnyOrderElementsOf(derivedHere);

            for (Statement statement : statements) {
                for (String column : SIGNAL_COLUMNS) {
                    setSignal(ACME, RECENT, column, true);
                }

                inTransaction(ACME, statement.run());

                List<String> expected = new ArrayList<>(SIGNAL_COLUMNS);
                expected.removeAll(statement.owns());
                if (!expected.contains("unlinked")) {
                    expected.add("unlinked");
                }
                assertThat(signalsOf(RECENT))
                        .as("%s must recompute %s and leave every other column on the shared row "
                                + "exactly as it found it", statement.label(), statement.owns())
                        .containsExactlyInAnyOrderElementsOf(expected);
            }
        }

        @Test
        @DisplayName("recomputing one company writes nothing in another")
        void companiesAreIsolated() {
            // The statement carries no company predicate at all — it relies entirely on FORCE ROW
            // LEVEL SECURITY covering both the SELECT and the INSERT. That is the right design and
            // an unasserted one is indistinguishable from a leak.
            recomputeFor(ACME);

            assertThat(healthRowExists(UMBRA, FOREIGN))
                    .as("Umbra's case must be invisible to a recompute run in Acme's context")
                    .isFalse();

            recomputeFor(UMBRA);

            assertThat(healthRowExists(UMBRA, FOREIGN))
                    .as("and reachable from its own")
                    .isTrue();
        }

        @Test
        @DisplayName("a second run changes nothing")
        void isIdempotent() {
            recomputeFor(ACME);
            List<String> first = signalsOf(ANCIENT);

            recomputeFor(ACME);

            assertThat(signalsOf(ANCIENT))
                    .as("the scheduler reruns hourly forever; drift here compounds silently")
                    .isEqualTo(first);
        }
    }

    @Nested
    @DisplayName("computed_at — the field that makes an empty panel honest")
    class ComputedAt {

        @Test
        @DisplayName("is stamped, so the dashboard can stop saying 'nobody has looked'")
        void isStamped() {
            // DashboardEndpointTest asserts healthComputedAt is null while no writer exists. This
            // is the other side of that: once a company has been recomputed, an empty "Needs
            // attention" panel finally means what it says.
            Instant before = Instant.now().minusSeconds(5);
            recomputeFor(ACME);

            assertThat(computedAt(ACME, RECENT))
                    .as("a row derived just now must carry when")
                    .isNotNull()
                    .isAfter(before);
        }

        @Test
        @DisplayName("a clean case still gets a row")
        void cleanCasesAreRecordedToo() {
            // Not an optimisation to skip: a case with no signals and NO ROW is indistinguishable
            // from a case nobody has computed, which is the exact distinction computed_at exists
            // to draw. The row is the evidence that the question was asked.
            recomputeFor(ACME);
            assertThat(healthRowExists(ACME, RECENT)).isTrue();
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private void recomputeFor(UUID companyId) {
        try (var ignored = CompanyContext.open(companyId)) {
            recompute.recompute();
        }
    }

    /**
     * Run one derivation on its own, in the shape the service runs it in.
     *
     * <p>The transaction is not a formality: {@code CompanyAwareTransactionManager} sets
     * {@code app.current_company} in {@code doBegin}, so a statement run outside one sees the GUC
     * unset and {@code V4}'s {@code nullif} makes that match <em>zero</em> rows. It would report
     * success having written nothing, and every assertion about what it left alone would pass.
     */
    private void inTransaction(UUID companyId, Runnable statement) {
        try (var ignored = CompanyContext.open(companyId)) {
            transactions.executeWithoutResult(status -> statement.run());
        }
    }

    /**
     * The signal columns this row asserts, so an assertion reads as a set rather than nine booleans.
     *
     * <p><strong>{@code incomplete} is in the list and is not a signal</strong> — it is the one
     * {@code case_health} boolean the server's {@code HealthSignal} does not name, and since §181 it
     * is also the only one no job writes. That combination is what {@code Boundaries} needs: a
     * column this job must leave alone whose owner is nobody, rather than one whose owner is
     * another module's schedule. Reading it here costs nothing on every other assertion, because a
     * column no writer touches is false unless a test set it.
     */
    private List<String> signalsOf(UUID caseId) {
        UUID companyId = companyOf(caseId);
        List<String> columns = SIGNAL_COLUMNS;
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + String.join(", ", columns)
                                + " FROM case_health WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return List.of();
                }
                return columns.stream().filter(column -> {
                    try {
                        return rows.getBoolean(column);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Which company's connection a case is read on.
     *
     * <p>A method rather than the ternary it replaces: §181 added a second Umbra case, and a
     * two-way conditional that names one id silently reads the other one through Acme's connection,
     * where RLS returns no row and every assertion about it reports "no signals" — a green test
     * proving nothing.
     */
    private static UUID companyOf(UUID caseId) {
        return caseId.equals(FOREIGN) || caseId.equals(FOREIGN_LINKED)
                || caseId.equals(FOREIGN_OWNED)
                ? UMBRA : ACME;
    }

    private boolean healthRowExists(UUID companyId, UUID caseId) {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM case_health WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Instant computedAt(UUID companyId, UUID caseId) {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT computed_at FROM case_health WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getTimestamp("computed_at").toInstant() : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setSignal(UUID companyId, UUID caseId, String column, boolean value)
            throws SQLException {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE case_health SET " + column + " = ? WHERE case_id = CAST(? AS uuid)")) {
            statement.setBoolean(1, value);
            statement.setString(2, caseId.toString());
            statement.executeUpdate();
        }
    }

    private void clearHealthAndExecutions() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                // review_participation before review (FK), and both before test_case even though
                // review.subject_id is deliberately NOT a foreign key — a review row surviving its
                // subject would be seeded onto the next class's cases through the id, which is the
                // §19 shape with an extra table in it.
                // case_doc_link is not in this list and does not need to be: it is ON DELETE
                // CASCADE from test_case, which is. doc_link IS here and its rows would otherwise
                // outlive the cases they name — source_id carries no foreign key, deliberately (a
                // cross-module FK), so a link seeded by one test would attach itself to the case
                // the next test re-creates under the same id. That is §19's shape with an extra
                // table in it, which is the reason review is on this list too.
                for (String table : List.of("case_health", "review_participation", "review",
                        "step_result", "test_execution",
                        "test_run", "test_case_version", "test_case", "suite",
                        "doc_link", "doc_block", "doc_page_version", "doc_page", "doc_space")) {
                    try (PreparedStatement statement =
                            connection.prepareStatement("DELETE FROM " + table)) {
                        statement.executeUpdate();
                    }
                }
            }
        }
    }

    // ---- documentation fixtures (§167) ---------------------------------------------------------
    //
    // The same four helpers CoverageMatrixReportTest writes, narrowed to what this class asserts:
    // that one reads doc_link for a report and this one mirrors it. Copied rather than shared for
    // the reason every fixture in this suite is per class — a shared seed is how two classes start
    // depending on each other's ids (§19).

    private void space(Connection connection, UUID id, UUID companyId, UUID projectId, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_space (id, company_id, project_id, name)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, name);
            statement.executeUpdate();
        }
    }

    private void page(Connection connection, UUID id, UUID companyId, UUID spaceId, UUID projectId,
            String key, long keySeq, String title, boolean deleted) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_page
                    (id, company_id, space_id, project_id, key, key_seq, path, slug, title,
                     status, current_version_no, created_at, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS ltree), ?, ?, 'PUBLISHED', 1, now(), now(),
                        CAST(? AS timestamptz))
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, spaceId.toString());
            statement.setString(4, projectId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "p" + keySeq);
            statement.setString(8, "page-" + keySeq);
            statement.setString(9, title);
            statement.setString(10, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    /**
     * One authored version of a page, at a stated moment.
     *
     * <p>The moment is the whole point: {@code doc_page_version.created_at} is the left-hand side of
     * the staleness comparison, and {@code doc_page.updated_at} deliberately is not — see
     * {@code DocumentationFacts} for why the timestamp spelling was refused on both sides.
     */
    private void pageVersion(Connection connection, UUID pageId, int versionNo, Instant createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_page_version
                    (id, company_id, page_id, version_no, snapshot, content_hash, created_at)
                SELECT gen_random_uuid(), p.company_id, p.id, ?, CAST('{}' AS jsonb), ?,
                       CAST(? AS timestamptz)
                  FROM doc_page p
                 WHERE p.id = CAST(? AS uuid)
                    ON CONFLICT (page_id, version_no) DO NOTHING
                """)) {
            statement.setInt(1, versionNo);
            statement.setString(2, "hash-" + pageId + "-" + versionNo);
            statement.setString(3, createdAt.toString());
            statement.setString(4, pageId.toString());
            statement.executeUpdate();
        }
    }

    private void block(Connection connection, UUID id, UUID companyId, UUID pageId, int position,
            String text) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_block (id, company_id, page_id, position, block_type, content)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, 'paragraph',
                        jsonb_build_object('text', CAST(? AS text)))
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, pageId.toString());
            statement.setInt(4, position);
            statement.setString(5, text);
            statement.executeUpdate();
        }
    }

    /** One link from a case to a block. The id is a parameter so a test can withdraw a named one. */
    private void link(Connection connection, UUID id, UUID companyId, UUID caseId, UUID blockId,
            String relationship) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_link
                    (id, company_id, source_type, source_id, target_kind, target_block_id,
                     relationship)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), 'TEST_CASE', CAST(? AS uuid),
                        'DOC_BLOCK', CAST(? AS uuid), ?)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, caseId.toString());
            statement.setString(4, blockId.toString());
            statement.setString(5, relationship);
            statement.executeUpdate();
        }
    }

    /** One {@code EXTERNAL_GIT} link — the kind whose sha is what makes the pin worth having. */
    private void gitLink(Connection connection, UUID id, UUID companyId, String sourceType,
            UUID sourceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_link
                    (id, company_id, source_type, source_id, target_kind, external_repo,
                     external_path, external_line_start, external_line_end, external_commit_sha,
                     relationship)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS uuid), 'EXTERNAL_GIT',
                        'acme/checkout', 'src/Refund.java', 40, 58, '4f9c2b1', 'VERIFIES')
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, sourceType);
            statement.setString(4, sourceId.toString());
            statement.executeUpdate();
        }
    }

    /** One {@code EXTERNAL_URL} link. */
    private void urlLink(Connection connection, UUID id, UUID companyId, String sourceType,
            UUID sourceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_link
                    (id, company_id, source_type, source_id, target_kind, external_url,
                     relationship)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS uuid), 'EXTERNAL_URL',
                        'https://specs.acme.test/refunds', 'REFERENCES')
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, sourceType);
            statement.setString(4, sourceId.toString());
            statement.executeUpdate();
        }
    }

    /** Re-dates a case's v1, so the staleness comparison has a stated left and right. */
    private void datedVersion(Connection connection, UUID caseId, Instant createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                SELECT gen_random_uuid(), c.company_id, c.id, 1, CAST('{}' AS jsonb), ?,
                       CAST(? AS timestamptz)
                  FROM test_case c
                 WHERE c.id = CAST(? AS uuid)
                    ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, "hash-" + caseId);
            statement.setString(2, createdAt.toString());
            statement.setString(3, caseId.toString());
            statement.executeUpdate();
        }
    }

    /** Every mirrored link of one case, in the order the read path gets them. */
    private List<MirroredLink> mirrored(UUID companyId, UUID caseId) {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, target_kind, relationship, page_id, page_key, page_title,
                               block_id, block_excerpt, external_repo, external_path,
                               external_line_start, external_line_end, external_commit_sha,
                               external_url, stale
                          FROM case_doc_link
                         WHERE case_id = CAST(? AS uuid)
                         ORDER BY target_kind, page_key, block_id
                        """)) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                List<MirroredLink> found = new java.util.ArrayList<>();
                while (rows.next()) {
                    found.add(new MirroredLink(
                            UUID.fromString(rows.getString("id")),
                            rows.getString("target_kind"),
                            rows.getString("relationship"),
                            // Null since §169 — an external link has no page and no block, and
                            // reading these through UUID.fromString unconditionally is what made
                            // this helper throw before the second arm had a test.
                            uuidOrNull(rows.getString("page_id")),
                            rows.getString("page_key"),
                            rows.getString("page_title"),
                            uuidOrNull(rows.getString("block_id")),
                            rows.getString("block_excerpt"),
                            rows.getString("external_repo"),
                            rows.getString("external_path"),
                            (Integer) rows.getObject("external_line_start"),
                            (Integer) rows.getObject("external_line_end"),
                            rows.getString("external_commit_sha"),
                            rows.getString("external_url"),
                            rows.getBoolean("stale")));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private record MirroredLink(UUID id, String targetKind, String relationship, UUID pageId,
            String pageKey, String pageTitle, UUID blockId, String blockExcerpt,
            String externalRepo, String externalPath, Integer externalLineStart,
            Integer externalLineEnd, String externalCommitSha, String externalUrl, boolean stale) {
    }

    /**
     * Link a case to a block on its own connection, so a test can make a link <em>between</em> two
     * recomputes. Always {@link #LATE_LINK}'s id — see that constant for why it is not random.
     */
    private void linkTo(UUID companyId, UUID caseId, UUID blockId) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            link(connection, LATE_LINK, companyId, caseId, blockId, "VERIFIES");
        }
    }

    private void withdrawLink(UUID companyId, UUID linkId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM doc_link WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, linkId.toString());
            statement.executeUpdate();
        }
    }

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private <T> T inCompany(UUID companyId, java.util.concurrent.Callable<T> work) {
        try (var ignored = CompanyContext.open(companyId)) {
            return work.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, 1, ?, 0, 'APPROVED', NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, "TS-1");
            statement.setString(5, name);
            statement.executeUpdate();
        }
    }

    /**
     * One case.
     *
     * @param automationStatus what the case <em>claims</em> about its automation — the left-hand
     *                         side of both drift signals. {@code NOT_AUTOMATABLE} carries a reason
     *                         because {@code V1}'s {@code CHECK} requires one, which is the schema
     *                         being right about the domain: "we will never automate this" is a
     *                         statement somebody has to justify.
     */
    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq, boolean deleted, String automationStatus)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, not_automatable_reason,
                     created_at, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', ?, ?, now(), now(),
                        CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setString(8, automationStatus);
            statement.setString(9,
                    "NOT_AUTOMATABLE".equals(automationStatus) ? "Requires visual judgement" : null);
            statement.setString(10, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), 1,
                        CAST('{}' AS jsonb), ?, now())
                ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, id.toString());
            statement.setString(3, "hash-" + key);
            statement.executeUpdate();
        }
    }

    /**
     * One case whose <em>authoring</em> state is what matters — its lifecycle status and the version
     * it is on — rather than its automation.
     *
     * <p>Separate from {@link #testCase} instead of widening it: that helper's thirteen call sites
     * are all about the automation columns, and a fourteenth and fifteenth parameter on them would
     * make every drift fixture state two facts it does not care about. Both write the same table.
     *
     * @param currentVersionNo the right-hand side of the stale-approval comparison. No matching
     *                         {@code test_case_version} rows are written for versions above 1,
     *                         because nothing in this signal's path joins them — the case's own
     *                         column is what {@code ReviewPanel} compares against too.
     */
    private void authoredCase(Connection connection, UUID id, UUID projectId, String key,
            long keySeq, String lifecycleStatus, int currentVersionNo) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, current_version_no,
                     created_at, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', ?, 'MANUAL', ?, now(), now(), NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, ACME_SUITE.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setString(8, lifecycleStatus);
            statement.setInt(9, currentVersionNo);
            statement.executeUpdate();
        }
    }

    /**
     * One case with an owner — {@code test_case.owner_id}, which no other helper here writes.
     *
     * <p>A third case helper rather than a fourteenth parameter on {@link #testCase} or a fifth on
     * {@link #authoredCase}, for the reason {@link #authoredCase}'s own javadoc gives: those two are
     * about the automation columns and the authoring columns respectively, and every one of their
     * call sites would have to state an owner it does not care about. All three write the same
     * table.
     *
     * @param ownerId null for the unowned case, which is a term in the predicate and not an
     *                absence of one.
     */
    private void ownedCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq, UUID ownerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, owner_id,
                     created_at, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', CAST(? AS uuid),
                        now(), now(), NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Case " + key);
            statement.setString(8, ownerId == null ? null : ownerId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * One classification dimension — §182, and the only fixture here that exists to be
     * <em>attached</em> to rather than read.
     *
     * <p>{@code MULTI} rather than {@code SINGLE}: {@code V2}'s integrity trigger takes its
     * cardinality branch only for {@code SINGLE}, and nothing in the completeness group is about
     * that trigger. Project-scoped like Feature (plan §6d) rather than company-scoped, because a
     * company-level dimension is shared and this class already runs two projects in Acme.
     */
    private void dimension(Connection connection, UUID id, UUID companyId, UUID projectId,
            String key, String cardinality) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO classification_dimension
                    (id, company_id, project_id, key, name, cardinality, controlled, hierarchical,
                     locked, position)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, true, false,
                        false, 0)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setString(5, "Feature");
            statement.setString(6, cardinality);
            statement.executeUpdate();
        }
    }

    /** One value on that dimension. {@code path} is {@code LTREE} and {@code NOT NULL}. */
    private void classificationValue(Connection connection, UUID id, UUID companyId,
            UUID dimensionId, String path, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO classification_value
                    (id, company_id, dimension_id, parent_id, path, name, position, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), NULL,
                        CAST(? AS ltree), ?, 0, NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, dimensionId.toString());
            statement.setString(4, path);
            statement.setString(5, name);
            statement.executeUpdate();
        }
    }

    /**
     * Classify a case — the third of the completeness signal's three terms.
     *
     * <p>{@code dimension_id} is the denormalized copy {@code V2}'s trigger verifies against the
     * value's own, so it is not a parameter: one wrong argument here would be refused with a
     * message about a check nobody in this class is testing.
     */
    private void classify(Connection connection, UUID caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO case_classification (company_id, case_id, value_id, dimension_id)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid))
                ON CONFLICT (case_id, value_id) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, CHECKOUT.toString());
            statement.setString(4, FEATURE_DIMENSION.toString());
            statement.executeUpdate();
        }
    }

    /**
     * One step. {@code expectedResult} null is a step that says what to do and not what to expect,
     * which is the ordinary shape of a case whose outcome is stated once at the case level.
     */
    private void step(Connection connection, String id, UUID caseId, int position, String action,
            String expectedResult) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_step (id, company_id, case_id, position, action, expected_result)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id);
            statement.setString(2, ACME.toString());
            statement.setString(3, caseId.toString());
            statement.setInt(4, position);
            statement.setString(5, action);
            statement.setString(6, expectedResult);
            statement.executeUpdate();
        }
    }

    /** {@code test_case.expected_outcome} — what the case as a whole proves. */
    private void expectedOutcome(Connection connection, UUID caseId, String outcome)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE test_case SET expected_outcome = ? WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, outcome);
            statement.setString(2, caseId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * Turn a case into a {@code GHERKIN} one — {@code step_format} and {@code gherkin_source}
     * together, because the two are one decision and a case carrying one without the other is a
     * state {@code CaseEdit} refuses.
     */
    private void scenario(Connection connection, UUID caseId, String source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE test_case SET step_format = 'GHERKIN', gherkin_source = ? "
                        + "WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, source);
            statement.setString(2, caseId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * One account — {@code app_user}, instance-level and outside RLS.
     *
     * <p><strong>{@code DO UPDATE} rather than {@code DO NOTHING}, unlike every other helper in this
     * class.</strong> Nothing deletes accounts between tests (they are not company-owned, so
     * {@link #clearHealthAndExecutions} cannot reach them and must not try), so a test that
     * reactivates somebody would leave them reactivated for whichever method JUnit runs next. The
     * seed has to be able to put a status back, which is §19's fixture-collision lesson arriving
     * through time rather than through an id — the same reason {@code LATE_LINK} is a named
     * constant.
     *
     * @param email class-prefixed like every other naturally-keyed fixture value here: the column
     *              is {@code CITEXT} and globally unique, so an unprefixed "qa@acme.test" is a
     *              collision with whichever other suite thought of it first.
     */
    private void person(Connection connection, UUID id, String email, String name, String status)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app_user
                    (id, email, canonical_name, status, locale, timezone, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS citext), ?, ?, 'en', 'UTC', now(), now())
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, email);
            statement.setString(3, name);
            statement.setString(4, status);
            statement.executeUpdate();
        }
    }

    /**
     * One membership — the row the derivation actually reads.
     *
     * <p>{@code DO UPDATE} for {@link #person}'s reason, and written on the connection of the
     * company it belongs to: {@code company_membership} is under {@code FORCE ROW LEVEL SECURITY},
     * so an insert of an Umbra row through Acme's connection is refused by the policy rather than
     * quietly landing in the wrong company.
     */
    private void membership(Connection connection, UUID companyId, UUID userId, String status)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_membership
                    (company_id, user_id, display_name, status, invited_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, now())
                ON CONFLICT (company_id, user_id) DO UPDATE SET status = EXCLUDED.status
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, userId.toString());
            statement.setString(3, "Member " + userId.toString().substring(0, 8));
            statement.setString(4, status);
            statement.executeUpdate();
        }
    }

    /**
     * One library condition.
     *
     * @param body the ordered steps, written as a JSONB array because that is what {@code V1}
     *             declares and {@code Condition.body} maps — the same shape the snapshot's
     *             {@code body} carries, which is what lets the statement compare the two with
     *             {@code IS DISTINCT FROM} rather than unnesting both.
     * @param deleted soft-deleted. The one fixture axis with no counterpart in the snapshot: a
     *                deleted entry is identical to a live one in every column the comparison reads.
     */
    private void condition(Connection connection, UUID id, String title, String type,
            List<String> body, boolean deleted) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO condition
                    (id, company_id, project_id, title, type, body, created_at, updated_at,
                     deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS jsonb),
                        now(), now(), CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, ACME_PROJECT.toString());
            statement.setString(4, title);
            statement.setString(5, type);
            statement.setString(6, jsonArray(body));
            statement.setString(7, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    /**
     * One version row carrying a real snapshot.
     *
     * <p>{@link #testCase} writes a {@code '{}'} snapshot for its thirteen cases, which is what
     * makes every one of them clean under this signal without saying so — an empty object has no
     * condition arrays and therefore nothing to compare. This helper is for the cases where the
     * snapshot <em>is</em> the fixture.
     */
    private void version(Connection connection, UUID caseId, int versionNo, String snapshot)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), ?,
                        CAST(? AS jsonb), ?, now())
                ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setInt(3, versionNo);
            statement.setString(4, snapshot);
            statement.setString(5, "hash-" + caseId + "-v" + versionNo);
            statement.executeUpdate();
        }
    }

    /**
     * A version snapshot with these resolved condition entries and nothing else that matters.
     *
     * <p>Deliberately <em>not</em> the whole {@code TestCaseDetailView} shape: the real snapshot
     * carries thirty-odd fields and this statement reads two of them, so a fixture spelling out the
     * rest would suggest a coupling that does not exist. The two arrays are named exactly as
     * Jackson serializes the record's components, which <em>is</em> the coupling — rename
     * {@code preconditions} on the view and this fixture, not the compiler, is what notices.
     */
    private static String snapshot(String preconditions, String postconditions) {
        return """
                {"title":"as the API served it","preconditions":[%s],"postconditions":[%s]}"""
                .formatted(preconditions, postconditions);
    }

    /**
     * One resolved condition inside a snapshot — {@code CaseConditionRefView}'s serialization.
     *
     * <p>The fields the statement never reads are present rather than trimmed, because a fixture
     * carrying only {@code conditionId}, {@code title} and {@code body} would pass against an
     * implementation that assumed the entry had nothing else in it.
     */
    private static String entry(UUID conditionId, String title, List<String> body) {
        return """
                {"id":"ce007000-0000-4000-8000-000000000001","position":0,"conditionId":"%s",\
                "title":"%s","body":%s,"inline":false,"inherited":false,"inheritedFrom":null}"""
                .formatted(conditionId, title, jsonArray(body));
    }

    private static String jsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** A sign-off on one version of one case — {@code state = 'APPROVED'}, with an approver. */
    private void approvedReview(Connection connection, UUID projectId, UUID caseId, int versionNo,
            String reviewId) throws SQLException {
        review(connection, projectId, caseId, versionNo, "APPROVED", reviewId);
    }

    /**
     * One review row.
     *
     * <p>{@code subject_version_no} is stamped at <em>submission</em> and never re-stamped
     * ({@code Review}'s header), so it is passed in rather than derived from the case: a fixture
     * that always matched the case's current version could not express the state this signal
     * exists to find.
     *
     * @param state one of {@code V1}'s three plus {@code V22}'s {@code WITHDRAWN}. Only
     *              {@code APPROVED} gets an {@code approved_at}, which is {@code Review.approve}'s
     *              invariant rather than a schema one. {@code approved_by} stays null in every row
     *              — the column is a foreign key to {@code app_user} and this class seeds no
     *              people, which is legitimate because the derivation asks <em>which version</em>
     *              was signed off and never <em>by whom</em>. A fixture that seeded a user to fill
     *              a column nothing reads would be stating a dependency that does not exist.
     */
    private void review(Connection connection, UUID projectId, UUID caseId, int versionNo,
            String state, String reviewId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO review
                    (id, company_id, project_id, subject_type, subject_id, subject_version_no,
                     state, required_peer_reviews, approved_by, approved_at, created_at, created_by)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'CASE', CAST(? AS uuid),
                        ?, ?, 0, NULL, CASE WHEN ? = 'APPROVED' THEN now() ELSE NULL END,
                        now(), NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, reviewId);
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, caseId.toString());
            statement.setInt(5, versionNo);
            statement.setString(6, state);
            statement.setString(7, state);
            statement.executeUpdate();
        }
    }

    /**
     * One run.
     *
     * @param source {@code 'MANUAL'} or {@code 'INGESTED'} — the right-hand side of both drift
     *               signals, and §117's rule: this is the <em>door the request came through</em>,
     *               so a fixture cannot express "a token posted this through {@code POST /runs}" as
     *               {@code INGESTED}, because the product cannot either.
     */
    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        'Recompute fixture run', 'IN_PROGRESS', ?,
                        (SELECT c.id FROM configuration c
                          WHERE c.project_id = CAST(? AS uuid) LIMIT 1),
                        now(), now())
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, source);
            statement.setString(7, projectId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * One execution row.
     *
     * @param executedAt null for a planned-but-unrecorded row — which also leaves {@code status_id}
     *                   null, because the two move together in {@code TestExecution.record()} and a
     *                   fixture that set one without the other would be testing a state the
     *                   application cannot produce.
     */
    private void execution(Connection connection, UUID companyId, UUID projectId, UUID runId,
            UUID caseId, Instant executedAt) throws SQLException {
        execution(connection, companyId, projectId, runId, caseId, executedAt, null, null);
    }

    /**
     * One execution row, with the two things the booleans never looked at.
     *
     * @param statusKind which of the project's seeded statuses to record, <em>by kind</em>. Null
     *              takes whichever one the project happens to have first, which is what every
     *              fixture written before §166 does and is fine for a test that only asks whether
     *              a status is set. It is <strong>not</strong> fine for one asserting the stored
     *              kind: {@code LIMIT 1} with no {@code ORDER BY} is a value nobody chose, so an
     *              assertion against it would be an assertion about the seeder.
     *
     *              <p>Ignored when {@code executedAt} is null — the two move together in
     *              {@code TestExecution.record()} and a fixture that set a status without a moment
     *              would be building a state the application cannot produce, which is exactly the
     *              row {@code ExecutionFacts}' {@code executed_at IS NOT NULL} predicate exists for
     *              and exactly why that predicate has no test.
     * @param releaseVersion denormalized onto the execution since {@code V1}, so it is set here
     *              rather than on the run — same as the product does, and the derivation reads it
     *              off this row without a {@code test_run} join.
     */
    private void execution(Connection connection, UUID companyId, UUID projectId, UUID runId,
            UUID caseId, Instant executedAt, String statusKind, String releaseVersion)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     executed_at, release_version, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = 1),
                        CASE WHEN CAST(? AS timestamptz) IS NULL THEN NULL
                             ELSE (SELECT s.id FROM case_status s
                                    WHERE s.project_id = CAST(? AS uuid)
                                      AND (CAST(? AS text) IS NULL OR s.kind = CAST(? AS text))
                                    LIMIT 1) END,
                        CAST(? AS timestamptz), ?, now(), now())
                """)) {
            String at = executedAt == null ? null : executedAt.toString();
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setString(6, at);
            statement.setString(7, projectId.toString());
            statement.setString(8, statusKind);
            statement.setString(9, statusKind);
            statement.setString(10, at);
            statement.setString(11, releaseVersion);
            statement.executeUpdate();
        }
        if (executedAt != null && statusKind != null) {
            // A kind the project has no status for makes the subselect above return null, which
            // writes an UNRECORDED execution — and every assertion about this case would then be
            // measuring a fixture bug while looking exactly like a derivation bug. Loud here.
            assertThat(recordedStatusKind(companyId, caseId, runId))
                    .as("fixture: project has no case_status of kind %s", statusKind)
                    .isEqualTo(statusKind);
        }
    }

    /**
     * Every live case in Acme, read from the database rather than listed as constants.
     *
     * <p>The point of {@link TheLastResult#theBooleanAndTheValueCannotDisagree} is that it covers
     * cases nobody thought about, and a hand-written list is exactly the thing that stops covering
     * them the first time somebody adds a fixture for an unrelated signal.
     */
    private List<UUID> liveAcmeCases() {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM test_case WHERE deleted_at IS NULL")) {
            try (ResultSet rows = statement.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString("id")));
                }
                assertThat(ids).as("fixture: Acme must have live cases to sweep").isNotEmpty();
                return ids;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The kind actually written on one execution, read back through the app role. */
    private String recordedStatusKind(UUID companyId, UUID caseId, UUID runId) {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT s.kind FROM test_execution e
                          JOIN case_status s ON s.id = e.status_id
                         WHERE e.case_id = CAST(? AS uuid) AND e.run_id = CAST(? AS uuid)
                        """)) {
            statement.setString(1, caseId.toString());
            statement.setString(2, runId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The three {@code V45} columns of one case's health row, or null when the row is absent.
     *
     * <p>Read as raw columns rather than through {@code CaseHealth}, for the same reason
     * {@link #signalsOf} does: this class asserts what the <em>statement</em> wrote, and going
     * through the entity would let a mapping bug and a derivation bug cancel each other out.
     */
    private StoredLastResult lastResultOf(UUID caseId) {
        UUID companyId = companyOf(caseId);
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT last_status_kind, last_executed_at, last_release_version,
                               never_executed
                          FROM case_health WHERE case_id = CAST(? AS uuid)
                        """)) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Timestamp at = rows.getTimestamp("last_executed_at");
                return new StoredLastResult(
                        rows.getString("last_status_kind"),
                        at == null ? null : at.toInstant(),
                        rows.getString("last_release_version"),
                        rows.getBoolean("never_executed"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * One health row's last-result columns, with {@code never_executed} carried alongside.
     *
     * <p>The boolean is in here rather than fetched separately because every assertion in
     * {@code TheLastResult} is about the two staying in step, and a shape that made it possible to
     * assert one without the other would make forgetting easy.
     */
    private record StoredLastResult(
            String kind, Instant executedAt, String releaseVersion, boolean neverExecuted) {
    }
}
