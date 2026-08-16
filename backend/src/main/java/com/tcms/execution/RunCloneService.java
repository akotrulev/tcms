package com.tcms.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cloning one run — plan §6v's highest-value clone, and the one that is genuinely a create.
 *
 * <h2>Same design as {@code repository.CaseCloneService}, and here it costs nothing</h2>
 *
 * <p>This class reads the source, builds the {@link RunWriteRequest} the create endpoint already
 * takes, and hands it to {@link RunCreateService}. Everything §6v asks for then holds by
 * construction: the key is allocated by the create, the run is born {@code OPEN} with every
 * execution untested, {@code Idempotency-Key} behaves as it does on a create — and, most of all,
 * <strong>no result, no evidence, no defect link and no timestamp can come across, because nothing
 * reachable from a create request names one</strong>. The safety of a clone-run is a property of the
 * shape rather than a list of things this class remembers not to copy.
 *
 * <p>It works here for a reason worth stating, because it did <em>not</em> work for the plan:
 * {@code RunWriteRequest} is the whole editable set of a run. There is no run editor beyond the
 * status ladder, so the create contract and the aggregate are the same object. {@link PlanCloneService}
 * has the opposite problem and its header carries it.
 *
 * <h2>The scope is the RESOLVED case set, and that is forced rather than chosen</h2>
 *
 * <p>§99 gives a run three scope arms — cases, suites, tags — and {@code test_run} <strong>stores
 * none of them</strong>. They are resolved once, at creation, into {@code test_execution} rows; the
 * declaration is not kept. So a clone can only copy what the source actually materialised, which it
 * does by passing those case ids as {@code caseIds}.
 *
 * <p>The consequence is worth being explicit about, because a reader will expect the other one:
 * <strong>cloning a suite-scoped run does not pick up the cases added since.</strong> That is §6v's
 * own distinction between a clone (frozen at copy time) and a template (a query re-evaluated), and
 * for once the schema decides it rather than the design.
 *
 * <h2>§6g's approval gate is re-evaluated, and the override is not inherited</h2>
 *
 * <p>Because this is a create, {@link ApprovalGating} runs again over the resolved cases — so a
 * clone of last month's run into this month's build is refused if those cases are no longer approved,
 * which is the right answer and not one this class had to write. The override travels on the
 * <em>clone request</em> and never from the source run: it is a statement about a request, nothing
 * stores it, and inheriting it would launder a documented exception into a permanent one.
 *
 * <h2>The gate is {@code run:create}, which Automation Service holds</h2>
 *
 * <p>Deliberately not narrowed. A CI credential may already open a run and post results into it
 * (§A6), and a clone of a run is a run — inventing a stricter key here would mean the product's
 * smallest role could create a checklist from scratch and not from the one it ran yesterday.
 * {@code run:execute}, the key §124 added to separate a person from a credential, guards recording
 * verdicts and is not asked for here: cloning a run records nothing.
 */
@Service
public class RunCloneService {

    private static final String AUDIT_ACTION = "run.cloned";
    private static final String AUDIT_TARGET = "test_run";

    private final ProjectService projects;
    private final RunCreateService creation;
    private final TestRunRepository runs;
    private final TestExecutionRepository executions;
    private final AuditEventRepository auditEvents;
    /** Reads the source's recorded request back (B7). The module's own mapper. */
    private final ObjectMapper json;
    private final Clock clock;

    RunCloneService(
            ProjectService projects,
            RunCreateService creation,
            TestRunRepository runs,
            TestExecutionRepository executions,
            AuditEventRepository auditEvents,
            ObjectMapper json,
            Clock clock) {

        this.projects = projects;
        this.creation = creation;
        this.runs = runs;
        this.executions = executions;
        this.auditEvents = auditEvents;
        this.json = json;
        this.clock = clock;
    }

    /** {@link RunCreateService.Outcome}'s six shapes, because a clone is a create. */
    public sealed interface Outcome {

        /** Unreachable project, or no such live run in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code run:create} not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages — the create path's, unedited. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** §6g refuses the cases this run would cover. 409, and the source's override is not it. */
        record Refused(String message) implements Outcome {
        }

        /** The new run, with its checklist. 201. */
        record Created(TestRunView view) implements Outcome {
        }

        /** This intent already cloned. 200, and no second checklist. */
        record Replayed(TestRunView view) implements Outcome {
        }
    }

    /**
     * Clones a run.
     *
     * <p>One transaction with the create it delegates to, so the run, its executions and both audit
     * rows commit together.
     */
    @Transactional
    public Outcome cloneRun(
            UUID userId,
            UUID projectId,
            UUID runId,
            RunCloneRequest request,
            String idempotencyKey) {

        Optional<TestRun> found = projects
                .readVisible(userId, projectId,
                        project -> runs.findLiveInProject(project.getId(), runId))
                .flatMap(outer -> outer);

        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestRun source = found.get();

        // B7 — re-run what the source was ASKED for, when the caller asks for that and the source
        // has a request to re-run. Refused rather than quietly downgraded to the ordinary clone:
        // the two produce different case sets and look identical afterwards, so a silent fallback
        // would answer a question nobody asked.
        RunScopeRequestView recorded =
                RunScopeRequestView.parse(json, source.getScopeRequest());
        if (Boolean.TRUE.equals(request.rescope()) && recorded == null) {
            return new Outcome.Invalid(Map.of("rescope", List.of(
                    "This run has no recorded scope to re-resolve — it was created before the "
                            + "request was stored, or by an ingested report. Clone it as it is, or "
                            + "create a new run from the suites and tags you want.")));
        }

        RunWriteRequest derived = Boolean.TRUE.equals(request.rescope())
                ? rescoped(source, request, recorded)
                : derive(source, request);

        RunCreateService.Outcome created =
                creation.create(userId, projectId, derived, idempotencyKey);

        return switch (created) {
            case RunCreateService.Outcome.NotFound ignored -> new Outcome.NotFound();
            case RunCreateService.Outcome.Forbidden ignored -> new Outcome.Forbidden();
            case RunCreateService.Outcome.Invalid invalid -> new Outcome.Invalid(invalid.errors());
            case RunCreateService.Outcome.Refused refused ->
                    new Outcome.Refused(refused.message());
            // A replay records no second provenance row — the first attempt already wrote one.
            case RunCreateService.Outcome.Replayed replayed ->
                    new Outcome.Replayed(replayed.view());
            case RunCreateService.Outcome.Created fresh -> {
                recordProvenance(userId, projectId, source, fresh.view(), clock.instant());
                yield new Outcome.Created(fresh.view());
            }
        };
    }


    /**
     * The source's own request, re-asked (B7).
     *
     * <p>The arms rather than the case list, which is the whole difference: the create resolves
     * them again, so a case filed into one of those suites since the source ran is on this
     * checklist and a case deleted since is not. That is the intention "run the smoke suite again"
     * has and the ordinary clone cannot express.
     *
     * <p>Everything that is not scope is {@link #derive}'s — the same fallbacks, the same two
     * fields that deliberately do not inherit. Only the four scope arguments differ, and they are
     * taken whole from the record rather than merged with anything: a re-ask is the same request,
     * not the same request plus what it happened to materialise.
     */
    private RunWriteRequest rescoped(
            TestRun source, RunCloneRequest request, RunScopeRequestView recorded) {

        RunWriteRequest asIs = derive(source, request);
        return new RunWriteRequest(
                asIs.name(),
                recorded.planId(),
                asIs.configurationId(),
                asIs.releaseVersion(),
                asIs.buildNumber(),
                asIs.commitSha(),
                asIs.branch(),
                recorded.caseIds(),
                recorded.suiteIds(),
                recorded.tags(),
                asIs.assigneeId(),
                asIs.overrideApprovalGate());
    }

    /**
     * The source run as a create request.
     *
     * <p>Every override falls back to the source except the two that must not — see
     * {@link RunCloneRequest} — and {@code commitSha} / {@code branch} are neither, being fields the
     * clone request does not carry and the clone does not inherit (the comment on them below says
     * why). {@code suiteIds} and {@code tags} are left empty because the resolved
     * case list <em>is</em> the scope: passing an arm as well would make the create re-resolve it and
     * quietly widen the clone past what the source actually covered.
     */
    private RunWriteRequest derive(TestRun source, RunCloneRequest request) {
        List<UUID> caseIds = new ArrayList<>();
        for (TestExecution execution : executions.findForRun(source.getProjectId(), source.getId())) {
            caseIds.add(execution.getCaseId());
        }

        return new RunWriteRequest(
                request.name(),
                source.getPlanId(),
                request.configurationId() == null
                        ? source.getConfigurationId() : request.configurationId(),
                request.releaseVersion() == null
                        ? source.getReleaseVersion() : request.releaseVersion(),
                request.buildNumber() == null ? source.getBuildNumber() : request.buildNumber(),
                // NEITHER IS INHERITED, and this is the one fallback that goes the other way. The
                // three above fall back because a re-run is against the same release until the caller
                // says otherwise; a git coordinate names the code that RAN, and a fresh clone has run
                // nothing. Carrying the source's sha forward would ship a run claiming a commit it
                // never tested — uncorrectable, since a run has no edit endpoint — where null says it
                // does not know what code this pass covers, which is true of a manual re-run days
                // later. So `TestRunCloneInput` declares no override for either: there is nothing to
                // override.
                null,
                null,
                caseIds,
                // Empty, not null-and-hope: the resolved list above is the whole scope.
                List.of(),
                List.of(),
                // No fallback — a run has no assignee to fall back to. RunCloneRequest says why.
                request.assigneeId(),
                // From the CLONE's request, never the source's. See the class header.
                request.overrideApprovalGate());
    }

    /**
     * The provenance row §6v asks for — a second audit event beside {@code run.created}.
     *
     * <p>{@code CaseCloneService.recordProvenance}'s shape and its argument: the created row is what
     * every existing audit reader understands, and "where did this run come from" is answered by
     * filtering for {@code run.cloned} rather than by parsing detail out of every creation. Both
     * keys, because a row naming only its source makes the reader join to find what it became.
     */
    private void recordProvenance(
            UUID userId, UUID projectId, TestRun source, TestRunView clone, Instant now) {

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(clone.id()),
                "{\"key\":" + AuditEvent.jsonString(clone.key())
                        + ",\"clonedFrom\":" + AuditEvent.jsonString(source.getKey())
                        + ",\"clonedFromId\":" + AuditEvent.jsonString(source.getId().toString())
                        + "}",
                now));
    }
}
