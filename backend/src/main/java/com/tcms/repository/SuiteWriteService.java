package com.tcms.repository;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.project.ReviewService;
import com.tcms.project.ReviewSubjectType;
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
 * Creating, renaming and deleting suites — <strong>the writes that make a provisioned company
 * usable.</strong>
 *
 * <p>Until this class existed, {@code provision-company} produced a company whose projects had no
 * folders and could therefore hold no cases: {@code tcms_seed_project_defaults} seeds a
 * configuration and the status vocabulary, deliberately not a folder, and a case requires a
 * {@code suiteId}. The §29 live pass had to insert a suite with hand-written SQL to test the case
 * endpoint it was building.
 *
 * <h2>Two permissions, and collapsing them would erase a boundary the spec called out</h2>
 *
 * <p>{@code suite:manage} (create, rename) is held by Test Engineer and above; {@code suite:delete}
 * by leads only. The roles spec states the reason in one line: deleting is the most destructive
 * repository action, so it sits with leads while contributors keep organising freely. It also lists that split as a boundary that was <em>previously wrong</em> and got fixed —
 * so gating both on {@code suite:manage} would not be a simplification, it would be the reversion.
 *
 * <h2>What this class refuses, and why refusing is the feature</h2>
 *
 * <ol>
 *   <li><strong>Deleting a suite that still holds FILED cases.</strong> The cascade is
 *       deliberately not built here: a delete that soft-deletes the cases in a suite unmakes
 *       <em>other people's</em> work through a permission whose sibling, {@code case:delete},
 *       carries an {@code OWN} qualifier precisely to prevent that — and with no preview of what it
 *       is about to touch. A non-empty suite answers 409 naming the count. The cascade belongs to a
 *       slice that can show its work; the contract already has the shape for it in
 *       {@code BulkResult}.
 *       <p><strong>Only the filing arm blocks (§98), and the other two deliberately do not.</strong>
 *       A case merely <em>added</em> here, or <em>matched</em> by a tag rule, lives elsewhere and
 *       only appears in this collection — deleting it loses a claim on the case and never the case.
 *       Filing is different in kind: {@code test_case.suite_id} is where the case <em>is</em>.</li>
 *   <li><strong>Moving</strong> — until §98, where the thing to move to stopped existing. A payload
 *       whose {@code parentId} differed from the stored one was <em>refused rather than ignored</em>,
 *       because silently dropping a field is what lets a client show a suite in its new place,
 *       reload, and find it back, with the server having reported success. Worth keeping in mind
 *       the next time a field looks droppable.</li>
 * </ol>
 *
 * <h2>What commits together</h2>
 *
 * <p>On a create: the counter increment, the suite row, its v1 composition version and the audit
 * row, in one transaction. The counter is in it for {@link DisplayKeys}' reason — its row lock must
 * be held until the row that used the number commits.
 *
 * <h2>Conditions ride on both writes (§186), and that is why they exist at all</h2>
 *
 * <p>{@code suite_condition} had no writer anywhere in the product until this class got one. It was
 * not made its own endpoint because a suite's conditions are the suite's content in the way its tag
 * rules are — an author sets them while naming the collection, and a separate {@code PUT
 * .../conditions} would be a second write whose {@code If-Match} argues about a different version
 * than the one the form was rendered from. {@link SuiteConditions#reconcile} does the work and its
 * header records the one way it differs from the case-side reconcile it is otherwise a copy of.
 */
@Service
public class SuiteWriteService {

    /** Roles spec §6b. Create, rename, move and reorder — the last two are not built. */
    private static final String MANAGE_SUITE = "suite:manage";

    /** Roles spec §6b. Leads and above; deliberately not held by Test Engineer. */
    private static final String DELETE_SUITE = "suite:delete";

    private static final String AUDIT_TARGET = "suite";

    /**
     * The longest {@code Idempotency-Key} this endpoint will store — {@code CaseCreateService}'s
     * cap, and the <strong>seventh copy of this constant</strong> in the codebase.
     *
     * <p>The copies are deliberate and {@code IdempotencyKeyCapTest} is what keeps them honest. The
     * only module all seven writers may import is {@code tenancy}, and an HTTP header's length cap
     * is not a tenancy concept — §125 set the bar for what may go into a foundation module and
     * refused {@code shared} as a grab bag on exactly these grounds. So the invariant is enforced
     * by a computed check rather than by a shared symbol, per the working agreement's rule that a
     * catalogue-wide invariant needs a check and not a careful reading.
     */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    private final ProjectService projects;
    private final SuiteRepository suites;
    private final SuiteService tree;
    private final SuiteVersions versions;
    private final SuiteMembershipWriter membership;
    private final SuiteConditions conditions;
    private final ConditionService conditionLibrary;
    private final TestCaseRepository cases;
    private final DisplayKeys displayKeys;
    private final ReviewService reviews;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public SuiteWriteService(
            ProjectService projects,
            SuiteRepository suites,
            SuiteService tree,
            SuiteVersions versions,
            SuiteMembershipWriter membership,
            SuiteConditions conditions,
            ConditionService conditionLibrary,
            TestCaseRepository cases,
            DisplayKeys displayKeys,
            ReviewService reviews,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.suites = suites;
        this.tree = tree;
        this.versions = versions;
        this.membership = membership;
        this.conditions = conditions;
        this.conditionLibrary = conditionLibrary;
        this.cases = cases;
        this.displayKeys = displayKeys;
        this.reviews = reviews;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so every controller mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, or a suite that is not in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and the permission the action needs is not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The client's {@code If-Match} did not match the stored {@code lockVersion}. */
        record Stale() implements Outcome {
        }

        /**
         * The delete was refused because the suite still holds filed cases.
         *
         * <p>Carries the count rather than a rendered sentence: the message is the controller's,
         * and a service that returns prose is a service two callers eventually word differently.
         *
         * <p>It carried a {@code childSuites} count too until §98 — the other half of "empty".
         */
        record NotEmpty(long cases) implements Outcome {
        }

        /** A new suite. 201. */
        record Created(SuiteView view) implements Outcome {
        }

        /**
         * A retry of a create that already succeeded. 200 rather than 201, so a client can tell "I
         * made this" from "I already had this" — and so a well-behaved retry is not reported as a
         * second creation in anybody's logs. {@code CaseCreateService.Outcome.Replayed}'s terms.
         */
        record Replayed(SuiteView view) implements Outcome {
        }

        /** A renamed suite. 200. */
        record Updated(SuiteView view) implements Outcome {
        }

        /** Soft-deleted. 204. */
        record Deleted() implements Outcome {
        }
    }

    /**
     * Creates a suite.
     *
     * <p>~~No {@code Idempotency-Key}, unlike case creation, and the difference is not an omission.
     * A duplicate case is expensive to spot — the grid shows two rows with different keys and
     * identical titles among hundreds. A duplicate folder is visible in the tree the moment the
     * create returns, next to its twin, and is removed by the delete this same slice ships. The
     * header is worth its column on {@code test_case} and would be ceremony here.~~ →
     * <strong>superseded by {@code V42}</strong>, whose header is the long form.
     *
     * <p>The old reason was not wrong; its <em>premise</em> stopped being universal. It reasons
     * entirely from a person looking at a screen — "visible", "next to its twin" — and §135's tool
     * surface adds a caller with no screen, for whom a timeout followed by a retry has no
     * correction loop at all. §98 removed the tree it names, besides: a duplicate in a flat list of
     * two hundred collections is not visible the moment anything returns.
     *
     * @param idempotencyKey the request's {@code Idempotency-Key} header, or null when absent
     */
    @Transactional
    public Outcome create(
            UUID userId, UUID projectId, SuiteWriteRequest request, String idempotencyKey) {
        UUID companyId = CompanyContext.require();

        return gated(projects.writeGranted(userId, projectId, MANAGE_SUITE,
                project -> applyCreate(companyId, userId, project, request, idempotencyKey)));
    }

    private Outcome applyCreate(
            UUID companyId,
            UUID userId,
            Project project,
            SuiteWriteRequest request,
            String idempotencyKey) {

        // FIRST, before validating anything — CaseCreateService's ordering and its reason: a retry
        // of a create that succeeded must answer with the suite it made even if the payload has
        // since drifted, because the client is asking "did my intent land" and a 400 would send it
        // to fix fields on a suite that exists.
        if (idempotencyKey != null) {
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
                return new Outcome.Invalid(Map.of("Idempotency-Key",
                        List.of("Send a key of at most " + MAX_IDEMPOTENCY_KEY + " characters.")));
            }
            Optional<Suite> already = suites.findByIdempotencyKey(project.getId(), idempotencyKey);
            if (already.isPresent()) {
                return new Outcome.Replayed(view(companyId, project.getId(), already.get()));
            }
        }

        // The project's live suites are no longer read here: they existed only to resolve and
        // validate the parent (§98). A create now touches no other suite at all.
        //
        // The stored halves of the condition union are List.of() rather than a read: the suite does
        // not exist yet, so it attaches nothing, and an absent list on a create therefore resolves
        // to empty. Passing a read here would be a query against an id no row carries.
        SuiteEdit.Outcome validated = SuiteEdit.validate(
                request,
                liveCaseIds(project.getId(), request),
                conditionLibrary.liveLibraryIds(
                        project.getId(), SuiteEdit.referencedConditionIds(request)),
                List.of(),
                List.of());
        if (validated instanceof SuiteEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        SuiteEdit edit = ((SuiteEdit.Outcome.Valid) validated).edit();

        // AFTER validation, so a rejected payload does not burn a number — §29's rule, and the
        // counter only ever moves forward, so a burned number is a permanent hole.
        DisplayKeys.Allocation allocated = displayKeys.allocate(DisplayKeys.EntityType.SUITE, 1);

        Instant now = clock.instant();
        Suite created = Suite.create(
                project.getId(),
                allocated.keyAt(0),
                allocated.seqAt(0),
                edit,
                userId,
                idempotencyKey,
                now);

        // Flushed before the version row so the suite exists for suite_version.suite_id to
        // reference, and so a constraint this class did not validate — the unique key — fails at its
        // cause rather than underneath the version written on top of it.
        suites.saveAndFlush(created);
        // After the flush, so suite_case.suite_id has a row to reference.
        membership.reconcile(
                created, edit.tagRules(), edit.caseIds(), companyId, userId, now);
        // Likewise: suite_condition.suite_id references the row that was just flushed.
        conditions.reconcile(
                created, edit.preconditionIds(), edit.postconditionIds(), companyId);
        versions.writeInitial(created, userId, now);

        audit(project.getId(), userId, "suite.created", created, now);

        return new Outcome.Created(view(companyId, project.getId(), created));
    }

    /**
     * Renames a suite, and edits its description.
     *
     * @param expectedVersion the {@code If-Match} the client sent, or null for last-write-wins
     */
    @Transactional
    public Outcome rename(
            UUID userId,
            UUID projectId,
            UUID suiteId,
            SuiteWriteRequest request,
            Long expectedVersion) {

        UUID companyId = CompanyContext.require();

        return gated(projects.writeGranted(userId, projectId, MANAGE_SUITE, project -> {
            Optional<Suite> found = suites.findLiveInProject(project.getId(), suiteId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            Suite suite = found.get();

            // Before validation: a client holding a stale copy should be told to reload rather than
            // told its name is wrong, because the name it is arguing about is not the current one.
            if (expectedVersion != null && expectedVersion != suite.getLockVersion()) {
                return new Outcome.Stale();
            }

            // The union CaseUpdateService assembles for a case, on the other entity that attaches
            // conditions: this project's live library entries for the ids the payload NAMES, plus
            // whatever the suite already attaches. Only the named ids are looked up, so the
            // ordinary rename — which names none — costs no library query.
            Set<UUID> acceptableConditionIds = new HashSet<>(conditionLibrary.liveLibraryIds(
                    project.getId(), SuiteEdit.referencedConditionIds(request)));
            List<UUID> storedPre =
                    conditions.liveAttachedIdsOf(suite.getId(), ConditionKind.PRE);
            List<UUID> storedPost =
                    conditions.liveAttachedIdsOf(suite.getId(), ConditionKind.POST);
            acceptableConditionIds.addAll(storedPre);
            acceptableConditionIds.addAll(storedPost);

            SuiteEdit.Outcome validated = SuiteEdit.validate(
                    request,
                    liveCaseIds(project.getId(), request),
                    acceptableConditionIds,
                    storedPre,
                    storedPost);
            if (validated instanceof SuiteEdit.Outcome.Invalid invalid) {
                return new Outcome.Invalid(invalid.errors());
            }
            SuiteEdit edit = ((SuiteEdit.Outcome.Valid) validated).edit();

            Instant now = clock.instant();

            // §6g's edit-after-approval rule, RE_REVIEW arm — see EditAfterApproval#RE_REVIEW for
            // why the demotion is not gated on suite:approve.
            //
            // SCOPE, stated rather than left to be discovered: this covers the suite's OWN edit, the
            // rename. It deliberately does not fire when a case is moved into or out of an approved
            // folder, which also changes what §6g says a suite review is about — its composition.
            // Doing that properly means every case write knowing which suites it just invalidated,
            // and the demotion arriving as a side effect of editing a different artifact; it is a
            // slice of its own and is named in the handoff rather than half-built here.
            boolean unApproves = suite.getLifecycleStatus() == LifecycleStatus.APPROVED
                    && reviews.reReviewOnEdit(project.getId(), ReviewSubjectType.SUITE);

            suite.applyEdit(edit, userId, now);
            if (unApproves) {
                suite.applyStatus(LifecycleStatus.DRAFT, userId, now);
            }
            suites.saveAndFlush(suite);
            // Membership is not versioned here, and that is worth stating: suite_version holds
            // composition, so a membership change is exactly what a v2 would be for — and nothing
            // writes one today (SuiteVersions writes v1 and nothing since, which CaseFacts'
            // suiteVersionMarkers already records as a property of the writer). Changing that is
            // the composition-versioning slice, not a line here.
            membership.reconcile(
                    suite, edit.tagRules(), edit.caseIds(), companyId, userId, now);
            conditions.reconcile(
                    suite, edit.preconditionIds(), edit.postconditionIds(), companyId);

            // No version row: a name is content, and suite_version holds composition. See
            // SuiteVersions for why bumping here would start the every-edit-bumps-every-ancestor
            // spiral V1's own schema comment exists to refuse.
            audit(project.getId(), userId, "suite.renamed", suite, now);
            if (unApproves) {
                // Its own verb, for CaseUpdateService's reason: an approval that ended is what
                // somebody comes looking for, and "suite.renamed" is not where they would look.
                audit(project.getId(), userId, "suite.re_review_required", suite, now);
            }

            return new Outcome.Updated(view(companyId, project.getId(), suite));
        }));
    }

    /**
     * Soft-deletes an empty suite.
     *
     * <p>The emptiness check is two queries and both are needed: a folder can be empty of cases while
     * holding child folders, and empty of child folders while holding cases. Answering 204 to either
     * would remove a node the tree still renders children under.
     */
    @Transactional
    public Outcome delete(UUID userId, UUID projectId, UUID suiteId) {
        UUID companyId = CompanyContext.require();

        return gated(projects.writeGranted(userId, projectId, DELETE_SUITE, project -> {
            Optional<Suite> found = suites.findLiveInProject(project.getId(), suiteId);
            if (found.isEmpty()) {
                // Includes the already-deleted case, which answers 404 rather than a second 204:
                // findLiveInProject excludes soft-deleted rows, and reporting success for a delete
                // this request did not perform is the same untruth as a 201 on a replayed create.
                return new Outcome.NotFound();
            }
            Suite suite = found.get();

            // FILED cases only — see the class header. The child-suite half of this check went
            // with the tree, and the case half narrowed from the subtree to this suite's own
            // filing arm in the same slice.
            long cases = suites.countLiveCasesFiledIn(suite.getId());
            if (cases > 0) {
                return new Outcome.NotEmpty(cases);
            }

            Instant now = clock.instant();
            suite.softDelete(userId, now);
            suites.saveAndFlush(suite);

            audit(project.getId(), userId, "suite.deleted", suite, now);

            return new Outcome.Deleted();
        }));
    }

    /**
     * Collapses the gate's three states onto this class's own.
     *
     * <p>One place rather than three identical switches, so a fourth gate state cannot be handled in
     * two of the three writes — the failure {@link GateResult}'s sealing exists to make visible and
     * this method makes impossible.
     */
    private Outcome gated(GateResult<Outcome> result) {
        return switch (result) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * The suite as the API serves it — read back through {@link SuiteService}'s renderer rather than
     * assembled here.
     *
     * <p>Not a second implementation of a suite's shape, for {@link CaseCreateService#render}'s
     * reason: this response is the first thing anyone sees of a folder, so a field rendered
     * differently would show one value on create and another on the reload that follows, with neither
     * screen revealing which is stored.
     *
     * <p>The body moved onto {@link SuiteService#viewOf} when {@link SuiteStatusService} needed the
     * same read: a second private copy is how {@code CaseRenderer}'s fourth copy happened.
     */
    /**
     * The project's live case ids — but only when the request actually names some.
     *
     * <p>Loaded lazily because the overwhelmingly common suite write is a rename with no
     * {@code caseIds} at all, and reading every case id of a project to validate a field nobody
     * sent is a full scan per rename. When the field IS present it must be checked against
     * something: a sibling project's case id satisfies {@code suite_case}'s foreign key, the
     * company column and RLS, so nothing else would refuse it (see {@link SuiteEdit}).
     */
    private Set<UUID> liveCaseIds(UUID projectId, SuiteWriteRequest request) {
        if (request.caseIds() == null || request.caseIds().isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(cases.liveIdsForProject(projectId));
    }

    private SuiteView view(UUID companyId, UUID projectId, Suite suite) {
        return tree.viewOf(companyId, projectId, suite);
    }

    private void audit(UUID projectId, UUID userId, String action, Suite suite, Instant now) {
        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action,
                AUDIT_TARGET,
                List.of(suite.getId()),
                // The key and the name, not the whole row. The key is what an audit reader
                // recognises the folder by and is the one thing the id does not tell them; the name
                // is here because a deleted suite's name is otherwise only recoverable by reading a
                // soft-deleted row, which is precisely what an audit trail should not require.
                // The escape lives on AuditEvent: the case delete needs the same one for a title,
                // and two copies of an escaper diverge on exactly the input neither was tested with.
                "{\"key\":\"" + suite.getKey() + "\",\"name\":"
                        + AuditEvent.jsonString(suite.getName()) + "}",
                now));
    }
}
