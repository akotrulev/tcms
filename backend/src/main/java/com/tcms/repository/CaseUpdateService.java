package com.tcms.repository;

import com.tcms.project.ClassificationDimensionView;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.project.ReviewService;
import com.tcms.project.ReviewSubjectType;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Editing one case — <strong>the first write in the product</strong>, and the path every later one
 * will be measured against.
 *
 * <h2>Five outcomes, because a write has more ways to be refused than a read</h2>
 *
 * <p>Every read in this module answers 200 or an undifferentiated 404. A write adds three:
 * {@code 403} for a caller who may read the case and not change it, {@code 400} for content the
 * server will not store, and {@code 409} for a caller whose copy is stale. {@link Outcome} is sealed
 * so the controller handles all five and a sixth added later cannot be quietly defaulted.
 *
 * <h2>The permission is {@code case:edit}, and it is not {@code OWN}-qualified</h2>
 *
 * <p>Roles spec §A5 said it was, in one line, contradicting both the permission table and §A3's own
 * principle — "a Test Engineer is never blocked from producing, only from unmaking someone else's
 * work" — which names {@code case:delete} and {@code evidence:delete} and not this. That line is now
 * corrected in the spec. Editing is producing: a Test Engineer who spots a wrong expected result in
 * a colleague's case must be able to fix it, in a product whose whole subject is a shared repository.
 *
 * <p>The gate that matters here is the one that is easy to get wrong in the other direction. Reads
 * in this module borrow {@link ProjectService#readVisible}, which asks {@code project:read} — and
 * <strong>every {@code PROJECT}-scope role holds it</strong>, because that is what makes a granted
 * project visible, and {@code SeedDataTest.everyProjectScopeRoleHoldsTheVisibilityPrecondition}
 * derives that from the catalogue rather than restating it. (Since {@code V2} the two
 * {@code COMPANY}-scope roles it added, Account Manager and Finance, hold it deliberately NOT — a
 * company-wide {@code project:read} would open every project in the company, which is the boundary
 * roles spec §A1 draws.) A write gated the same way is a write a Viewer may perform, and a Viewer is
 * specifically the client's own stakeholder, invited to read coverage without touching it. So this
 * calls {@link ProjectService#writeGranted} instead, and the difference is visible in the diff
 * because the permission has to be named to call it.
 *
 * <h2>Two concurrency checks, catching two different races</h2>
 *
 * <ol>
 *   <li><strong>{@code If-Match} against the stored {@code lock_version}</strong> — catches the
 *       stale editor: someone opened the case, went to lunch, and saved over a change made since.
 *       {@code @Version} alone cannot see this, because the entity is loaded fresh inside this
 *       transaction and its version therefore always matches itself.</li>
 *   <li><strong>{@code @Version} on {@link TestCase#getLockVersion()}</strong> — catches the true
 *       race: two saves that both passed the check above and then interleaved. Mapped to the same 409
 *       by {@link CaseController#onWriteRace}, which has to be a handler rather than a {@code catch}
 *       here: the transaction is rollback-only by the time the exception exists, so returning a tidy
 *       outcome from inside it would fail again at commit.</li>
 * </ol>
 *
 * <p><strong>The second check is not covered by a test</strong> and is recorded as such rather than
 * implied to be verified. Reaching it needs two transactions interleaved, which this suite has no
 * mechanism for — so the mutation that turns the flush below back into a plain {@code save} survives,
 * and honestly so.</p>
 *
 * <p>Omitting {@code If-Match} skips only the first check. That is last-write-wins and is correct for
 * a caller with no prior read; the SPA always sends it.
 *
 * <h2>What commits together</h2>
 *
 * <p>The case row, its steps, the {@code test_case_version} row and the {@code audit_event} row are
 * one transaction. Each pairing matters: an edit whose version row is missing is a hole in history
 * exactly where something changed, and an audit row that survives a rolled-back edit records
 * something that never happened.
 */
@Service
public class CaseUpdateService {

    /** Roles spec §A5, as corrected — see the class header. */
    private static final String EDIT_CASE = "case:edit";

    private static final String AUDIT_ACTION = "case.updated";

    /**
     * §6g's RE_REVIEW demotion. A verb of its own — see the write below for why it is not a field on
     * the save's own audit row.
     */
    private static final String AUDIT_ACTION_RE_REVIEW = "case.re_review_required";
    private static final String AUDIT_TARGET = "test_case";

    private final ProjectService projects;
    private final TestCaseRepository cases;
    private final SuiteRepository suites;
    private final CaseSteps steps;
    private final TestStepRepository stepRows;
    private final CaseClassifications classifications;
    private final CaseConditions conditions;
    private final CaseTags tags;
    /**
     * Read only when the caller mentions `automationTags` (B2). Nothing here writes the table — the
     * field is still refused when it would CHANGE anything — so this is the comparison's evidence
     * and not a write path arriving quietly.
     */
    private final CaseAutomationTagRepository automationTags;
    private final ConditionService conditionLibrary;
    private final CaseRenderer renderer;
    private final CaseVersions versions;
    private final SuiteVersions suiteVersions;
    private final ReviewService reviews;
    private final AuditEventRepository auditEvents;
    private final TestCaseDraftRepository drafts;
    private final Clock clock;

    public CaseUpdateService(
            ProjectService projects,
            TestCaseRepository cases,
            SuiteRepository suites,
            CaseSteps steps,
            TestStepRepository stepRows,
            CaseClassifications classifications,
            CaseConditions conditions,
            CaseTags tags,
            CaseAutomationTagRepository automationTags,
            ConditionService conditionLibrary,
            CaseRenderer renderer,
            CaseVersions versions,
            SuiteVersions suiteVersions,
            ReviewService reviews,
            AuditEventRepository auditEvents,
            TestCaseDraftRepository drafts,
            Clock clock) {

        this.projects = projects;
        this.cases = cases;
        this.suites = suites;
        this.steps = steps;
        this.stepRows = stepRows;
        this.classifications = classifications;
        this.conditions = conditions;
        this.tags = tags;
        this.automationTags = automationTags;
        this.conditionLibrary = conditionLibrary;
        this.renderer = renderer;
        this.versions = versions;
        this.suiteVersions = suiteVersions;
        this.reviews = reviews;
        this.auditEvents = auditEvents;
        this.drafts = drafts;
        this.clock = clock;
    }

    /** What happened, in the five shapes the endpoint can answer. */
    public sealed interface Outcome {

        /** Unreachable project, or no such case in it — one bodiless 404 for both (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code case:edit} not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The caller's {@code If-Match} did not match the stored lock version. */
        record Stale() implements Outcome {
        }

        record Updated(TestCaseDetailView view) implements Outcome {
        }
    }

    /**
     * Applies an edit.
     *
     * @param sent the body as it bound — every absent field is null here, which is why it is never
     *     used alone. {@link CaseUpdateRequest#overlay} is what turns it into an edit.
     * @param body the same body, still able to say which fields it carried (B1). A caller with no
     *     body to hand — there is none today — would pass an empty one and get a save that changes
     *     nothing, which is the honest answer rather than a wipe.
     * @param expectedLockVersion the parsed {@code If-Match}, or null when the header was absent
     */
    @Transactional
    public Outcome update(
            UUID userId,
            UUID projectId,
            UUID caseId,
            CaseUpdateRequest sent,
            com.tcms.api.PatchBody body,
            Long expectedLockVersion) {

        UUID companyId = CompanyContext.require();

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, EDIT_CASE,
                project -> apply(
                        companyId, userId, project, caseId, sent, body, expectedLockVersion));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * Whether the stored case says how it is run — B2's dividing line.
     *
     * <p>Read off the STORED row and never off the payload: the question is whether this save could
     * be deleting a procedure, and the payload is the thing being judged.
     *
     * <p>{@code gherkin_source} is still a term although §255 stopped writing it, and that is the
     * point: a case authored before the backfill has its procedure <em>only</em> in the blob, and
     * reading it as a stub would let one save empty a scenario nobody has migrated yet. The term
     * dies with the column in {@code 002-}, by which time no such row exists.
     */
    private static boolean hasProcedure(TestCase testCase, List<TestStep> storedSteps) {
        return !storedSteps.isEmpty()
                || (testCase.getGherkinSource() != null && !testCase.getGherkinSource().isBlank());
    }

    private Outcome apply(
            UUID companyId,
            UUID userId,
            Project project,
            UUID caseId,
            CaseUpdateRequest sent,
            com.tcms.api.PatchBody body,
            Long expectedLockVersion) {

        // The second gate, and the one a two-segment path needs: is this case in THAT project.
        // Without it, /projects/{granted}/cases/{ungranted} edits a case the caller was never given
        // — and every cross-company test still passes, because the row is in the caller's company.
        Optional<TestCase> found = cases.findLiveInProject(project.getId(), caseId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestCase testCase = found.get();

        if (expectedLockVersion != null && expectedLockVersion != testCase.getLockVersion()) {
            // Checked before validating, so a stale editor is told to reload rather than being sent
            // to fix fields in a copy it is about to have to discard anyway.
            return new Outcome.Stale();
        }

        List<Suite> liveSuites = suites.findLiveForProject(project.getId());
        Set<UUID> liveSuiteIds = new HashSet<>();
        for (Suite suite : liveSuites) {
            liveSuiteIds.add(suite.getId());
        }

        List<TestStep> storedSteps = stepRows.findForCase(testCase.getId());
        Set<UUID> existingStepIds = new HashSet<>();
        for (TestStep step : storedSteps) {
            existingStepIds.add(step.getId());
        }

        /*
          B1 — the body laid over the case as it stands, and everything below reads the RESULT.

          This line is the whole semantic change: a field the caller did not mention is the field
          the case already has, so `{"title": "x"}` renames the case and touches nothing else. It
          has to happen HERE, before anything reads the request, because three things below make
          decisions from it — the condition union and the folder the declines are resolved at. A
          partially-overlaid request would check the payload against one case and store another.
          (It was three until §239 retired the shared-step library and its union with it.)

          `sent` is kept for exactly one purpose, one line down: the automation tags are read only
          when the caller mentioned them, and that is the last question that can be asked of the
          body rather than of the merge.
        */
        List<String> storedAutomationTags = body.has("automationTags")
                ? automationTags.findForCase(testCase.getId()).stream()
                        .map(CaseAutomationTag::getTag)
                        .toList()
                : List.of();

        CaseUpdateRequest request =
                CaseUpdateRequest.overlay(CaseEdit.asRequest(testCase, storedSteps), sent, body);

        // Resolved BEFORE validating and then reused for the render, rather than being read again
        // inside it. Two reads would be two answers to "what does this project offer" a few lines
        // apart, and the write would be checked against one of them while the response was rendered
        // from the other — so a value retired between the two would be accepted and then omitted
        // from the very response that reports the save.
        List<ClassificationDimensionView> vocabularies =
                renderer.vocabularies(userId, project.getId());
        ClassificationVocabulary vocabulary = ClassificationVocabulary.of(vocabularies);

        // The same union, on the other library — this project's live entries plus what the case
        // already attaches. Both halves fail in opposite directions and CaseEdit#normalizeConditions
        // spells out which. Only the ids the payload names are looked up, so a case attaching no
        // condition — most of them — costs no query.
        Set<UUID> acceptableConditionIds = new HashSet<>(
                conditionLibrary.liveLibraryIds(
                        project.getId(),
                        CaseEdit.referencedConditionIds(
                                request.preconditions(), request.postconditions())));
        acceptableConditionIds.addAll(
                conditions.liveAttachedIdsOf(testCase.getId(), ConditionKind.PRE));
        acceptableConditionIds.addAll(
                conditions.liveAttachedIdsOf(testCase.getId(), ConditionKind.POST));

        // Resolved BEFORE validating and reused by the reconcile below, for the reason the
        // vocabulary above is: one answer to "what can this case decline", checked and applied.
        // It has to be read here rather than after applyEdit, because a save that also MOVES the
        // case would otherwise resolve the declines against the folder it is going to instead of
        // the one the client was looking at. See CaseConditions#reachingAt.
        CaseConditions.Reaching here = conditions.reachingAt(testCase.getSuiteId(), liveSuites);
        Set<UUID> declinable = here.declinable();

        // And the RETENTION bound, at the folder the case will be in when this save is done (§192).
        // The SAME folder in every save that does not move the case — which is nearly all of them,
        // and the reason this is a conditional rather than a second unconditional query. A null
        // suiteId is an unfiled case, which inherits nothing and therefore retains no decline; a
        // suiteId this project does not offer is refused by validate below, and asking about it
        // here first costs one query that answers nothing rather than a wrong answer.
        Set<UUID> retained =
                java.util.Objects.equals(request.suiteId(), testCase.getSuiteId())
                        ? here.all()
                        : conditions.reachingAt(request.suiteId(), liveSuites).all();

        // Which suites hold this case RIGHT NOW, read before anything is written (§238). Two of the
        // three membership arms move on this path — a save can refile the case and can retag it —
        // and a suite that LOSES the case by either is unfindable afterwards, so the losers have to
        // be captured here or they stay pinned to a composition they no longer have. Read even for
        // a save that turns out to change neither: the reconcile is hash-gated, so an unchanged
        // suite costs a resolve and writes nothing, and a conditional here would be a second,
        // weaker answer to a question decide() already answers exactly.
        Set<UUID> suitesBefore =
                suiteVersions.holdersBefore(project.getId(), List.of(testCase.getId()));

        CaseEdit.Outcome validated = CaseEdit.validate(
                request,
                liveSuiteIds,
                existingStepIds,
                vocabulary,
                classifications.liveValueIdsOf(testCase.getId(), vocabulary),
                acceptableConditionIds,
                conditions.liveAttachedIdsOf(testCase.getId(), ConditionKind.PRE),
                conditions.liveAttachedIdsOf(testCase.getId(), ConditionKind.POST),
                declinable,
                retained,
                conditions.storedDeclinedIds(testCase.getId()),
                key -> cases.existsByAutomationKeyOtherThan(key, testCase.getId()),
                testCase.getOwnerId(),
                storedAutomationTags,
                // ~~The path the procedure rule is FOR (§200): a PATCH body is the whole editable
                // set, so an empty procedure says this case has none, and this is the funnel a stub
                // reaches its steps through.~~ → **B2: it is REQUIRED of a case that has one, and
                // DEFERRED of a stub.**
                //
                // The funnel argument was about the SPA's editor and is still enforced there, in
                // its Zod schema, where the author is looking at the field. What it cost on the API
                // was the round trip: §200 made a stepless case a legal stored row on the create
                // path, and this rule then refused to store that same row back — so `GET` then
                // `PATCH` unchanged was a 400 about steps for every stub in the product, and a
                // client fixing a typo in a stub's title had to invent a procedure to do it.
                //
                // What is still refused is the thing worth refusing: EMPTYING a procedure that
                // exists. A case with steps that sends `steps: []` is asking to delete them and is
                // told to write one instead — which is the same message, on the same field, for the
                // only body that was ever really a mistake.
                hasProcedure(testCase, storedSteps)
                        ? CaseEdit.ProcedureRule.REQUIRED
                        : CaseEdit.ProcedureRule.DEFERRED);

        if (validated instanceof CaseEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        CaseEdit.Outcome.Valid accepted = (CaseEdit.Outcome.Valid) validated;
        CaseEdit edit = accepted.edit();

        Instant now = clock.instant();

        // §6g's edit-after-approval rule, RE_REVIEW arm. Read BEFORE applyEdit only for clarity —
        // an ordinary edit cannot change the status, because CaseUpdateRequest omits it deliberately
        // ("a status an ordinary edit can set is an approval anyone grants themselves by saving").
        boolean unApproves = testCase.getLifecycleStatus() == LifecycleStatus.APPROVED
                && reviews.reReviewOnEdit(project.getId(), ReviewSubjectType.CASE);

        testCase.applyEdit(edit, userId, now);
        steps.reconcile(testCase, edit.steps());
        // Before the render, not after: the response IS the version snapshot, and rendering first
        // would report the classification the case had on the way in — so the row, the response and
        // the recorded history would each say something different about the save that just happened.
        classifications.reconcile(testCase, edit.classificationValueIds(), vocabulary, companyId);
        // Before the render too, and for the identical reason: the response IS the version
        // snapshot, so reconciling after it would report the conditions the case had on the way in.
        conditions.reconcile(
                testCase, edit.preconditionIds(), edit.postconditionIds(), companyId);
        // The other half of §6f, and it is applied against the set resolved BEFORE applyEdit rather
        // than re-derived here — see `declinable` above. Before the render for the reason every
        // reconcile on this line is: the response IS the version snapshot.
        conditions.reconcileOptouts(
                testCase.getId(), declinable, retained, edit.declinedConditionIds(), companyId);
        // Beside those two and NOT inside `edit`, because a tag is not authored content and is not
        // in CaseVersions#hash — see CaseEdit.Outcome.Valid. Before the render for the reason the
        // other reconciles are: the response IS the version snapshot, so a save that retagged a
        // case would otherwise report the tags it had on the way in. A null here is "the request
        // did not mention tags", which reads nothing and writes nothing.
        tags.reconcile(testCase, accepted.tags(), companyId);

        // Applied BEFORE the render, so the response carries DRAFT rather than reporting the
        // approval the save just ended — the same ordering the classification reconcile above is
        // placed for, and for the same reason: the row, the response and the recorded history must
        // not each say something different about one save.
        //
        // No version row of its own and no version bump: the status is not authored content and is
        // not in CaseVersions#hash, so the edit's own version — decided just below — is the whole
        // record of what changed. §75's identical call on a suite's status.
        if (unApproves) {
            testCase.moveToStatus(LifecycleStatus.DRAFT, userId, now);
        }

        // Decided before the version number is stamped, and stamped before anything is rendered, so
        // the snapshot and the response are one render carrying the same number.
        CaseVersions.Decision decision = versions.decide(testCase, edit);
        testCase.advanceVersion(decision.versionNo());

        // Flushed here so a constraint this class did not validate fails at its cause, before the
        // version row and the audit row are written on top of it. It does NOT change what the client
        // sees for a write race: with or without the flush, @Version's failure propagates out of this
        // transaction and CaseController.onWriteRace answers 409 — which is why the mutation removing
        // it survives, and why the flush is justified on the debugging property alone.
        cases.saveAndFlush(testCase);

        TestCaseDetailView view = renderer.render(testCase, liveSuites, vocabularies, companyId);
        versions.write(testCase, edit, decision, view, userId, now);

        // AFTER the render, unlike every reconcile above it, and the difference is not an
        // inconsistency: those write the case's own rows, so the response would report the case as
        // it was on the way in. This writes rows on OTHER aggregates and touches nothing the
        // response carries — TestCaseDetailView serves no suite version — so its only ordering
        // requirement is that the case's own write has landed, which the flush above is.
        suiteVersions.reconcileAfterCaseWrite(
                project.getId(), suitesBefore, List.of(testCase.getId()), userId, now);

        // "An explicit save promotes this into test_case + a new test_case_version and CLEARS THE
        // ROW" — V1's own comment on test_case_draft, and the clause that had no code behind it
        // until §128 (decision round 3, #57).
        //
        // In this transaction, not after it, for the reason the version row is: a committed save
        // whose buffer survived would make the editor offer to restore, on its next mount, content
        // the user has already saved — which reads as "your save did not land" about a save that
        // did, on the one screen where that doubt is most expensive.
        //
        // Cleared on EVERY successful save, including one whose content hash was unchanged and
        // which therefore wrote no version. The buffer's job ends when somebody presses Save; it is
        // not a record of what changed.
        //
        // THE SAVER'S OWN BUFFER, and nobody else's (V64, audit D1). ~~Cleared regardless of WHO
        // saved, because the buffer is per case and not per person — a colleague's save discards
        // your unsaved draft, which is the 1:1 primary key's cost showing up a second time.~~ That
        // was the defect D1 names: a save by somebody who was not in the room threw away work that
        // was not theirs to throw away. A colleague's draft now survives this save and comes back
        // marked `stale`, which is a statement the returning editor can act on.
        //
        // Usually deletes nothing. Most saves are not preceded by an autosave, so this is one
        // statement affecting zero rows rather than a load-then-delete — see the repository.
        drafts.deleteForCase(testCase.getId(), userId);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(testCase.getId()),
                // The version the edit produced, and whether it was a new one — not a field-by-field
                // diff. A diff belongs in the version snapshot, which holds the whole content and is
                // what a diff view reads; duplicating it here would be a second, lossier copy of
                // client content in a table with different retention. Recording the no-op save too
                // is deliberate: "Ana saved this and nothing changed" is a fact about who was in the
                // case, which is what an audit trail is for even when history has nothing to add.
                "{\"versionNo\":" + decision.versionNo()
                        + ",\"newVersion\":" + decision.isNew() + "}",
                now));

        // Its OWN audit row, not a field on the save's. An approval that ended is the fact somebody
        // will come looking for, and "case.updated" is the most common action in the table — burying
        // it there makes it findable only by whoever already knows to look. §75's argument for six
        // distinct verbs, applied to an act nobody explicitly requested, which is exactly why the
        // trail has to name it.
        if (unApproves) {
            auditEvents.save(AuditEvent.record(
                    project.getId(),
                    userId,
                    AUDIT_ACTION_RE_REVIEW,
                    AUDIT_TARGET,
                    List.of(testCase.getId()),
                    "{\"key\":" + AuditEvent.jsonString(testCase.getKey())
                            + ",\"from\":\"APPROVED\",\"to\":\"DRAFT\""
                            + ",\"reason\":\"EDIT_AFTER_APPROVAL\"}",
                    now));
        }

        return new Outcome.Updated(view);
    }

}
