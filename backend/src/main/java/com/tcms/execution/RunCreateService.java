package com.tcms.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.project.ProjectSettingsService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.DisplayKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating one run — <strong>and materializing its executions</strong>, which is the whole of the
 * difficulty and the reason this slice mattered.
 *
 * <p>Everything before this could be seeded with SQL; a run could not, because a run is not one row.
 * Creating one resolves a case set, resolves each case's <em>current version</em>, and writes an
 * execution per case pinned to it. §38 named this exactly: <em>"this module's second crossing of the
 * seam and the first one that WRITES a case_version_id"</em>.
 *
 * <h2>The pin is resolved once, here, and that is the design</h2>
 *
 * <p>{@link CaseFacts#currentVersionIds} answers what version each case is on at this instant, and
 * every execution stores it. From then on the case may be edited freely: the execution keeps naming
 * the version it was created against, and {@link CaseFacts#pinnedCases} reads that version's
 * snapshot on every render.
 *
 * <p>The alternative — resolving the version when the result is recorded, or when the execution is
 * read — is the one that looks equivalent and is not. A tester who opens a run on Monday, is shown
 * the steps, and records a verdict on Wednesday must be recording it against Monday's text. Under
 * late resolution an edit on Tuesday silently changes what they agreed to.
 *
 * <h2>The permission is {@code run:create}, and Automation Service DOES hold it</h2>
 *
 * <p>Roles spec §A4 — the one write in this module a leaked CI token may perform besides
 * {@code result:submit}, and deliberately so: a CI job opens the run it is about to post results
 * into. That makes this the widest write gate in the product, and it is worth stating what bounds
 * it. A run creates nothing durable that anybody authored: it names cases that already exist, pins
 * versions that already exist, and produces rows whose only content is "not yet run". It cannot
 * change a case, a plan or a verdict. The blast radius of the token is a project with extra empty
 * runs in it, which is noise rather than damage — and that is the whole argument for §A6's smallest
 * role being allowed this and not {@code plan:create}.
 *
 * <h2>What commits together</h2>
 *
 * <p>The counter increment, the run row, every execution and the audit row are one transaction. A
 * partially materialised run — some of its checklist missing, no error anywhere — is the failure
 * this rules out, and it is indistinguishable by inspection from a run whose scope was smaller.
 *
 * <h2>§6g's gating rule, added in §82</h2>
 *
 * <p>A project may require every case in a run to be {@code APPROVED}. Three things about how that
 * is applied here are decisions rather than mechanics:
 *
 * <p><strong>It binds on the RESOLVED set, not on {@code caseIds}.</strong> A run names its cases
 * four ways since §99 — three explicit arms and the plan's scope — and every one of them but the
 * first is a way to reach cases without listing them: gating only the explicit list would leave
 * "create the run from the plan instead", "from the suite instead", "by tag instead" as bypasses
 * that cost no permission and leave no trace, on the very path a release run is most likely to
 * take. Because the gate reads {@code edit.caseIds()} — the union, after resolution — a new arm is
 * covered by construction rather than by remembering, which is the property that made §99's arms
 * cheap to add and is worth not losing.
 *
 * <p><strong>The whole run is refused, never trimmed.</strong> Dropping the unapproved cases and
 * materialising the rest is the shape {@code CaseBulkService} uses for a filtered selection and it
 * is wrong here for the reason this class's own header already gives about partial materialisation:
 * a checklist that is quietly shorter than the one the tester asked for is indistinguishable from a
 * run whose scope was smaller, and its pass rate is computed over a denominator nobody chose. So
 * this is a 409 naming the offending keys.
 *
 * <p><strong>The override is {@code case:approve} and is asked for only on the refusal
 * path.</strong> §6g asks for "a documented lead override for the 2am release" and no key is
 * invented for it (§67's argument, via §79 and §80): a key {@code V3} seeds to nobody is a 403
 * nobody can grant their way out of. {@code case:approve} is <em>derived</em> — its holder can reach
 * this exact run today by approving the cases first, so the override widens nothing. What it changes
 * is the record it leaves: the cases stay honestly unapproved and the audit row says the gate was
 * overridden, where the workaround it replaces writes approvals nobody performed into the very
 * history §6g exists to keep honest.
 */
@Service
public class RunCreateService {

    /** Roles spec §A4. Held by every role except Viewer — see the class header. */
    private static final String CREATE_RUN = "run:create";

    /**
     * §6g's gating override (§82). Spelled out here rather than imported: the constant lives on
     * {@code repository.LifecycleLadder.Keys}, and this module may not depend on {@code repository}
     * (plan §3). A second spelling of one key is a real cost and the alternative is worse — a port
     * in {@code project} whose only job is to hand back a string literal — so it is written once
     * with its reason, and {@code SeedDataTest} is what asserts the catalogue still contains it.
     */
    private static final String APPROVE_CASE = "case:approve";

    private static final String AUDIT_ACTION = "run.created";
    private static final String AUDIT_TARGET = "test_run";

    /** {@code CaseCreateService}'s cap, for its reason. */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    /**
     * How many keys the refusal quotes before it stops counting. A gated project rejecting a
     * two-hundred-case run must not answer with a two-hundred-key sentence — and the first few in
     * key order are enough to find the problem, because the caller is looking at the same list in
     * the same order.
     */
    private static final int REFUSAL_KEYS_QUOTED = 5;

    private final ProjectService projects;
    private final ProjectSettingsService settings;
    private final TestRunRepository runs;
    private final TestPlanRepository plans;
    private final TestExecutionRepository executions;
    private final ConfigurationRepository configurations;
    private final RunService views;
    private final CaseFacts cases;
    private final ExecutionPeople people;
    private final DisplayKeys displayKeys;
    private final AuditEventRepository auditEvents;
    /** Writes `scope_request` (B7) and nothing else — the module's own mapper, never a new one. */
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public RunCreateService(
            ProjectService projects,
            ProjectSettingsService settings,
            TestRunRepository runs,
            TestPlanRepository plans,
            TestExecutionRepository executions,
            ConfigurationRepository configurations,
            RunService views,
            CaseFacts cases,
            ExecutionPeople people,
            DisplayKeys displayKeys,
            AuditEventRepository auditEvents,
            ObjectMapper json,
            Clock clock) {

        this.projects = projects;
        this.settings = settings;
        this.runs = runs;
        this.plans = plans;
        this.executions = executions;
        this.configurations = configurations;
        this.views = views;
        this.cases = cases;
        this.people = people;
        this.displayKeys = displayKeys;
        this.auditEvents = auditEvents;
        this.json = json;
        this.clock = clock;
    }

    /** {@link PlanCreateService.Outcome}'s five shapes. */
    public sealed interface Outcome {

        record NotFound() implements Outcome {
        }

        record Forbidden() implements Outcome {
        }

        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /**
         * §6g's gating rule refuses this run. A <strong>409</strong>, not a 400: every id in the
         * request names a live case in this project and the permission is held — it is the
         * project's own policy that refuses, which is the same distinction {@code PlanLifecycle}
         * draws for a move the ladder does not have.
         */
        record Refused(String message) implements Outcome {
        }

        /** A new run, with its executions. 201. */
        record Created(TestRunView view) implements Outcome {
        }

        /** A retry. 200 — and, critically, no second checklist. */
        record Replayed(TestRunView view) implements Outcome {
        }
    }

    /**
     * Creates a run and materializes its executions.
     *
     * @param idempotencyKey the request's {@code Idempotency-Key} header, or null when absent
     */
    @Transactional
    public Outcome create(
            UUID userId, UUID projectId, RunWriteRequest request, String idempotencyKey) {

        return create(userId, projectId, request, idempotencyKey, RunSource.MANUAL);
    }

    /**
     * The same create, for the run an ingested report lands in — {@code IngestionService}'s only
     * way to make one.
     *
     * <p><strong>This exists so that ingestion does not grow a second run-creation path.</strong>
     * Materialising a run is not one row: it resolves a case set, resolves each case's current
     * version, allocates a display key, writes an execution per case and audits the whole thing as
     * one transaction. A copy of that in {@code IngestionService} would be a second implementation
     * of the pin — the one thing this class's header says must be resolved once and never
     * re-resolved — and the two would drift on the first change to either.
     *
     * <p>So the CI path enters here, and {@code run:create} is asked <strong>here</strong>, exactly
     * as it is for a person. That is the whole reason §A6 grants an Automation Service token
     * {@code run:create}: this class's header already argues that a CI job opening the run it is
     * about to post into is the widest write gate in the product and a bounded one.
     *
     * <p>Package-private, and {@code source} is not a parameter a caller chooses from a request
     * field: there are exactly two doors and each names its own. See {@link TestRun#create}.
     */
    @Transactional
    Outcome createIngested(
            UUID userId, UUID projectId, RunWriteRequest request, String idempotencyKey) {

        return create(userId, projectId, request, idempotencyKey, RunSource.INGESTED);
    }

    private Outcome create(
            UUID userId,
            UUID projectId,
            RunWriteRequest request,
            String idempotencyKey,
            RunSource source) {

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, CREATE_RUN,
                project -> apply(userId, project, request, idempotencyKey, source));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }


    /**
     * The run's request, as the JSON {@code test_run.scope_request} holds (B7).
     *
     * <p>Normalised the way the resolution normalised it — de-duplicated ids, trimmed tags — so
     * what is recorded is what the server acted on rather than the bytes that arrived. A caller
     * that named the same suite twice asked for one suite.
     *
     * <p><strong>Null for an INGESTED run</strong>, which had no request: a report arrived and the
     * run was opened to hold its results. Recording {@code {}} there would say "asked for nothing",
     * which is a different and false statement — the column's own comment makes the absence mean
     * <em>not recorded</em>, and this is the path that produces it.
     */
    private String scopeRequestOf(RunWriteRequest request, RunSource source) {
        if (source == RunSource.INGESTED) {
            return null;
        }
        ObjectNode node = json.createObjectNode();
        if (request.planId() != null) {
            node.put("planId", request.planId().toString());
        }
        putIds(node, "caseIds", PlanEdit.distinct(request.caseIds()));
        putIds(node, "suiteIds", PlanEdit.distinct(request.suiteIds()));
        ArrayNode tags = node.putArray("tags");
        RunEdit.tagsOf(request).forEach(tags::add);
        return node.toString();
    }

    private static void putIds(ObjectNode node, String field, List<UUID> ids) {
        ArrayNode array = node.putArray(field);
        ids.forEach(id -> array.add(id.toString()));
    }

    private Outcome apply(
            UUID userId,
            Project project,
            RunWriteRequest request,
            String idempotencyKey,
            RunSource source) {

        UUID companyId = CompanyContext.require();

        // FIRST — CaseCreateService's rule, and the stakes are higher here than anywhere else it has
        // been applied: a replay that fell through would not merely make a second row, it would make
        // a second CHECKLIST, and the same tester's results would end up split across two runs that
        // each report a pass rate over a denominator nobody intended.
        if (idempotencyKey != null) {
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
                return new Outcome.Invalid(Map.of("Idempotency-Key",
                        List.of("Send a key of at most " + MAX_IDEMPOTENCY_KEY + " characters.")));
            }
            Optional<TestRun> already = runs.findByIdempotencyKey(project.getId(), idempotencyKey);
            if (already.isPresent()) {
                return new Outcome.Replayed(views.viewOf(already.get()));
            }
        }

        boolean planExists = request.planId() != null
                && plans.findLiveInProject(project.getId(), request.planId()).isPresent();

        List<UUID> requestedCases = PlanEdit.distinct(request.caseIds());
        Set<UUID> liveCases = cases.liveCaseIds(project.getId(), requestedCases);
        List<UUID> requestedSuites = PlanEdit.distinct(request.suiteIds());
        Set<UUID> liveSuites = cases.liveSuiteIds(project.getId(), requestedSuites);
        List<String> requestedTags = RunEdit.tagsOf(request);

        // Explicit selection wins over the plan's scope, and they are never unioned — RunEdit's
        // header carries the argument. Resolved before validation because "would this run have
        // anything in it" is one of the things being validated.
        //
        // The three arms are handed over RAW rather than narrowed to the live ones first: the query
        // itself is bounded by `c.project_id = :projectId AND c.deleted_at IS NULL` and by the same
        // predicate on the suite, so a deleted or foreign id contributes nothing there. Filtering
        // here as well would put the bound in two places, and the one that drifts is whichever the
        // next arm forgets. Liveness is still checked — as a field error, in RunEdit, which is a
        // different question: what would be materialised versus what the caller believes exists.
        List<UUID> resolved;
        if (!requestedCases.isEmpty() || !requestedSuites.isEmpty() || !requestedTags.isEmpty()) {
            resolved = cases.caseIdsInRunScope(
                    project.getId(), requestedCases, requestedSuites, requestedTags);
        } else if (planExists) {
            resolved = cases.caseIdsInPlanScope(project.getId(), request.planId());
        } else {
            resolved = List.of();
        }

        Map<UUID, UUID> pins = cases.currentVersionIds(project.getId(), resolved);

        // isActiveMember, NOT resolve: app_user is instance-level (ERD §0.1's exception list), so
        // resolve finds any account on the instance and would accept a user id from another client
        // entirely. See ExecutionPeople#isActiveMember — this distinction cost a red test.
        boolean assigneeExists = request.assigneeId() != null
                && people.isActiveMember(companyId, request.assigneeId());

        RunEdit.Outcome validated = RunEdit.validateCreate(
                request, planExists, liveCases, liveSuites, resolved, assigneeExists,
                pins.keySet());
        if (validated instanceof RunEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        RunEdit edit = ((RunEdit.Outcome.Valid) validated).edit();

        Configuration configuration = resolveConfiguration(project.getId(), request);
        if (configuration == null) {
            return new Outcome.Invalid(Map.of("configurationId",
                    List.of("That configuration is not in this project.")));
        }

        // §6g's gating rule, LAST of the refusals and BEFORE the key is allocated.
        //
        // Last, because a request that is both malformed and gated must get the field errors: those
        // are the ones the caller can act on, where "these cases are not approved" about a case list
        // that also names a deleted id sends them to fix the wrong thing. Before the allocation for
        // §29's reason, one line down — a refused run burns no key.
        //
        // On edit.caseIds() and not request.caseIds(): the resolved set is what would actually be
        // materialised, which is the whole difference between gating a run and gating a form.
        ApprovalGating.Verdict gate = gate(
                userId, project.getId(), edit.caseIds(), request.overrideApprovalGate());
        if (gate.refusal() != null) {
            return new Outcome.Refused(gate.refusal());
        }

        // AFTER validation, so a rejected payload burns no key — §29's rule, and here a run that
        // named a plan with an empty scope is a mistake somebody makes twice in a row.
        DisplayKeys.Allocation allocated = displayKeys.allocate(DisplayKeys.EntityType.RUN, 1);

        Instant now = clock.instant();
        TestRun created = TestRun.create(
                project.getId(),
                configuration.getId(),
                allocated.keyAt(0),
                allocated.seqAt(0),
                edit,
                // The DOOR the request came through, never the caller's role. See TestRun#create's
                // header: an Automation Service token creating a run through the public endpoint
                // has created a MANUAL run, and only a posted report creates an INGESTED one.
                source,
                userId,
                idempotencyKey,
                // B7 — what was ASKED for, beside what it materialised. Written from the REQUEST
                // and not from `edit.caseIds()`: the resolved list is the checklist, and the whole
                // value of this column is being able to see the difference between the two.
                scopeRequestOf(request, source),
                now);

        // Flushed before the executions so test_execution.run_id has a row to reference, and so
        // uq_test_run_key fails at its cause rather than under 200 executions written on top of it.
        runs.saveAndFlush(created);

        List<TestExecution> checklist = new ArrayList<>();
        for (UUID caseId : edit.caseIds()) {
            checklist.add(TestExecution.materialise(
                    project.getId(),
                    created.getId(),
                    caseId,
                    // Never null: RunEdit refused the create unless every resolved case was
                    // pinnable, so this is a lookup in a map validation has already covered.
                    pins.get(caseId),
                    edit.assigneeId(),
                    edit.releaseVersion(),
                    now));
        }
        executions.saveAll(checklist);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(created.getId()),
                // The size of the checklist is the fact an audit reader needs and the one nothing
                // else records: the run's scope can be inferred later from its executions, but "how
                // many did it start with" is what a run that has since had rows removed is read
                // against.
                "{\"key\":" + AuditEvent.jsonString(created.getKey())
                        + ",\"executions\":" + checklist.size()
                        + ",\"planId\":" + (edit.planId() == null
                                ? "null" : AuditEvent.jsonString(edit.planId().toString()))
                        // §6g's "documented" override, and this is where it is documented. Written
                        // only when the gate actually fired, so the field's presence means somebody
                        // started a run of unapproved cases against their project's own policy —
                        // where writing false on every run would bury that in noise. A payload key
                        // rather than a distinct audit verb (run.created_with_override): overriding
                        // is not a different act, it is the same act performed against the policy,
                        // and RunStatusService's argument for distinct verbs is about acts.
                        + (gate.overridden() ? ",\"approvalGateOverridden\":true" : "")
                        + "}",
                now));

        return new Outcome.Created(views.viewOf(created));
    }

    /**
     * §6g's gating rule for this run — see the class header for why it binds where it does, and
     * {@link ApprovalGating.Verdict} for what the answer means.
     *
     * <p>Two early exits before either question is asked, and they are the ordinary path: a project
     * that never turned gating on pays one settings read, and a gated project whose cases are all
     * approved pays one more. Neither asks a permission, which is what keeps the override incapable
     * of narrowing anybody's reach — a request that was never going to be refused never has a
     * second permission asked of it at all.
     */
    private ApprovalGating.Verdict gate(
            UUID userId, UUID projectId, List<UUID> caseIds, Boolean overrideRequested) {

        if (!settings.effective(projectId).requireApprovedCaseForRun()) {
            return ApprovalGating.Verdict.PROCEED;
        }
        List<String> unapproved = cases.unapprovedCases(projectId, caseIds);
        if (unapproved.isEmpty()) {
            return ApprovalGating.Verdict.PROCEED;
        }
        return ApprovalGating.Verdict.fired(
                Boolean.TRUE.equals(overrideRequested)
                        && projects.holds(userId, projectId, APPROVE_CASE),
                refusal(unapproved, caseIds.size()));
    }

    /**
     * The refusal, naming the first few offenders in key order.
     *
     * <p>Both numbers are in it on purpose. "3 of 4" tells somebody the run is nearly ready and
     * "180 of 200" tells them the project turned this on yesterday and nobody has been through the
     * library yet — the same message, and two completely different next actions.
     */
    private static String refusal(List<String> unapproved, int total) {
        List<String> quoted = unapproved.stream().limit(REFUSAL_KEYS_QUOTED).toList();
        String rest = unapproved.size() > quoted.size()
                ? " and " + (unapproved.size() - quoted.size()) + " more"
                : "";

        return "This project only runs approved test cases. "
                + unapproved.size() + " of the " + total + " in this run "
                + (unapproved.size() == 1 ? "is" : "are") + " not approved: "
                + String.join(", ", quoted) + rest + ".";
    }

    /**
     * The named configuration, or the project's default.
     *
     * <p>{@code configuration_id} is {@code NOT NULL} and V1's comment says why: every project is
     * seeded with a "Default" configuration so that runs without an explicit environment point at it
     * rather than carrying NULL, which keeps the column non-null through every rollup read model.
     * So an absent {@code configurationId} is not an error — it is the ordinary case, and the field
     * is optional on the contract because of it.
     *
     * <p>A named configuration is matched inside the project's own list rather than fetched by id:
     * {@code configuration} is project-scoped, and an id from a sibling project would otherwise pass
     * the foreign key and RLS both, giving this run an environment from another product.
     *
     * @return null when the id names no configuration of this project, which the caller turns into a
     *         field error. Never null for an absent id, because {@code tcms_seed_project_defaults}
     *         guarantees the default — and if that guarantee were broken the honest failure is the
     *         same field error rather than a null foreign key at flush.
     */
    private Configuration resolveConfiguration(UUID projectId, RunWriteRequest request) {
        List<Configuration> available =
                configurations.findByProjectIdOrderByIsDefaultDescNameAsc(projectId);

        if (request.configurationId() != null) {
            return available.stream()
                    .filter(candidate -> candidate.getId().equals(request.configurationId()))
                    .findFirst()
                    .orElse(null);
        }
        // The list is ordered is_default DESC, so the default is first when one exists.
        return available.stream().filter(Configuration::isDefault).findFirst()
                .orElse(available.isEmpty() ? null : available.getFirst());
    }
}
