package com.tcms.repository;

import com.tcms.project.ClassificationDimensionView;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.DisplayKeys;
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
 * Creating one case — the other half of authoring, and a harder write than editing one.
 *
 * <h2>What creating adds over editing, which is where the defects live</h2>
 *
 * <ol>
 *   <li><strong>A display key has to be allocated</strong> from the company's counter, by the one
 *       statement that cannot race ({@link DisplayKeys}). Every alternative that suggests itself —
 *       {@code MAX(key_seq) + 1}, read-then-write — issues duplicates under exactly the load a client
 *       importing a suite produces, and never in a test.</li>
 *   <li><strong>Version 1 has to be written here</strong>, not by the first edit. {@link
 *       CaseVersions#initial} exists because {@code decide()} would number it v2 — see its header for
 *       why reproducing the pre-versioning gap on a brand-new case is not defensible.</li>
 *   <li><strong>A retry must not create a second case.</strong> The SPA sends one
 *       {@code Idempotency-Key} per intent and the mock honoured it; nothing on the server did until
 *       this slice, so mock mode was proving a property live mode lacked. The mock is gone and the
 *       server's half is the whole of it now, which is why the property is pinned by a test rather
 *       than by a second implementation agreeing with the first.</li>
 * </ol>
 *
 * <h2>The permission is {@code case:create}, and one qualifier on it is NOT implemented</h2>
 *
 * <p>Held by Instance Admin, Company Admin, Test Lead, Test Engineer, and — <strong>"quarantine
 * folder only"</strong> — Automation Service (roles spec §A5/§A6, plan §6b, ERD §3). Viewer does not
 * hold it, which is the gate that matters: every read in this module asks {@code project:read}, which
 * all six roles hold, so a create gated like a read is one the client's own read-only stakeholder may
 * perform.
 *
 * <p><strong>The Automation Service restriction is not enforced, and this is the record of that.</strong>
 * There is no quarantine folder in the schema — {@code suite} has no marker column and
 * {@code project_configuration} names no default suite — because the concept arrives with CI ingestion
 * in M3. {@code V3}'s own comment already calls it "a service-layer rule: a permission cannot carry a
 * folder". So a holder of Automation Service can currently create a case in any suite of a granted
 * project. It is unreachable through a client today (API tokens are M3; the only authenticator is a
 * password login, so the role can only be attached by hand-written SQL), and the honest options were
 * to say so or to invent a {@code suite.is_quarantine} column to gate a caller that cannot yet exist.
 * It belongs to the ingestion slice, which can create the folder and prove the gate. <del>Like the
 * {@code OWN} qualifier still owed to {@code case:delete}.</del> → that debt was paid by {@code V12}
 * and then written off by {@code V51} (§207), so it is no longer the comparison to reach for.
 *
 * <h2>Everything else is slice 7's, deliberately</h2>
 *
 * <p>The gate, the validator, the step writer, the version writer, the audit row and the renderer are
 * all reused unchanged. A create that validated its own way would be a second set of answers to
 * "is this title acceptable", and the one that diverges is whichever was edited second — so
 * {@link CaseEdit#validate} is called with the same arguments the update path uses, with two
 * differences that are both properties of there being no prior case: the set of adoptable step ids is
 * <strong>empty</strong>, and the automation-key check excludes nothing.
 *
 * <p><strong>Since §200 there is a third, and it is not that shape</strong> — it is a rule about the
 * payload rather than about there being nothing stored yet, so it is the one place this path is
 * genuinely more permissive than an edit. {@link CaseEdit.ProcedureRule#DEFERRED}: a case may be
 * created before anybody has written how to run it, and what comes out is a {@code DRAFT} whose
 * missing procedure {@code case_health} only calls a fault once somebody approves it. Every other
 * rule — the title, the suite, the automation pair, the classification, the conditions — is the
 * update path's, unchanged, for the reason above.
 *
 * <h2>What commits together</h2>
 *
 * <p>The counter increment, the case row, its steps, the v1 version row and the audit row are one
 * transaction. The counter is in it on purpose: its row lock has to be held until the case that used
 * the number commits, or two creations can be issued the same key.
 */
@Service
public class CaseCreateService {

    /** Roles spec §A5. Not {@code case:edit}: a caller may create without being able to rewrite. */
    private static final String CREATE_CASE = "case:create";

    private static final String AUDIT_ACTION = "case.created";
    private static final String AUDIT_TARGET = "test_case";

    /**
     * The longest {@code Idempotency-Key} this endpoint will store.
     *
     * <p>The column is unbounded {@code TEXT} and the header is attacker-controlled, so the cap is
     * here rather than in the schema: a 10 MB header should be refused at the edge, not indexed. 200
     * is generous for the UUID the SPA sends and for any sane key format.
     */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    private final ProjectService projects;
    private final TestCaseRepository cases;
    private final SuiteRepository suites;
    private final CaseSteps steps;
    private final CaseClassifications classifications;
    private final CaseConditions conditions;
    private final CaseTags tags;
    private final ConditionService conditionLibrary;
    private final CaseRenderer renderer;
    private final CaseVersions versions;
    private final SuiteVersions suiteVersions;
    private final DisplayKeys displayKeys;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public CaseCreateService(
            ProjectService projects,
            TestCaseRepository cases,
            SuiteRepository suites,
            CaseSteps steps,
            CaseClassifications classifications,
            CaseConditions conditions,
            CaseTags tags,
            ConditionService conditionLibrary,
            CaseRenderer renderer,
            CaseVersions versions,
            SuiteVersions suiteVersions,
            DisplayKeys displayKeys,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.cases = cases;
        this.suites = suites;
        this.steps = steps;
        this.classifications = classifications;
        this.conditions = conditions;
        this.tags = tags;
        this.conditionLibrary = conditionLibrary;
        this.renderer = renderer;
        this.versions = versions;
        this.suiteVersions = suiteVersions;
        this.displayKeys = displayKeys;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /**
     * What happened. Four shapes, and no {@code Stale}: there is no prior state for a create to be
     * stale against, so {@code If-Match} has no meaning on it.
     */
    public sealed interface Outcome {

        /** Unreachable project — one bodiless 404, exactly as every read in this module (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code case:create} not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A new case. 201. */
        record Created(TestCaseDetailView view) implements Outcome {
        }

        /**
         * A retry of a create that already succeeded. 200 rather than 201, so a client can tell "I
         * made this" from "I already had this" — and so a well-behaved retry is not reported as a
         * second creation in anybody's logs.
         */
        record Replayed(TestCaseDetailView view) implements Outcome {
        }
    }

    /**
     * Creates a case.
     *
     * @param idempotencyKey the request's {@code Idempotency-Key} header, or null when absent
     */
    @Transactional
    public Outcome create(
            UUID userId, UUID projectId, CaseUpdateRequest request, String idempotencyKey) {

        UUID companyId = CompanyContext.require();

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, CREATE_CASE,
                project -> apply(companyId, userId, project, request, idempotencyKey));

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
            CaseUpdateRequest request,
            String idempotencyKey) {

        List<Suite> liveSuites = suites.findLiveForProject(project.getId());

        // FIRST, before validating anything. A retry of a create that succeeded must answer with the
        // case it made even if the payload has since drifted — the client is asking "did my intent
        // land", and answering 400 to a request that already created a case would send it to fix
        // fields on a case that exists.
        if (idempotencyKey != null) {
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
                return new Outcome.Invalid(Map.of("Idempotency-Key",
                        List.of("Send a key of at most " + MAX_IDEMPOTENCY_KEY + " characters.")));
            }
            Optional<TestCase> already =
                    cases.findByIdempotencyKey(project.getId(), idempotencyKey);
            if (already.isPresent()) {
                return new Outcome.Replayed(
                        render(companyId, userId, project.getId(), already.get(), liveSuites));
            }
        }

        Set<UUID> liveSuiteIds = new HashSet<>();
        for (Suite suite : liveSuites) {
            liveSuiteIds.add(suite.getId());
        }

        // Resolved once, used to validate and then to render — see CaseUpdateService for why the
        // two must be the same read rather than two reads a few lines apart.
        List<ClassificationDimensionView> vocabularies =
                renderer.vocabularies(userId, project.getId());
        ClassificationVocabulary vocabulary = ClassificationVocabulary.of(vocabularies);

        // The requested folder's imposition, read once and used three times below — the same
        // one-read rule the vocabulary above follows. Read from the REQUEST rather than from a
        // stored row, because there is none.
        CaseConditions.Reaching reaching = conditions.reachingAt(request.suiteId(), liveSuites);

        CaseEdit.Outcome validated = CaseEdit.validate(
                request,
                liveSuiteIds,
                // Empty: there is no case yet, so any step id the payload carries names a step
                // belonging to another case. Adopting it would move that step's identity — and
                // therefore its recorded results — onto this one.
                Set.of(),
                vocabulary,
                // Empty, for the same reason as the step ids above: nothing is stored yet, so there
                // is no classification to leave alone. A create that omits the field makes an
                // unclassified case, which is the only thing "leave it alone" can mean here.
                Set.of(),
                // The live library alone again, with no stored half — a new case attaches nothing
                // yet, so a retired entry is correctly refused here while an existing case attaching
                // one keeps saving.
                conditionLibrary.liveLibraryIds(
                        project.getId(),
                        CaseEdit.referencedConditionIds(
                                request.preconditions(), request.postconditions())),
                // Both empty, for the classification reason one line up: nothing is stored, so
                // there are no attachments to leave alone, and a create that omits the fields makes
                // a case with no conditions of its own.
                List.of(),
                List.of(),
                // Resolved from the REQUESTED suite, not from a stored one — there is no row yet.
                // A create with a suiteId inherits that folder's conditions the moment it exists,
                // so unlike the two empty arguments above this one is a real set.
                //
                // The SPA's create form cannot reach it: `CaseEditorPage` reads its inherited rows
                // from `existing`, which a create does not have, so no "inherited from Billing"
                // marker is drawn and there is nothing to toggle. The caller this exists for is
                // CaseCloneService, whose §6v rule — carry the source's declines when the clone
                // lands in the source's own suite — has been written down since §129 with no writer
                // to obey it. Refusing here instead would have forced the clone to write the rows
                // behind this path's back, and the version it wrote would then hash as a case that
                // declines nothing.
                reaching.declinable(),
                // The retention bound (§192), and on this path it is the same folder by
                // construction — a create cannot move a case, because there is nowhere to move it
                // from. It still goes through the same bound rather than being waved past with the
                // full set, so a clone landing in a folder that has since dropped the attachment
                // its source declined does not carry a decline of a rule nothing imposes.
                reaching.all(),
                // Nothing stored, for the classification reason above: a create declines exactly
                // what it asks to decline.
                List.of(),
                cases::existsByAutomationKey,
                // Nothing is stored yet, so every value of either refused field is a CHANGE and the
                // answer is what it always was: a 400 naming the field. B2 narrowed the rule to
                // "refused when it would change something" for the update path's round trip; on
                // this path the narrowing is vacuous, which is worth stating because it looks like
                // a pair of nulls that could mean anything.
                null,
                null,
                // §200: a case may be created before anybody has written how to run it. This is the
                // one rule the create path relaxes relative to the update path, and it is a rule
                // about the payload rather than about there being no prior case — so unlike the
                // arguments above it does not follow from "nothing is stored yet". The reason is
                // {@link CaseEdit.ProcedureRule}'s: the plan's Test cases tab authors a case from a
                // title, and requiring a step there means refusing the save or inventing content.
                // What is created is a DRAFT, which is what makes this cheap — case_health only
                // calls a missing procedure a fault once somebody has APPROVED the case.
                CaseEdit.ProcedureRule.DEFERRED);

        if (validated instanceof CaseEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        CaseEdit.Outcome.Valid accepted = (CaseEdit.Outcome.Valid) validated;
        CaseEdit edit = accepted.edit();

        // AFTER validation, so a rejected payload does not burn a key. Numbers are not recycled — the
        // counter only moves forward — so a client fixing a blank title would otherwise leave a
        // permanent hole in the company's key sequence for every failed attempt.
        DisplayKeys.Allocation allocated = displayKeys.allocate(DisplayKeys.EntityType.CASE, 1);

        Instant now = clock.instant();
        TestCase created = TestCase.create(
                project.getId(),
                allocated.keyAt(0),
                allocated.seqAt(0),
                edit,
                userId,
                idempotencyKey,
                now);

        // Flushed before the steps so the case row exists for test_step.case_id to reference, and so
        // a constraint this class did not validate — the unique key, the unique automation key — fails
        // at its cause rather than under the version row written on top of it.
        cases.saveAndFlush(created);
        steps.reconcile(created, edit.steps());
        classifications.reconcile(created, edit.classificationValueIds(), vocabulary, companyId);
        conditions.reconcile(
                created, edit.preconditionIds(), edit.postconditionIds(), companyId);
        // Nothing is stored, so this only ever inserts — but it goes through the same reconcile the
        // edit path uses rather than a create-only insert loop, for CaseEdit#of's reason one table
        // over: a second writer is a second set of rules, and the one that diverges is whichever
        // was edited second.
        conditions.reconcileOptouts(
                created.getId(),
                reaching.declinable(),
                reaching.all(),
                edit.declinedConditionIds(),
                companyId);
        // Beside the reconciles above and NOT inside `edit`: a tag is not authored content, so it
        // is not in the version hash (CaseEdit.Outcome.Valid). Before the render, for the same
        // reason as the other two — the response IS the version snapshot, and reconciling after it
        // would report the tags the case did not have.
        tags.reconcile(created, accepted.tags(), companyId);

        CaseVersions.Decision decision = versions.initial(edit);
        TestCaseDetailView view = renderer.render(created, liveSuites, vocabularies, companyId);
        versions.write(created, edit, decision, view, userId, now);

        // A new case lands in every suite that files it, and in every suite whose tag rule its tags
        // match — so its arrival changes those suites' composition and each owes a version (§238).
        // No holdersBefore reading: the case did not exist a moment ago, so nothing held it and the
        // empty before-set is the honest input rather than an omission.
        suiteVersions.reconcileAfterCaseWrite(
                project.getId(), Set.of(), List.of(created.getId()), userId, now);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(created.getId()),
                // The key, not the content. The version row holds the whole case and is what a diff
                // reads; repeating it here would be a second, lossier copy of client content in a
                // table with different retention. The key is what an audit reader recognises the case
                // by, and it is the one thing the id does not tell them.
                "{\"key\":\"" + created.getKey() + "\",\"versionNo\":1}",
                now));

        return new Outcome.Created(view);
    }

    /**
     * The case as the API serves it — the same renderers the grid, the detail endpoint and the update
     * endpoint use.
     *
     * <p>Not a fourth implementation of a case's shape. The argument is {@link CaseUpdateService}'s and
     * applies with the most force here: this response is the first thing anyone sees of a case, so a
     * field rendered differently would show one value on create and another on the reload that follows
     * it, with neither screen revealing which is stored.
     */
    private TestCaseDetailView render(
            UUID companyId, UUID userId, UUID projectId, TestCase row, List<Suite> liveSuites) {

        return renderer.render(companyId, userId, projectId, row, liveSuites);
    }
}
