package com.tcms.repository;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tcms.api.PatchBody;
import com.tcms.project.ClassificationDimensionView;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves a case's procedure out of {@code test_case.gherkin_source} and into {@code test_step} rows
 * — <strong>the population {@code 002-} cannot drop the column until this has emptied</strong>.
 *
 * <h2>What is actually broken, and for whom</h2>
 *
 * <p>Before §255 a {@code GHERKIN} case stored its scenario as one blob and had no step rows at
 * all, so there was nothing for a tester to record a verdict against: executing one meant a single
 * pass/fail over the whole scenario, and {@code step_result} had no row to point at. §255 made
 * {@code stepFormat} a <em>lens</em> over {@code test_step} rather than a second storage, which
 * fixes every case authored after it and none of the ones authored before. This is what fixes
 * those — and it is the only thing that can, because a blob nobody has parsed is a procedure the
 * product can render and cannot execute.
 *
 * <h2>It writes a version, and that is the decision worth defending</h2>
 *
 * <p>The rows could have been inserted on their own. They are not, because {@link CaseVersions}'
 * content hash covers {@link CaseEdit} and therefore covers the steps: rows written past it leave
 * every migrated case with a stored hash that no longer describes it, and <strong>the next
 * ordinary save by a real author</strong> writes a version attributing this migration to them.
 * That is the same trap {@code CaseBulkService.writeVersion} exists to avoid, arriving through a
 * different door — and it would arrive silently, months later, in the one screen whose whole job is
 * to say who changed what.
 *
 * <p>So the migration is an authored act with an author: {@code --actor} resolves to a member of
 * the company, {@code test_case_version.created_by} is that account, and the change comment says
 * what happened ({@code data-model.md} §3).
 *
 * <h2>What it does NOT touch</h2>
 *
 * <ul>
 *   <li><strong>{@code gherkin_source} itself</strong> — left exactly as it was. It is the proof
 *       that the migration was faithful, and {@code 002-}'s contraction is what removes it, after
 *       a human has read a clean report. Emptying it here would make a bad parse unrecoverable.</li>
 *   <li><strong>The case's own fields.</strong> A blob's {@code Feature:} header, its
 *       {@code Scenario:} title and its description are ignored: the case already has a title and
 *       an objective that somebody authored, and the template that seeded most of these blobs
 *       copied the title into the scenario name, so honouring it would mostly be a no-op and
 *       occasionally an overwrite ({@code data-model.md} §4).</li>
 *   <li><strong>{@code updated_at} / {@code updated_by}.</strong> {@link TestCase#applyEdit} is not
 *       called: the case's content is unchanged, only its spelling, and moving the timestamp would
 *       turn every "edited recently" list into a list of when the operator ran a command. Same
 *       argument {@code CaseBulkService} makes for a tag.</li>
 *   <li><strong>A {@code Background:}.</strong> Its steps would have to become library conditions,
 *       and an operator command has no {@code library:manage} semantics to create them under. The
 *       scenario still migrates; the Background lines are <em>reported</em> so a human can attach
 *       preconditions by hand ({@code data-model.md} §4).</li>
 * </ul>
 *
 * <h2>One transaction per case, and one context around all of them</h2>
 *
 * <p>The company context is opened by the caller, <strong>outside</strong> every transaction here —
 * {@code CompanyAwareTransactionManager} reads it in {@code doBegin}, so a context opened inside
 * would leave the RLS GUC unset and every read would return zero rows without raising anything
 * (handoff §15, and {@code ExportCompanyCommand}'s header). Each case then gets its own transaction
 * through {@link TransactionTemplate}, so one case that fails to write does not roll back the
 * hundred before it — an operator re-running after a fix should not have to redo work that
 * succeeded.
 */
@Service
public class GherkinBackfill {

    /** {@code data-model.md} §3 — the sentence the version list shows for a migrated case. */
    static final String CHANGE_COMMENT = "Migrated from gherkin_source by migrate-gherkin-source";

    /**
     * The body this command "sent" — {@code steps}, and nothing else.
     *
     * <p>{@link PatchBody} answers presence, never value, so a node of nulls is exactly right: it
     * says the caller mentioned {@code steps} and mentioned no other optional field, which is what
     * makes {@link CaseUpdateRequest#overlay} carry every other column through from the stored row.
     * {@code changeComment} needs no entry — the overlay takes it from the request unconditionally,
     * deliberately, so that one save's comment can never be inherited by the next.
     */
    private static final PatchBody MENTIONS_STEPS =
            new PatchBody(JsonNodeFactory.instance.objectNode().putNull("steps"));

    private final ProjectService projects;
    private final TestCaseRepository cases;
    private final SuiteRepository suites;
    private final TestStepRepository stepRows;
    private final CaseSteps steps;
    private final CaseClassifications classifications;
    private final CaseConditions conditions;
    private final CaseRenderer renderer;
    private final CaseVersions versions;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public GherkinBackfill(
            ProjectService projects,
            TestCaseRepository cases,
            SuiteRepository suites,
            TestStepRepository stepRows,
            CaseSteps steps,
            CaseClassifications classifications,
            CaseConditions conditions,
            CaseRenderer renderer,
            CaseVersions versions,
            TransactionTemplate transactions,
            Clock clock) {

        this.projects = projects;
        this.cases = cases;
        this.suites = suites;
        this.stepRows = stepRows;
        this.steps = steps;
        this.classifications = classifications;
        this.conditions = conditions;
        this.renderer = renderer;
        this.versions = versions;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Migrates every case in the <strong>open company</strong> that still holds its procedure in
     * the blob.
     *
     * @param actorUserId the account {@code --actor} resolved to, already checked to be an active
     *                    member of this company by the caller. It authors every version this
     *                    writes, so it may not be null: a version row with no author is a change
     *                    the history cannot attribute, and {@code test_case_version.created_by} is
     *                    the column {@code 002-}'s reviewer reads to see the migration happened.
     * @param dryRun      report only. Nothing below writes anything on this path — no reconcile, no
     *                    version, and no managed entity is mutated, so there is nothing for the
     *                    transaction to flush.
     */
    public GherkinBackfillReport run(UUID actorUserId, boolean dryRun) {
        if (actorUserId == null) {
            throw new IllegalArgumentException("a backfill writes versions and needs an author");
        }
        UUID companyId = CompanyContext.require();

        List<Candidate> candidates = transactions.execute(status ->
                cases.findStillOnTheBlob(StepFormat.GHERKIN).stream()
                        .map(row -> new Candidate(row.getId(), row.getProjectId(), row.getKey()))
                        .toList());

        // Grouped so the per-project reads below — the live suites and the classification
        // vocabulary — happen once per project rather than once per case. Insertion-ordered, so the
        // report keeps the query's order (project, then key sequence) and two runs read the same.
        Map<UUID, List<Candidate>> byProject = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            byProject.computeIfAbsent(candidate.projectId(), key -> new ArrayList<>())
                    .add(candidate);
        }

        List<GherkinBackfillReport.Entry> entries = new ArrayList<>();
        for (Map.Entry<UUID, List<Candidate>> project : byProject.entrySet()) {
            Context context = contextFor(companyId, actorUserId, project.getKey());
            if (context == null) {
                // The one refusal that is about the OPERATOR rather than about the text, and it is
                // reported per case rather than thrown. `renderer.vocabularies` answers "none" for
                // a caller who cannot see the project — it is a gated read — and a snapshot
                // rendered from that would record every classified case as unclassified, which is
                // a false version row and worse than no version row at all. So the project is
                // skipped, loudly, and the fix is a grant on the actor rather than a change here.
                for (Candidate candidate : project.getValue()) {
                    entries.add(GherkinBackfillReport.Entry.notMigrated(candidate.caseKey(),
                            "the actor cannot see this case's project"));
                }
                continue;
            }
            for (Candidate candidate : project.getValue()) {
                entries.add(transactions.execute(status ->
                        migrateOne(candidate, actorUserId, context, dryRun)));
            }
        }
        return new GherkinBackfillReport(dryRun, List.copyOf(entries));
    }

    /** One case, in its own transaction. */
    private GherkinBackfillReport.Entry migrateOne(
            Candidate candidate, UUID actorUserId, Context context, boolean dryRun) {

        TestCase row = cases.findLiveInProject(context.projectId(), candidate.caseId())
                .orElse(null);
        if (row == null) {
            // Deleted between the selection and now. Reported rather than dropped: a case that
            // vanishes mid-run is a fact about a concurrent editor, and a silent shrink in the
            // totals is the shape an operator cannot distinguish from a bug in the query.
            return GherkinBackfillReport.Entry.notMigrated(
                    candidate.caseKey(), "the case is no longer live");
        }
        if (!stepRows.findForCase(row.getId()).isEmpty()) {
            return GherkinBackfillReport.Entry.alreadyMigrated(candidate.caseKey());
        }

        GherkinParseResult parsed;
        try {
            parsed = GherkinScenarios.parse(row.getGherkinSource());
        } catch (GherkinSyntaxException refused) {
            // Every refusal already carries the line and column the author can see, and an Outline
            // carries the sentence that says to expand it. Passed through verbatim rather than
            // re-worded, so the operator's report and the paste dialog say the same thing about the
            // same text (`api-surface.md` §5.1).
            return GherkinBackfillReport.Entry.notMigrated(
                    candidate.caseKey(), refused.getMessage());
        }

        CaseEdit.Outcome validated = validate(row, parsed, context);
        if (validated instanceof CaseEdit.Outcome.Invalid invalid) {
            return GherkinBackfillReport.Entry.notMigrated(
                    candidate.caseKey(), "the migrated case does not validate: " + first(invalid));
        }
        CaseEdit edit = ((CaseEdit.Outcome.Valid) validated).edit();
        String note = noteFor(parsed);

        if (dryRun) {
            return GherkinBackfillReport.Entry.migrated(
                    candidate.caseKey(), edit.steps().size(), note);
        }

        steps.reconcile(row, edit.steps());
        // Decided after the rows are reconciled and stamped before the render, which is the order
        // CaseUpdateService and CaseBulkService both use: the snapshot and the version number have
        // to be one render carrying one number, or the history says something the row does not.
        CaseVersions.Decision decision = versions.decide(row, edit);
        row.advanceVersion(decision.versionNo());
        cases.saveAndFlush(row);

        Instant now = clock.instant();
        versions.write(
                row,
                edit,
                decision,
                renderer.render(row, context.liveSuites(), context.vocabularies(),
                        context.companyId()),
                actorUserId,
                now);

        return GherkinBackfillReport.Entry.migrated(
                candidate.caseKey(), edit.steps().size(), note);
    }

    /**
     * The parsed steps, validated as though the case had been saved with them.
     *
     * <p>Through {@link CaseEdit#validate} rather than by building the rows directly, for
     * {@link CaseEdit#of}'s reason: a second normalizer is a second answer to what a step is, and
     * every hash it produced would disagree with every hash the edit path produces — so the next
     * ordinary save of every migrated case would write a version whose diff is empty.
     *
     * <p>The request is assembled through {@link CaseUpdateRequest#overlay} for the same reason
     * one layer up. Only two things are stated — the steps and the change comment — and everything
     * else is the case as it stands, which is what makes this a migration rather than an edit.
     */
    private CaseEdit.Outcome validate(
            TestCase row, GherkinParseResult parsed, Context context) {

        CaseUpdateRequest stored = CaseEdit.asRequest(row, List.of());
        CaseUpdateRequest sent = new CaseUpdateRequest(
                // The four the overlay takes from the body as sent, spelled back from the row:
                // this is a migration, so "what the caller sent" is "what is already there".
                stored.title(),
                null, null, null, null,
                stored.stepFormat(),
                stored.priority(),
                null,
                stored.automationStatus(),
                null, null, null,
                // The two things this command actually says.
                parsed.steps(),
                CHANGE_COMMENT,
                // Every collection stays null — "leave it alone" — so the stored classification,
                // tags, conditions and declines are carried into the edit unchanged. See
                // CaseUpdateRequest's own comments on each.
                null, null, null, null, null, null, null);

        List<UUID> declined = conditions.storedDeclinedIds(row.getId());

        return CaseEdit.validate(
                CaseUpdateRequest.overlay(stored, sent, MENTIONS_STEPS),
                context.liveSuiteIds(),
                // Zero rows is the precondition for being here at all, so there is no existing id a
                // submitted step could name — and the parser never sends one.
                Set.of(),
                // Never consulted: the request's classification is null, so nothing is checked
                // against a vocabulary. The stored values are supplied on the next line instead,
                // and those ARE resolved against the project's real vocabulary — which is why a
                // project the actor cannot see is refused rather than migrated.
                ClassificationVocabulary.none(),
                classifications.liveValueIdsOf(
                        row.getId(), ClassificationVocabulary.of(context.vocabularies())),
                // Never consulted either: both condition lists are null on the request.
                Set.of(),
                conditions.liveAttachedIdsOf(row.getId(), ConditionKind.PRE),
                conditions.liveAttachedIdsOf(row.getId(), ConditionKind.POST),
                // Never consulted, third instance of the same shape: declinedConditionIds is null.
                Set.of(),
                // The RETENTION bound is the stored set itself — "retain all of it" (§192). It is
                // always read, unlike the three above, and an empty one would reconstruct every
                // migrated case as declining nothing.
                Set.copyOf(declined),
                declined,
                key -> cases.existsByAutomationKeyOtherThan(key, row.getId()),
                // Both refused fields are absent from the request, so neither comparison has
                // anything to compare — CaseEdit.of passes the same two nulls.
                null,
                null,
                // A migration is not an authoring act, and the case demonstrably has a procedure
                // already (in the blob). REQUIRED would add nothing: the parser refuses a scenario
                // with no steps before this line is reached.
                CaseEdit.ProcedureRule.DEFERRED);
    }

    // ------------------------------------------------------------------ per-project context ----

    /**
     * @return null when the actor cannot see the project — see the call site for why that is a
     *         refusal rather than an empty vocabulary.
     */
    private Context contextFor(UUID companyId, UUID actorUserId, UUID projectId) {
        boolean visible = Boolean.TRUE.equals(
                projects.readVisible(actorUserId, projectId, project -> Boolean.TRUE).orElse(null));
        if (!visible) {
            return null;
        }
        return transactions.execute(status -> new Context(
                companyId,
                projectId,
                suites.findLiveForProject(projectId),
                renderer.vocabularies(actorUserId, projectId)));
    }

    /**
     * One project's answers, resolved once for every case in it.
     *
     * @param liveSuites   passed into both {@link CaseEdit#validate} (as ids) and
     *                     {@link CaseRenderer#render}, which is why it is the entities and not the
     *                     ids: the renderer resolves a case's inherited conditions through the
     *                     suite chain.
     * @param vocabularies the project's classification, read as the actor. It decides what the
     *                     snapshot records and what {@link CaseEdit}'s hash covers, which is why a
     *                     project the actor cannot see is refused rather than rendered without it.
     */
    private record Context(
            UUID companyId,
            UUID projectId,
            List<Suite> liveSuites,
            List<ClassificationDimensionView> vocabularies) {

        Set<UUID> liveSuiteIds() {
            Set<UUID> ids = new LinkedHashSet<>();
            for (Suite suite : liveSuites) {
                ids.add(suite.getId());
            }
            return ids;
        }
    }

    // ------------------------------------------------------------------------------ details ----

    /** A case as the selection found it — id, its project, and the key the report prints. */
    private record Candidate(UUID caseId, UUID projectId, String caseKey) {
    }

    private static String noteFor(GherkinParseResult parsed) {
        if (parsed.background().isEmpty()) {
            return null;
        }
        return "background-skipped: " + parsed.background().size()
                + " step(s) a human must attach as preconditions";
    }

    private static String first(CaseEdit.Outcome.Invalid invalid) {
        Map.Entry<String, List<String>> field = invalid.errors().entrySet().iterator().next();
        return field.getKey() + ": " + field.getValue().getFirst();
    }
}
