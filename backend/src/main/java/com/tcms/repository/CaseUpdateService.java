package com.tcms.repository;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
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
 * <strong>every one of the six built-in roles holds it</strong>, because that is what makes a granted
 * project visible. A write gated the same way is a write a Viewer may perform, and a Viewer is
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
    private static final String AUDIT_TARGET = "test_case";

    private final ProjectService projects;
    private final TestCaseRepository cases;
    private final SuiteRepository suites;
    private final CaseSteps steps;
    private final TestStepRepository stepRows;
    private final SharedStepService sharedSteps;
    private final CaseRenderer renderer;
    private final CaseVersions versions;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public CaseUpdateService(
            ProjectService projects,
            TestCaseRepository cases,
            SuiteRepository suites,
            CaseSteps steps,
            TestStepRepository stepRows,
            SharedStepService sharedSteps,
            CaseRenderer renderer,
            CaseVersions versions,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.cases = cases;
        this.suites = suites;
        this.steps = steps;
        this.stepRows = stepRows;
        this.sharedSteps = sharedSteps;
        this.renderer = renderer;
        this.versions = versions;
        this.auditEvents = auditEvents;
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
     * @param expectedLockVersion the parsed {@code If-Match}, or null when the header was absent
     */
    @Transactional
    public Outcome update(
            UUID userId,
            UUID projectId,
            UUID caseId,
            CaseUpdateRequest request,
            Long expectedLockVersion) {

        UUID companyId = CompanyContext.require();

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, EDIT_CASE,
                project -> apply(companyId, userId, project, caseId, request, expectedLockVersion));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(
            UUID companyId,
            UUID userId,
            Project project,
            UUID caseId,
            CaseUpdateRequest request,
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

        Set<UUID> existingStepIds = new HashSet<>();
        Set<UUID> storedSharedStepIds = new HashSet<>();
        for (TestStep step : stepRows.findForCase(testCase.getId())) {
            existingStepIds.add(step.getId());
            if (step.getSharedStepId() != null) {
                storedSharedStepIds.add(step.getSharedStepId());
            }
        }

        // The library's live entries UNIONED with what this case already references — see
        // CaseEdit#validate. The union is the whole point: the first half refuses a sibling
        // project's entry, and without the second, retiring an entry would make every case using
        // it unsaveable. Only the ids the payload actually names are looked up, so a case with no
        // shared steps — most of them — costs no query.
        Set<UUID> acceptableSharedStepIds = new HashSet<>(storedSharedStepIds);
        acceptableSharedStepIds.addAll(sharedSteps.liveLibraryIds(
                project.getId(), CaseEdit.referencedSharedStepIds(request.steps())));

        CaseEdit.Outcome validated = CaseEdit.validate(
                request,
                liveSuiteIds,
                existingStepIds,
                acceptableSharedStepIds,
                key -> cases.existsByAutomationKeyOtherThan(key, testCase.getId()));

        if (validated instanceof CaseEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        CaseEdit edit = ((CaseEdit.Outcome.Valid) validated).edit();

        Instant now = clock.instant();
        testCase.applyEdit(edit, userId, now);
        steps.reconcile(testCase, edit.steps());

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

        TestCaseDetailView view = render(companyId, userId, project.getId(), testCase, liveSuites);
        versions.write(testCase, edit, decision, view, userId, now);

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

        return new Outcome.Updated(view);
    }

    /**
     * The case as the API serves it — the same renderers the grid and the detail endpoint use.
     *
     * <p>Was a private method here and a byte-identical one in {@link CaseCreateService}, each
     * arguing in its javadoc that there must not be another copy. {@link CaseBulkService} would have
     * been the third, so the argument became {@link CaseRenderer}. §27's reasoning is unchanged and
     * now lives there: a field rendered differently from {@code GET} shows one value on save and
     * another on reload, with neither screen revealing which is stored.
     */
    private TestCaseDetailView render(
            UUID companyId, UUID userId, UUID projectId, TestCase row, List<Suite> liveSuites) {

        return renderer.render(companyId, userId, projectId, row, liveSuites);
    }
}
