package com.tcms.repository;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import jakarta.persistence.OptimisticLockException;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One project's reusable condition library (plan §6f, §6h) — the read, and since §95 the writers.
 *
 * <h2>The gate is borrowed, not restated</h2>
 *
 * <p>The read goes through {@link ProjectService#readVisible}, so a caller passes exactly the two
 * gates {@code GET /projects/{projectId}} applies — company first, then the explicit project
 * grant. The writes go through {@link ProjectService#writeGranted} with {@code library:manage}.
 * Nothing in this class decides whether a caller may see a project, which is the rule the whole
 * module follows and the one a permission check appearing here would break.
 *
 * <p>The failure that gate prevents is quiet rather than dramatic: an endpoint checking only that
 * the project is in the caller's company passes every cross-company test in the suite — the rows
 * genuinely are in the caller's company — and hands a member granted nothing a list of the
 * client's setup procedures. A condition library reads "seed a captured payment of 120.00 EUR",
 * "open the legacy admin console", "restore the merchant balance"; it is a description of how the
 * client's system is operated, which is why it is behind the grant and not merely behind the
 * company.
 *
 * <h2>The edit is the propagation — there is nothing to fan out</h2>
 *
 * <p>{@code case_condition.condition_id} and {@code suite_condition.condition_id} are references,
 * so every attachment resolves through the row {@link #update} writes. That is the object's whole
 * purpose and the deliberate opposite of {@code /step-suggestions}, where picking a suggestion
 * copies text and creates no link. Two things follow, and both are decisions rather than
 * omissions:
 *
 * <ul>
 *   <li><strong>No fork on edit.</strong> Offering "change everywhere / copy for this case" is what
 *       turned {@code shared_step} into a library of near-duplicates nobody could rank. An author
 *       who wants different text writes a different condition.</li>
 *   <li><strong>The impact warning is the client's, and it is computed from data served here.</strong>
 *       By the time {@link #update} runs the decision has been made, so a confirmation round-trip
 *       on this endpoint would be a second place for the rule to live. What this class owes the
 *       client is honest numbers — see {@link #rank}.</li>
 * </ul>
 */
@Service
public class ConditionService {

    /** Roles spec, Repository table. Test Engineer and above; not Viewer, not Automation Service. */
    private static final String MANAGE_LIBRARY = "library:manage";

    private static final String AUDIT_TARGET = "condition";

    private final ProjectService projects;
    private final ConditionRepository conditions;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public ConditionService(
            ProjectService projects,
            ConditionRepository conditions,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.conditions = conditions;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping is exhaustive at compile time. */
    public sealed interface Outcome {

        /**
         * Unreachable project, or — on the edit — an id that is not a live entry of it. One
         * bodiless 404 (§6t), so this endpoint is not an existence oracle for a library the caller
         * cannot read.
         */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code library:manage} is not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A stale {@code If-Match}. Somebody else edited the entry since the client read it. */
        record Conflict() implements Outcome {
        }

        /** A new library entry. 201. */
        record Created(ConditionView view) implements Outcome {
        }

        /** An edited entry. 200. */
        record Updated(ConditionView view) implements Outcome {
        }
    }

    /**
     * The project's matching live conditions, or empty when the caller may not see the project.
     *
     * <p>An empty {@link Optional} and a present-but-empty list are different answers and the
     * controller renders them differently: 404 for "you may not see this project", {@code []} for
     * "you may, and nothing matched". Collapsing them would turn both a freshly provisioned
     * project — whose library is empty, since {@code tcms_seed_project_defaults} seeds statuses
     * and a configuration and deliberately not a client's setup procedures — and a typeahead that
     * has narrowed past the data into a 404 the user cannot back out of.
     */
    @Transactional(readOnly = true)
    public Optional<List<ConditionView>> findLibrary(
            UUID userId, UUID projectId, ConditionQuery request) {

        return projects.readVisible(userId, projectId, project ->
                rank(conditions.findLibrary(project.getId(), request.types(), request.needle())));
    }

    /**
     * Resolves both usage counts for a page of entries and sorts by the case count.
     *
     * <p>Two count queries rather than one. Three
     * queries for the whole page rather than one per row — the shape that turns a typeahead into
     * N+1 against the largest table in the schema — and never a correlated subquery.
     *
     * <p><strong>Most-used first is by the CASE count, not by the sum.</strong> The two numbers are
     * about different things ({@link ConditionView} says why they must not be added), so a blended
     * sort key would be an ordering by a quantity that does not exist. The case count is the one an
     * author is choosing between entries on.
     */
    private List<ConditionView> rank(List<Condition> live) {
        if (live.isEmpty()) {
            // Also the guard both count queries document: an empty IN () is a syntax error, not an
            // empty result, so the early return is load-bearing rather than an optimization.
            return List.of();
        }

        List<UUID> ids = new ArrayList<>(live.size());
        for (Condition condition : live) {
            ids.add(condition.getId());
        }

        Map<UUID, Long> cases = tally(conditions.countCaseUsage(ids));
        Map<UUID, Long> suites = tally(conditions.countSuiteUsage(ids));

        List<ConditionView> views = new ArrayList<>(live.size());
        for (Condition condition : live) {
            // Absent means nothing attaches it — the state of every entry the moment it is created.
            // A GROUP BY cannot emit a zero row; defaulting here is where that is handled.
            views.add(ConditionView.of(
                    condition,
                    cases.getOrDefault(condition.getId(), 0L),
                    suites.getOrDefault(condition.getId(), 0L)));
        }

        views.sort(Comparator.comparingInt(ConditionView::usageCount).reversed()
                .thenComparing(ConditionView::title)
                .thenComparing(view -> view.id().toString()));
        return views;
    }

    private static Map<UUID, Long> tally(List<ConditionUsage> counted) {
        Map<UUID, Long> byId = new HashMap<>();
        for (ConditionUsage usage : counted) {
            byId.put(usage.conditionId(), usage.count());
        }
        return byId;
    }

    /**
     * Creates a library entry. {@code library:manage}.
     *
     * <p>No {@code Idempotency-Key} and no uniqueness check on the title, both on
     * these terms: a duplicate is visible in the picker next to its
     * twin the moment the create returns, and two entries may legitimately be called "Signed in"
     * and differ in their actions.
     */
    @Transactional
    public Outcome create(UUID userId, UUID projectId, ConditionWriteRequest request) {
        UUID companyId = CompanyContext.require();

        GateResult<Outcome> result =
                projects.writeGranted(userId, projectId, MANAGE_LIBRARY, project -> {
                    ConditionEdit.Outcome validated = ConditionEdit.validate(request);
                    if (validated instanceof ConditionEdit.Outcome.Invalid invalid) {
                        return new Outcome.Invalid(invalid.errors());
                    }
                    ConditionEdit edit = ((ConditionEdit.Outcome.Valid) validated).edit();

                    Instant now = clock.instant();
                    Condition created =
                            Condition.create(companyId, project.getId(), edit, userId, now);
                    conditions.saveAndFlush(created);

                    audit(project.getId(), userId, created, "condition.created", now);

                    // Zero by construction on both counts: nothing can attach an entry created in
                    // this transaction. Passed as literals rather than by re-running the queries,
                    // whose answers are known.
                    return new Outcome.Created(ConditionView.of(created, 0L, 0L));
                });

        return collapse(result);
    }

    /**
     * Creates a library entry the {@code .feature} import owns — {@link #create} plus an
     * {@code imported_key}.
     *
     * <p><strong>Package-private, and it takes no gate.</strong> Both are deliberate. The import
     * decides per ROW whether the caller may write ({@code api-surface.md} §5.4: a caller without
     * {@code library:manage} gets a {@code REFUSE} row naming the key, not a 403 for the request),
     * so the permission question is answered before this is reached and answering it twice would
     * turn a refusal the plan can report into an exception it cannot. {@link FeatureImportService}
     * is the only caller and it is in this package; a public overload would be a second door onto
     * the library that skips {@code library:manage}.
     *
     * <p>Everything else is {@link #create}'s: the same validation, the same audit verb, the same
     * view. A condition written here is an ordinary condition — the key is provenance, not a kind.
     *
     * @return the created row, so the caller can attach it by id in the same transaction
     */
    Condition createImported(
            UUID userId, UUID projectId, ConditionWriteRequest request, String importedKey) {

        UUID companyId = CompanyContext.require();
        ConditionEdit.Outcome validated = ConditionEdit.validate(request);
        if (validated instanceof ConditionEdit.Outcome.Invalid invalid) {
            // Unreachable by construction — the import truncates the title to ConditionEdit's own
            // bound and a Background step always has a body — and thrown rather than swallowed,
            // because a validation failure here means the truncation rule and the validator have
            // drifted apart, which is a defect and not a row to report.
            throw new IllegalStateException(
                    "an imported Background step failed condition validation: " + invalid.errors());
        }
        ConditionEdit edit = ((ConditionEdit.Outcome.Valid) validated).edit();

        Instant now = clock.instant();
        Condition created =
                Condition.createImported(companyId, projectId, edit, importedKey, userId, now);
        conditions.saveAndFlush(created);

        audit(projectId, userId, created, "condition.created", now);
        return created;
    }

    /**
     * Re-titles an import-owned entry whose step text was truncated differently than before.
     *
     * <p>Package-private and gateless for {@link #createImported}'s reason, and narrower than it
     * looks: the {@code imported_key} hashes the step's BODY, so the body of an entry the import
     * owns can never have changed. The only thing that can move is the title — which is the
     * truncation of the step text, and the budget is a constant somebody may one day raise. Without
     * this, a raised budget would leave every existing entry showing the old cut for ever, because
     * the key would still match and the plan would still say {@code UNCHANGED}.
     *
     * <p>Goes through {@link Condition#applyEdit}, so the propagation, the audit row and the
     * {@code updated_by} stamp are the ordinary ones. Silently does nothing if the row has been
     * retired since the plan read it: an import must not resurrect a condition a human retired.
     */
    void retitleImported(UUID userId, UUID projectId, UUID conditionId, String title) {
        conditions.findLiveInProject(projectId, conditionId).ifPresent(condition -> {
            Instant now = clock.instant();
            condition.applyEdit(
                    new ConditionEdit(title, condition.getType(), condition.getBody()),
                    userId,
                    now);
            conditions.saveAndFlush(condition);
            audit(projectId, userId, condition, "condition.updated", now);
        });
    }

    /**
     * Edits a library entry, propagating to everything that attaches it. {@code library:manage}.
     *
     * <h2>The order of the checks is the same one §72 fixed and §74 restated</h2>
     *
     * <p>Permission, then existence, then the {@code If-Match}, then validation. Each step earns its
     * position: a caller without {@code library:manage} gets 403 rather than being told whether the
     * id exists; a stale precondition is reported before the body is judged, because re-submitting
     * a corrected body against a version somebody else has moved on from would fail again for a
     * reason the first message did not mention.
     *
     * <h2>The retired entry answers 404, not 409</h2>
     *
     * <p>{@link ConditionRepository#findLiveInProject} filters {@code deletedAt IS NULL} and carries
     * the {@code projectId} predicate, so a retired entry, a sibling project's entry and a
     * nonexistent id are one answer. Resolving with {@code findById} instead would be RLS-scoped to
     * the <em>company</em> and would accept a sibling project's condition — §64/§68/§69's family,
     * and here it would let a caller edit a library they hold no grant on.
     *
     * @param expectedVersion the {@code If-Match} value, or null to skip the check — a last-write-wins
     *                        save, correct for a caller with no prior read and wrong for an editor,
     *                        which is why the SPA always sends it
     */
    @Transactional
    public Outcome update(
            UUID userId,
            UUID projectId,
            UUID conditionId,
            Long expectedVersion,
            ConditionWriteRequest request) {

        GateResult<Outcome> result =
                projects.writeGranted(userId, projectId, MANAGE_LIBRARY, project -> {
                    Optional<Condition> found =
                            conditions.findLiveInProject(project.getId(), conditionId);
                    if (found.isEmpty()) {
                        return new Outcome.NotFound();
                    }
                    Condition condition = found.get();

                    // Checked here as well as by @Version, and the two are not redundant. This one
                    // reports the stale read the client can actually act on ("reload and retry")
                    // before anything is written; @Version is the backstop for the interleaving
                    // this check cannot see, where a concurrent commit lands between the read above
                    // and the flush below.
                    if (expectedVersion != null && condition.getLockVersion() != expectedVersion) {
                        return new Outcome.Conflict();
                    }

                    ConditionEdit.Outcome validated = ConditionEdit.validate(request);
                    if (validated instanceof ConditionEdit.Outcome.Invalid invalid) {
                        return new Outcome.Invalid(invalid.errors());
                    }
                    ConditionEdit edit = ((ConditionEdit.Outcome.Valid) validated).edit();

                    Instant now = clock.instant();
                    condition.applyEdit(edit, userId, now);

                    try {
                        conditions.saveAndFlush(condition);
                    } catch (OptimisticLockException | OptimisticLockingFailureException stale) {
                        // The backstop firing. Same answer as the explicit check above, because the
                        // caller's remedy is identical and a second 409 wording would only tell
                        // them which of two internal paths noticed.
                        return new Outcome.Conflict();
                    }

                    audit(project.getId(), userId, condition, "condition.updated", now);

                    // Re-counted rather than assumed: the client renders these as the impact of the
                    // NEXT edit, and an entry that gained attachments since it was read would
                    // otherwise report the figures the caller already had.
                    return new Outcome.Updated(ConditionView.of(
                            condition,
                            single(conditions.countCaseUsage(List.of(condition.getId()))),
                            single(conditions.countSuiteUsage(List.of(condition.getId())))));
                });

        return collapse(result);
    }

    /**
     * Which of the submitted ids a case in this project may attach.
     *
     * <p>It answers only the <em>library</em>
     * half of the rule — the ids are live entries of this project — never the second half, which is
     * whether the case already attaches the id. That one is the caller's, because only the caller
     * has the stored attachments, and folding it in here would mean this method silently approved
     * an id no longer in the library depending on which case asked.
     *
     * <p>Not gated, deliberately: the caller has already passed the project's write gate before it
     * gets here, and a second gate on a helper is a second place for the two to disagree.
     *
     * @param submitted the ids the payload references. Empty in the common case — most cases attach
     *                  no condition at all — and then this makes no query, which is also what keeps
     *                  {@code IN ()} from ever being bound
     */
    @Transactional(readOnly = true)
    public Set<UUID> liveLibraryIds(UUID projectId, Set<UUID> submitted) {
        if (submitted.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(conditions.findLiveIdsInProject(projectId, submitted));
    }

    /** Zero when the entry is attached by nothing — a GROUP BY emits no row for it. */
    private static long single(List<ConditionUsage> counted) {
        return counted.isEmpty() ? 0L : counted.getFirst().count();
    }

    private Outcome collapse(GateResult<Outcome> result) {
        return switch (result) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private void audit(UUID projectId, UUID userId, Condition condition, String action, Instant now) {
        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action,
                AUDIT_TARGET,
                List.of(condition.getId()),
                // The title, for SuiteWriteService#audit's reason: it is what an audit reader
                // recognises the entry by, and the one thing the id does not tell them. The escape
                // lives on AuditEvent so there is one escaper rather than one per caller.
                "{\"title\":" + AuditEvent.jsonString(condition.getTitle()) + "}",
                now));
    }
}
