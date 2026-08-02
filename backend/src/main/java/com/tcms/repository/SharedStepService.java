package com.tcms.repository;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One project's reusable step library (plan §6f, §6h) — the read, the write, and the membership
 * question a case save asks of it.
 *
 * <h2>The gate is borrowed, not restated</h2>
 *
 * <p>The read goes through {@link ProjectService#readVisible}, so a caller passes exactly the two
 * gates {@code GET /projects/{projectId}} applies — company first, then the explicit project grant.
 * The write goes through {@link ProjectService#writeGranted} with {@code library:manage}. Nothing
 * in this class decides whether a caller may see a project, which is the rule the whole module
 * follows and the one a permission check appearing here would break.
 *
 * <p>{@link ConditionService} states why the read is behind the project grant rather than merely
 * behind the company, and the argument transfers with more force: a step library reads "open the
 * legacy admin console", "restore the merchant balance", "submit the refund form" — it is a
 * description of how the client's system is operated, assembled by the people who operate it.
 *
 * <h2>{@code library:manage} gets its first consumer in Java here</h2>
 *
 * <p>It has been seeded by {@code V3} and granted to Test Engineer and above since M0, and nothing
 * has ever asked for it — the shape §62 found with {@code case:approve}, where the permission is
 * correct in the catalogue and unreachable in the product. The roles spec's note that library work
 * is <em>"gated by an impact modal, not by permission"</em> is about editing an entry, whose change
 * propagates to every case referencing it; a create propagates nowhere, so the permission is the
 * whole control on this path.
 */
@Service
public class SharedStepService {

    /** Roles spec, Repository table. Test Engineer and above; not Viewer, not Automation Service. */
    private static final String MANAGE_LIBRARY = "library:manage";

    private static final String AUDIT_TARGET = "shared_step";

    private final ProjectService projects;
    private final SharedStepRepository sharedSteps;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public SharedStepService(
            ProjectService projects,
            SharedStepRepository sharedSteps,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.sharedSteps = sharedSteps;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code library:manage} is not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A new library entry. 201. */
        record Created(SharedStepView view) implements Outcome {
        }
    }

    /**
     * The project's matching live entries, most-used first, or empty when the caller may not see
     * the project.
     *
     * <p>An empty {@link Optional} and a present-but-empty list are different answers and the
     * controller renders them differently: 404 for "you may not see this project", {@code []} for
     * "you may, and nothing matched". {@link ConditionService#findLibrary} states the cost of
     * collapsing them — a typeahead narrowed past the data becomes a 404 the user cannot back out
     * of.
     */
    @Transactional(readOnly = true)
    public Optional<List<SharedStepView>> findLibrary(
            UUID userId, UUID projectId, SharedStepQuery request) {

        return projects.readVisible(userId, projectId, project ->
                rank(sharedSteps.findLibrary(project.getId(), request.needle())));
    }

    /**
     * Ranks the library by usage, resolving the counts in one query for the whole page.
     *
     * <p>Two queries rather than one correlated subquery, and never one query per row — the shape
     * that turns a typeahead into N+1 against the largest table in the schema.
     *
     * <p>The comparator is the ordering §6h specifies and the one {@link ConditionRepository} spells
     * in SQL: most-used first, because the point of a library is to surface the block four hundred
     * cases already share rather than the one written yesterday. Title and id break the ties, so
     * the same keystroke returns the same list.
     */
    private List<SharedStepView> rank(List<SharedStep> live) {
        if (live.isEmpty()) {
            // Also the guard countUsage documents: an empty IN () is a syntax error, not an empty
            // result, so the early return is load-bearing rather than an optimization.
            return List.of();
        }

        List<UUID> ids = new ArrayList<>(live.size());
        for (SharedStep step : live) {
            ids.add(step.getId());
        }

        Map<UUID, Long> usage = new HashMap<>();
        for (SharedStepUsage counted : sharedSteps.countUsage(ids)) {
            usage.put(counted.sharedStepId(), counted.cases());
        }

        List<SharedStepView> views = new ArrayList<>(live.size());
        for (SharedStep step : live) {
            // Absent means nothing references it — the state of every entry the moment it is
            // created. countUsage cannot emit a zero row; defaulting here is where that is handled.
            views.add(SharedStepView.of(step, usage.getOrDefault(step.getId(), 0L)));
        }

        views.sort(Comparator.comparingInt(SharedStepView::usageCount).reversed()
                .thenComparing(SharedStepView::title)
                .thenComparing(view -> view.id().toString()));
        return views;
    }

    /**
     * Creates a library entry.
     *
     * <p>No {@code Idempotency-Key}, on {@link SuiteWriteService#create}'s terms: a duplicate entry
     * is visible in the picker next to its twin the moment the create returns. The header earns its
     * column on {@code test_case}, where a duplicate is two rows with different keys and identical
     * titles among hundreds.
     *
     * <p>No uniqueness check on the title either, and that is a decision rather than an omission.
     * Two entries may legitimately be called "Sign in" and differ in their actions — one for the
     * admin console, one for the customer app — and the schema carries no unique index to enforce
     * a rule against it. Refusing the second would make the library's own vocabulary the thing an
     * author has to work around.
     */
    @Transactional
    public Outcome create(UUID userId, UUID projectId, SharedStepWriteRequest request) {
        UUID companyId = CompanyContext.require();

        GateResult<Outcome> result =
                projects.writeGranted(userId, projectId, MANAGE_LIBRARY, project -> {
                    SharedStepEdit.Outcome validated = SharedStepEdit.validateCreate(request);
                    if (validated instanceof SharedStepEdit.Outcome.Invalid invalid) {
                        return new Outcome.Invalid(invalid.errors());
                    }
                    SharedStepEdit edit = ((SharedStepEdit.Outcome.Valid) validated).edit();

                    Instant now = clock.instant();
                    SharedStep created =
                            SharedStep.create(companyId, project.getId(), edit, userId, now);
                    sharedSteps.saveAndFlush(created);

                    audit(project.getId(), userId, created, now);

                    // Zero by construction: nothing can reference an entry created in this
                    // transaction. Passed as a literal rather than by re-running the count, which
                    // would be a query whose answer is known.
                    return new Outcome.Created(SharedStepView.of(created, 0L));
                });

        return switch (result) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * Which of the submitted ids a case in this project may reference.
     *
     * <p>Called by the case create and update paths, which pass the result into
     * {@link CaseEdit#validate}. It answers only the <em>library</em> half of that rule — the ids
     * live in this project — and never the second half, which is whether the case already
     * references the id. That one is the caller's, because only the caller has the stored steps,
     * and folding it in here would mean this method silently approved an id no longer in the
     * library depending on which case asked.
     *
     * <p>Not gated, deliberately: the caller has already passed the project's write gate before it
     * gets here, and a second gate on a helper is a second place for the two to disagree.
     *
     * @param submitted the ids the payload references. Empty in the common case — most cases
     *                  reference no library entry at all — and then this makes no query, which is
     *                  also what keeps {@code IN ()} from ever being bound
     */
    @Transactional(readOnly = true)
    public Set<UUID> liveLibraryIds(UUID projectId, Set<UUID> submitted) {
        if (submitted.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(sharedSteps.findLiveIdsInProject(projectId, submitted));
    }

    private void audit(UUID projectId, UUID userId, SharedStep step, Instant now) {
        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                "shared_step.created",
                AUDIT_TARGET,
                List.of(step.getId()),
                // The title, for SuiteWriteService#audit's reason: it is what an audit reader
                // recognises the entry by, and the one thing the id does not tell them. The escape
                // lives on AuditEvent so there is one escaper rather than one per caller.
                "{\"title\":" + AuditEvent.jsonString(step.getTitle()) + "}",
                now));
    }
}
