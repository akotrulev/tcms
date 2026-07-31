package com.tcms.execution;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
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
 */
@Service
public class RunCreateService {

    /** Roles spec §A4. Held by every role except Viewer — see the class header. */
    private static final String CREATE_RUN = "run:create";

    private static final String AUDIT_ACTION = "run.created";
    private static final String AUDIT_TARGET = "test_run";

    /** {@code CaseCreateService}'s cap, for its reason. */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    private final ProjectService projects;
    private final TestRunRepository runs;
    private final TestPlanRepository plans;
    private final TestExecutionRepository executions;
    private final ConfigurationRepository configurations;
    private final RunService views;
    private final CaseFacts cases;
    private final ExecutionPeople people;
    private final DisplayKeys displayKeys;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public RunCreateService(
            ProjectService projects,
            TestRunRepository runs,
            TestPlanRepository plans,
            TestExecutionRepository executions,
            ConfigurationRepository configurations,
            RunService views,
            CaseFacts cases,
            ExecutionPeople people,
            DisplayKeys displayKeys,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.runs = runs;
        this.plans = plans;
        this.executions = executions;
        this.configurations = configurations;
        this.views = views;
        this.cases = cases;
        this.people = people;
        this.displayKeys = displayKeys;
        this.auditEvents = auditEvents;
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

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, CREATE_RUN,
                project -> apply(userId, project, request, idempotencyKey));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(
            UUID userId, Project project, RunWriteRequest request, String idempotencyKey) {

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

        // Explicit selection wins over the plan's scope, and they are never unioned — RunEdit's
        // header carries the argument. Resolved before validation because "would this run have
        // anything in it" is one of the things being validated.
        List<UUID> resolved;
        if (!requestedCases.isEmpty()) {
            // Narrowed to the live ones so the emptiness check below asks about what would actually
            // be materialised. An id that is not live is separately a field error, so this cannot
            // quietly shorten a run that is otherwise accepted.
            resolved = requestedCases.stream().filter(liveCases::contains).toList();
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
                request, planExists, liveCases, resolved, assigneeExists, pins.keySet());
        if (validated instanceof RunEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        RunEdit edit = ((RunEdit.Outcome.Valid) validated).edit();

        Configuration configuration = resolveConfiguration(project.getId(), request);
        if (configuration == null) {
            return new Outcome.Invalid(Map.of("configurationId",
                    List.of("That configuration is not in this project.")));
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
                userId,
                idempotencyKey,
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
                        + "}",
                now));

        return new Outcome.Created(views.viewOf(created));
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
