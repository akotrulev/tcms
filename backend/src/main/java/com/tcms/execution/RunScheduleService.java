package com.tcms.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD over standing run requests (§247).
 *
 * <h2>{@code run:create}, and no new key</h2>
 *
 * <p>A schedule is <strong>delegated run creation</strong>: a caller who may create a run now may
 * create one every Monday, and nothing a schedule can do is a thing {@code POST /runs} cannot. A
 * {@code schedule:manage} key would need six seeded grants — a second migration, where {@code V68}
 * is this area's only one — plus the contract's permission enum, {@code lib/permissions.ts} and two
 * matrix copies that {@code matrixCopies.test.ts} compares cell by cell in both directions, all for
 * a distinction the product does not draw. The catalogue stays at 55.
 *
 * <p>The key is {@code RunCreateService.CREATE_RUN}, <strong>imported rather than spelled
 * again</strong>: two endpoints gating one capability on two literals is the shape §228 records,
 * where both look correct and one is wrong.
 *
 * <p><strong>Reading is gated on project visibility alone</strong>, like the run list. A schedule
 * carries nothing a project member may not see — a name, a cadence, a scope and an owner — so a
 * second question here would refuse a reader a fact the run list already tells them.
 *
 * <h2>{@link #recomputeNextFire} is the only writer of {@code next_fire_at}</h2>
 *
 * <p>Create, update and every fire call it. That column is stored state derived from two others,
 * and the way stored derived state goes wrong is a second place that writes it — one of which
 * eventually disagrees with the parser about what an expression means. {@link CronSchedule} is the
 * matching rule one level down: one parser, called by the validator and by this.
 *
 * <p>The methods are package-private because {@code RunScheduleController} and
 * {@code RunScheduleFiringService} are their only callers and both are in this package —
 * {@code everyPublicBeanMethodIsExercisedInProduction}'s rubric, and {@code @Transactional} on a
 * package-private method works for a class-based proxy (the in-repo proof is
 * {@code tenancy.OutboxLedger.claim}).
 */
@Service
public class RunScheduleService {

    private static final String AUDIT_TARGET = "run_schedule";
    private static final String AUDIT_CREATED = "run_schedule.created";
    private static final String AUDIT_UPDATED = "run_schedule.updated";
    private static final String AUDIT_DELETED = "run_schedule.deleted";

    private final ProjectService projects;
    private final RunScheduleRepository schedules;
    private final TestRunRepository runs;
    private final ConfigurationRepository configurations;
    private final ExecutionPeople people;
    private final AuditEventRepository auditEvents;
    /** Reads and writes `scope_request` through {@link RunScopeDocument} — no second mapper. */
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    RunScheduleService(
            ProjectService projects,
            RunScheduleRepository schedules,
            TestRunRepository runs,
            ConfigurationRepository configurations,
            ExecutionPeople people,
            AuditEventRepository auditEvents,
            ObjectMapper json,
            Clock clock) {

        this.projects = projects;
        this.schedules = schedules;
        this.runs = runs;
        this.configurations = configurations;
        this.people = people;
        this.auditEvents = auditEvents;
        this.json = json;
        this.clock = clock;
    }

    /** {@code RunCreateService.Outcome}'s shapes, minus the two a schedule cannot produce. */
    public sealed interface Outcome {

        record NotFound() implements Outcome {
        }

        record Forbidden() implements Outcome {
        }

        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Read(RunScheduleView view) implements Outcome {
        }

        record Created(RunScheduleView view) implements Outcome {
        }

        record Updated(RunScheduleView view) implements Outcome {
        }

        record Deleted() implements Outcome {
        }
    }

    /** The project's schedules, by name. Names and run keys resolved once for the whole list. */
    @Transactional(readOnly = true)
    Optional<List<RunScheduleView>> list(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId,
                project -> viewsOf(project.getId(), schedules.findInProject(project.getId())));
    }

    @Transactional(readOnly = true)
    Outcome read(UUID userId, UUID projectId, UUID scheduleId) {
        return projects.readVisible(userId, projectId,
                        project -> schedules.findInProject(project.getId(), scheduleId)
                                .<Outcome>map(schedule -> new Outcome.Read(
                                        viewsOf(project.getId(), List.of(schedule)).getFirst()))
                                .orElseGet(Outcome.NotFound::new))
                .orElseGet(Outcome.NotFound::new);
    }

    @Transactional
    /**
     * Public, with the class and the two types below it, for the composition root's
     * {@code DemoExecutionSeeder}: a seeded schedule has to be one this service made, because the
     * cron expression, the zone and the first {@code next_fire_at} are computed here and a row
     * assembled elsewhere would be one the firing service never picks up.
     */
    public Outcome create(UUID userId, UUID projectId, RunScheduleWriteRequest request) {
        return gated(userId, projectId, project -> {
            RunScheduleEdit.Outcome validated = validate(project, request);
            if (validated instanceof RunScheduleEdit.Outcome.Invalid invalid) {
                return new Outcome.Invalid(invalid.errors());
            }
            RunScheduleEdit edit = ((RunScheduleEdit.Outcome.Valid) validated).edit();

            Instant now = clock.instant();
            RunSchedule created = RunSchedule.create(
                    project.getId(),
                    edit.name(),
                    edit.cronExpression(),
                    edit.zoneId(),
                    edit.scopeRequest(),
                    edit.configurationId(),
                    edit.assigneeId(),
                    edit.enabled(),
                    userId,
                    now);
            recomputeNextFire(created, now);
            schedules.saveAndFlush(created);

            auditEvents.save(AuditEvent.record(
                    project.getId(), userId, AUDIT_CREATED, AUDIT_TARGET,
                    List.of(created.getId()), cadence(created), now));

            return new Outcome.Created(viewsOf(project.getId(), List.of(created)).getFirst());
        });
    }

    /**
     * Replaces the schedule.
     *
     * <p>Total, not a patch — see {@code RunScheduleWriteRequest}. {@link #recomputeNextFire} runs
     * <strong>after</strong> the replace because the expression or the zone may have moved, and a
     * corrected cadence that took effect only at the old cadence's next slot is a correction that
     * looks ignored.
     */
    @Transactional
    Outcome update(
            UUID userId, UUID projectId, UUID scheduleId, RunScheduleWriteRequest request) {

        return gated(userId, projectId, project -> {
            Optional<RunSchedule> found = schedules.findInProject(project.getId(), scheduleId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            RunScheduleEdit.Outcome validated = validate(project, request);
            if (validated instanceof RunScheduleEdit.Outcome.Invalid invalid) {
                return new Outcome.Invalid(invalid.errors());
            }
            RunScheduleEdit edit = ((RunScheduleEdit.Outcome.Valid) validated).edit();

            RunSchedule schedule = found.get();
            Instant now = clock.instant();
            schedule.replace(
                    edit.name(),
                    edit.cronExpression(),
                    edit.zoneId(),
                    edit.scopeRequest(),
                    edit.configurationId(),
                    edit.assigneeId(),
                    edit.enabled(),
                    userId,
                    now);
            recomputeNextFire(schedule, now);
            schedules.saveAndFlush(schedule);

            auditEvents.save(AuditEvent.record(
                    project.getId(), userId, AUDIT_UPDATED, AUDIT_TARGET,
                    List.of(schedule.getId()), cadence(schedule), now));

            return new Outcome.Updated(viewsOf(project.getId(), List.of(schedule)).getFirst());
        });
    }

    /**
     * Deletes the standing request, and nothing else.
     *
     * <p>A real {@code DELETE} rather than a soft one: a soft delete exists to keep a record of
     * work and a schedule records none — the runs it made are ordinary runs and outlive it. The
     * audit row is what keeps the act.
     */
    @Transactional
    Outcome delete(UUID userId, UUID projectId, UUID scheduleId) {
        return gated(userId, projectId, project -> {
            Optional<RunSchedule> found = schedules.findInProject(project.getId(), scheduleId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            RunSchedule schedule = found.get();
            auditEvents.save(AuditEvent.record(
                    project.getId(), userId, AUDIT_DELETED, AUDIT_TARGET,
                    List.of(schedule.getId()), cadence(schedule), clock.instant()));
            schedules.delete(schedule);
            return new Outcome.Deleted();
        });
    }

    /**
     * Moves {@code next_fire_at} to the first occurrence strictly after {@code from} — the one
     * writer of that column.
     *
     * <p>Called from create, from update and from <strong>every branch of a fire, including every
     * failure</strong>. A schedule that fails once and does not advance is re-fired on every tick
     * for ever, which is the worst outcome available here: a broken schedule becoming a hot loop.
     *
     * <p>{@code from} is <strong>the moment of firing, never the missed slot</strong> — there is no
     * catch-up. A deployment down for a day must not produce a day of backdated regression runs on
     * restart: seven identical runs appearing at once, each materialised against today's case
     * versions, none of them meaning what its timestamp says. A regression pass that did not happen
     * on Tuesday cannot be made to have happened on Tuesday, and {@code last_fired_at} is the
     * honest record of the gap.
     *
     * <p>An expression with nothing left in it sets the column to <strong>null</strong>, which is a
     * real answer: the schedule will not fire again and the screen says so.
     */
    void recomputeNextFire(RunSchedule schedule, Instant from) {
        Instant next = CronSchedule
                .parse(schedule.getCronExpression(), schedule.getZoneId())
                .flatMap(cron -> cron.nextAfter(from))
                .orElse(null);
        schedule.applyNextFire(next);
    }

    private RunScheduleEdit.Outcome validate(Project project, RunScheduleWriteRequest request) {
        UUID companyId = CompanyContext.require();

        // isActiveMember, NOT resolve: app_user is instance-level (ERD §0.1's exception list), so
        // resolve finds any account on the instance and would accept a user id from another client
        // entirely — see ExecutionPeople#isActiveMember, where the distinction cost a red test.
        boolean assigneeExists = request.assigneeId() != null
                && people.isActiveMember(companyId, request.assigneeId());

        // The project's own list, never a lookup by id: a configuration id from a sibling project
        // passes the foreign key, the company column and RLS all three, and would give every run
        // this schedule ever makes an environment from another product.
        boolean configurationOk = request.configurationId() != null
                && configurations.findByProjectIdOrderByIsDefaultDescNameAsc(project.getId())
                        .stream()
                        .anyMatch(candidate -> candidate.getId().equals(request.configurationId()));

        boolean anyArm = RunScheduleEdit.anyArm(request);
        String scopeRequest = anyArm
                ? RunScopeDocument.of(json, RunScheduleEdit.scopeOf(request))
                : null;

        return RunScheduleEdit.validate(
                request, scopeRequest, anyArm, assigneeExists, configurationOk);
    }

    private Outcome gated(UUID userId, UUID projectId, Function<Project, Outcome> write) {

        GateResult<Outcome> gated = projects.writeGranted(
                userId, projectId, RunCreateService.CREATE_RUN, write);

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * The audit row's {@code changes} document.
     *
     * <p>The cadence and the switch, because those are the two things a reader of the trail is
     * asking about — "when did this start firing at 03:00" and "who turned it off". The scope is
     * deliberately absent: it can be a hundred ids, and the schedule row itself carries it for
     * anyone who wants it.
     */
    private static String cadence(RunSchedule schedule) {
        return "{\"name\":" + AuditEvent.jsonString(schedule.getName())
                + ",\"cronExpression\":" + AuditEvent.jsonString(schedule.getCronExpression())
                + ",\"zoneId\":" + AuditEvent.jsonString(schedule.getZoneId())
                + ",\"enabled\":" + schedule.isEnabled()
                + "}";
    }

    /**
     * Views for a whole list — three lookups, never three per row.
     *
     * <p>{@code ExecutionPeople#resolve} for both people at once and
     * {@code TestRunRepository#findRunKeys} for every last run at once. That method already
     * excludes deleted runs, so a schedule whose last run was removed answers a null key beside a
     * non-null id, which is what tells a client not to offer a link to a screen that would 404.
     */
    private List<RunScheduleView> viewsOf(UUID projectId, List<RunSchedule> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        UUID companyId = CompanyContext.require();

        Set<UUID> userIds = new HashSet<>();
        Set<UUID> runIds = new HashSet<>();
        for (RunSchedule schedule : rows) {
            userIds.add(schedule.getCreatedBy());
            if (schedule.getAssigneeId() != null) {
                userIds.add(schedule.getAssigneeId());
            }
            if (schedule.getLastRunId() != null) {
                runIds.add(schedule.getLastRunId());
            }
        }
        Map<UUID, UserRef> named = people.resolve(companyId, userIds);
        Map<UUID, String> runKeys = new HashMap<>();
        if (!runIds.isEmpty()) {
            for (Object[] row : runs.findRunKeys(projectId, runIds)) {
                runKeys.put((UUID) row[0], (String) row[1]);
            }
        }

        List<RunScheduleView> views = new ArrayList<>(rows.size());
        for (RunSchedule schedule : rows) {
            views.add(new RunScheduleView(
                    schedule.getId(),
                    schedule.getProjectId(),
                    schedule.getName(),
                    schedule.getCronExpression(),
                    schedule.getZoneId(),
                    RunScopeRequestView.parse(json, schedule.getScopeRequest()),
                    schedule.getConfigurationId(),
                    schedule.getAssigneeId(),
                    displayName(named, schedule.getAssigneeId()),
                    schedule.isEnabled(),
                    schedule.getNextFireAt(),
                    schedule.getLastFiredAt(),
                    schedule.getLastRunId(),
                    schedule.getLastRunId() == null
                            ? null : runKeys.get(schedule.getLastRunId()),
                    schedule.getLastFireError(),
                    schedule.getCreatedAt(),
                    schedule.getCreatedBy(),
                    displayName(named, schedule.getCreatedBy())));
        }
        return views;
    }

    /**
     * A person's display name, or null.
     *
     * <p>A null is never evidence about the id beside it — an owner offboarded from this company
     * still resolves through {@code PeopleDirectory}'s fallback, and one whose account is gone
     * entirely does not. The id is the answer to "whose authority" either way.
     */
    private static String displayName(Map<UUID, UserRef> named, UUID userId) {
        UserRef found = userId == null ? null : named.get(userId);
        return found == null ? null : found.displayName();
    }
}
