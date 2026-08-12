package com.tcms.repository;

import com.tcms.identity.ProjectMembershipService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One operation over a selection of cases — <strong>preview, then commit</strong> (plan §6f).
 *
 * <h2>The gate depends on the argument, which is why it is not a constant</h2>
 *
 * <p>Every other write in this module names one permission at the top of the class.
 * {@code SET_STATUS} cannot: targeting {@code APPROVED} needs {@code case:approve}, which the roles
 * spec grants to Lead and up and <strong>explicitly not to Test Engineer</strong>, while everything
 * else here needs {@code case:edit}, which Test Engineer holds. Plan §6g is unambiguous — "Only
 * Admin and Test Lead can perform the final transition into APPROVED", and "the state machine has
 * exactly one gate, held by {@code *:approve}".
 *
 * <p>So the permission is resolved from the parsed command by {@link BulkOperations#permission}
 * <em>before</em> the gate is called. Gating this endpoint on {@code case:edit} — the obvious
 * reading, and the one every sibling write here uses — would have let a Test Engineer approve two
 * hundred cases in one click, silently, because they hold the permission the gate names. That is
 * §61's shape: not a fail-closed outage but a quiet widening, invisible at the line that grants it.
 *
 * <p>{@code case:approve} had <strong>no consumer in Java at all</strong> before this class. It has
 * been seeded by {@code V3} and granted correctly per role since M0, and nothing ever asked it.
 *
 * <h2>The state machine lives in {@link LifecycleLadder}, and the gate is per row</h2>
 *
 * <p>This class used to carry §6g's table itself, in one rule — {@code APPROVED} only from
 * {@code IN_REVIEW} — and to read the permission off the <strong>target</strong> alone. §75 moved
 * the table out, because {@link LifecycleStatus} is the status of a suite <em>or</em> a case and a
 * suite's ladder had to be the same one, and corrected the gate in the same move.
 *
 * <p><strong>The target was not enough, and the gap was live.</strong> Asking {@code case:approve}
 * only for a move <em>into</em> {@code APPROVED} left every move back <em>out</em> of it at
 * {@code case:edit} — so a Test Engineer could select two hundred approved cases, ask for
 * {@code DRAFT}, and rescind a lead's sign-off with the permission they use every day. The edge's
 * real key is {@link LifecycleLadder#permissionFor}, one end of the edge is the row's stored status,
 * and so the check is per row rather than at the gate. {@code PlanLifecycle} derived exactly this
 * rule for a plan in §73 and named this class as the weaker shape without coming back for it.
 *
 * <p>A row the ladder refuses, and a row whose edge costs more than the caller holds, are both
 * {@link BulkVerdict.Skipped} with a reason rather than a refusal of the whole operation — over a
 * selection of 44, three ineligible drafts must not stop the other 41, and a selection built by a
 * filter routinely mixes statuses.
 *
 * <h2>Preview and commit are the same code path</h2>
 *
 * <p>{@code dryRun} is consulted at exactly one place — the point of mutation — so a preview cannot
 * describe an operation the commit would not perform. The alternative, two methods that agree by
 * inspection, is how a preview starts promising something the commit does differently, and this
 * endpoint exists precisely because a mass edit without a trustworthy preview is how somebody
 * approves 240 cases at 5pm.
 *
 * <p><strong>What it does not do is link the two requests.</strong> The commit re-evaluates
 * everything from scratch, so a case edited between preview and commit gets the verdict its current
 * state earns, and the response says what actually happened rather than what was promised. A token
 * pinning the preview was rejected: it would refuse the whole operation because one case moved,
 * which is worse for the user than a per-case report they can read.
 *
 * <h2>One audit event, and two of the eight actions write versions</h2>
 *
 * <p>§6g asks for "preview, transactional, single audit event" and that is what this is: one
 * transaction, one {@code audit_event} row naming every case that changed. Per-case audit rows would
 * turn one deliberate act by one person into 500 entries that bury it.
 *
 * <p>Versions are the opposite — necessarily per case, and only for
 * {@link BulkCommand#changesAuthoredContent()}. See that method for why skipping them would
 * mis-attribute a bulk change to the next person who saves.
 */
@Service
public class CaseBulkService {

    private static final String AUDIT_TARGET = "test_case";

    private final ProjectService projects;
    private final ProjectMembershipService memberships;
    private final TestCaseRepository cases;
    private final SuiteRepository suites;
    private final ConditionRepository conditions;
    private final CaseConditionRepository caseConditions;
    private final CaseClassificationRepository caseClassifications;
    private final CaseTagRepository caseTags;
    private final TestStepRepository stepRows;
    private final CaseRenderer renderer;
    private final CaseVersions versions;
    private final ReviewService reviews;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public CaseBulkService(
            ProjectService projects,
            ProjectMembershipService memberships,
            TestCaseRepository cases,
            SuiteRepository suites,
            ConditionRepository conditions,
            CaseConditionRepository caseConditions,
            CaseClassificationRepository caseClassifications,
            CaseTagRepository caseTags,
            TestStepRepository stepRows,
            CaseRenderer renderer,
            CaseVersions versions,
            ReviewService reviews,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.memberships = memberships;
        this.cases = cases;
        this.suites = suites;
        this.conditions = conditions;
        this.caseConditions = caseConditions;
        this.caseClassifications = caseClassifications;
        this.caseTags = caseTags;
        this.stepRows = stepRows;
        this.renderer = renderer;
        this.versions = versions;
        this.reviews = reviews;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened, in the four shapes the endpoint can answer. */
    public sealed interface Outcome {

        /** Unreachable project — one bodiless 404, never a 403 (§6t). */
        record NotFound() implements Outcome {
        }

        /**
         * Readable, and the permission this operation needs is not held.
         *
         * @param detail the sentence to show. Carried rather than fixed because the approve refusal
         *               and the general one must differ — see {@link BulkOperations#refusal}.
         */
        record Forbidden(String detail) implements Outcome {
        }

        /** Field name to messages, for the dialog to land on its controls. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Done(BulkResultView result) implements Outcome {
        }
    }

    /**
     * Previews or applies a bulk operation.
     *
     * <p>Validation runs <strong>before</strong> the gate, which is the opposite of the order the
     * single-case writes use, and deliberately: the permission cannot be known until the payload is
     * parsed, because {@code SET_STATUS}'s permission is a function of its target. The information
     * this leaks to an ungranted caller is that their own request was well-formed, which is a fact
     * about the request they wrote and not about the project.
     */
    @Transactional
    public Outcome run(UUID userId, UUID projectId, BulkOperationRequest request) {
        UUID companyId = CompanyContext.require();

        BulkOperations.Outcome parsed = BulkOperations.parse(request);
        if (parsed instanceof BulkOperations.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        BulkOperations.Outcome.Valid valid = (BulkOperations.Outcome.Valid) parsed;

        // The second half of §6g's edge rule. The request-level gate below is the floor — it sees
        // only the target — so the key each row's actual edge costs is applied per row, and that
        // needs one more fact: may this caller approve at all. Resolved once, here, and only for
        // the command whose rows have edges; every other action's verdict does not read it.
        // Costs one evaluator call on a status change and nothing on the other seven actions.
        boolean approver = valid.command() instanceof BulkCommand.SetStatus
                && holds(userId, projectId, BulkOperations.APPROVE_CASE);

        GateResult<Outcome> gated = projects.writeGranted(
                userId,
                projectId,
                BulkOperations.permission(valid.command()),
                project -> apply(
                        companyId, userId, project, valid.command(), valid.caseIds(),
                        request.isPreview(), approver));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored ->
                    // The second permission question, asked ONLY on the refusal path and only to
                    // choose the wording: an engineer who lacks just case:approve and a Viewer who
                    // lacks everything need opposite advice. Costs one evaluator call on a request
                    // that is already failing, and nothing on the path that succeeds.
                    new Outcome.Forbidden(BulkOperations.refusal(valid.command(), holdsEdit(userId, projectId)));
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /** Whether this caller may edit cases here at all — see {@link BulkOperations#refusal}. */
    private boolean holdsEdit(UUID userId, UUID projectId) {
        return holds(userId, projectId, BulkOperations.EDIT_CASE);
    }

    /**
     * One project-scope permission question — {@link ProjectService#holds}, which this method was
     * the first and for a long time the only caller of.
     *
     * <p>It moved there in §82 when a second and a third caller appeared in {@code execution} for
     * §6g's gating override. Kept as a one-line delegation rather than inlined at the two sites
     * above, because {@code holdsEdit} reads as the question it is asking and
     * {@code projects.holds(userId, projectId, EDIT_CASE)} reads as plumbing.
     */
    private boolean holds(UUID userId, UUID projectId, String permission) {
        return projects.holds(userId, projectId, permission);
    }

    private Outcome apply(
            UUID companyId,
            UUID userId,
            Project project,
            BulkCommand command,
            List<UUID> caseIds,
            boolean dryRun,
            boolean approver) {

        // Argument validity is a property of the project, not of any one case, so it is checked once
        // rather than 500 times — and a bad argument is a 400 on the field, not 500 identical skips.
        Optional<Map<String, List<String>>> argumentErrors = checkArgument(project, command);
        if (argumentErrors.isPresent()) {
            return new Outcome.Invalid(argumentErrors.get());
        }

        List<TestCase> found = cases.findLiveInProjectByIds(project.getId(), caseIds);
        Map<UUID, TestCase> byId = new HashMap<>();
        for (TestCase row : found) {
            byId.put(row.getId(), row);
        }

        Context context = loadContext(userId, project, command, found);
        Instant now = clock.instant();

        List<String> changed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        List<BulkResultView.SkippedCase> skipped = new ArrayList<>();
        List<UUID> changedIds = new ArrayList<>();

        // The caller's order, over the REQUESTED ids rather than the found ones, so an id that
        // resolved to nothing still lands in a bucket. See BulkVerdict — the mock's loop dropped
        // those, and the user was shown an accounting of 42 for a selection of 44.
        for (UUID caseId : caseIds) {
            TestCase row = byId.get(caseId);
            if (row == null) {
                skipped.add(new BulkResultView.SkippedCase(
                        // No key to name it by: the row is not in this project, so its key is not
                        // ours to read even if the id resolves elsewhere in the company. The id is
                        // what the caller sent and can match against its own selection.
                        caseId.toString(),
                        "This case is no longer in this project."));
                continue;
            }

            BulkVerdict verdict = evaluate(row, command, context, approver);
            switch (verdict) {
                case BulkVerdict.Skipped skip ->
                        skipped.add(new BulkResultView.SkippedCase(row.getKey(), skip.reason()));
                case BulkVerdict.Unchanged ignored -> unchanged.add(row.getKey());
                case BulkVerdict.Changed ignored -> {
                    // The one place dryRun is consulted. Everything above ran identically.
                    if (!dryRun) {
                        mutate(companyId, userId, project.getId(), row, command, context, now);
                    }
                    changed.add(row.getKey());
                    changedIds.add(row.getId());
                }
            }
        }

        UUID auditEventId = null;
        if (!dryRun && !changedIds.isEmpty()) {
            auditEventId = auditEvents.save(AuditEvent.record(
                    project.getId(),
                    userId,
                    auditAction(command),
                    AUDIT_TARGET,
                    changedIds,
                    auditDetail(command, changedIds.size()),
                    now)).getId();
        }

        return new Outcome.Done(new BulkResultView(
                dryRun, caseIds.size(), List.copyOf(changed), List.copyOf(unchanged),
                List.copyOf(skipped), auditEventId));
    }

    // ------------------------------------------------------------------ argument ----

    /**
     * Whether the operation's argument is usable in this project at all.
     *
     * <p>Every check here answers 400 with a field name, because each names a value the caller chose
     * from a picker: a suite that is not in this project, a person who is not a member of it, a
     * condition from another project's library. Reporting these per case would produce 500 identical
     * skip reasons for one wrong dropdown, which buries the one thing the user has to change.
     */
    private Optional<Map<String, List<String>>> checkArgument(Project project, BulkCommand command) {
        return switch (command) {
            case BulkCommand.MoveToSuite move -> {
                boolean live = suites.findLiveForProject(project.getId()).stream()
                        .anyMatch(suite -> suite.getId().equals(move.suiteId()));
                yield live
                        ? Optional.empty()
                        : Optional.of(Map.of("suiteId", List.of("That suite is not in this project.")));
            }

            case BulkCommand.SetOwner owner -> {
                // Null clears the owner and needs no member check.
                if (owner.ownerId() == null) {
                    yield Optional.empty();
                }
                // §54's predicate, not a query over role_assignment: that finds only DIRECT grants,
                // so it silently omits everyone staffed through a team and every Company Admin, and
                // would refuse an assignment the single-case path allows. One predicate, one answer.
                yield memberships.isAssignableTo(owner.ownerId(), project.getId())
                        ? Optional.empty()
                        : Optional.of(Map.of("ownerId",
                                List.of("That person is not a member of this project.")));
            }

            case BulkCommand.AttachCondition attach ->
                    conditionInProject(project, attach.conditionId());

            case BulkCommand.DetachCondition detach ->
                    conditionInProject(project, detach.conditionId());

            case BulkCommand.SetStatus ignored -> Optional.empty();
            case BulkCommand.SetAutomationStatus ignored -> Optional.empty();
            case BulkCommand.AddTags ignored -> Optional.empty();
        };
    }

    private Optional<Map<String, List<String>>> conditionInProject(Project project, UUID conditionId) {
        return conditions.findLiveInProject(project.getId(), conditionId).isPresent()
                ? Optional.empty()
                : Optional.of(Map.of("conditionId",
                        List.of("That condition is not in this project's library.")));
    }

    // ------------------------------------------------------------------- verdict ----

    /**
     * What this command would do to this case.
     *
     * <p>Reads only — nothing here writes, so the preview and the commit reach the identical verdict
     * for the identical state.
     */
    private BulkVerdict evaluate(
            TestCase row, BulkCommand command, Context context, boolean approver) {
        return switch (command) {
            case BulkCommand.SetStatus status ->
                    evaluateStatus(row, status.target(), approver, context);

            case BulkCommand.SetAutomationStatus automation -> {
                if (row.getAutomationStatus() == automation.target()
                        && java.util.Objects.equals(
                                row.getNotAutomatableReason(), automation.notAutomatableReason())) {
                    yield new BulkVerdict.Unchanged();
                }
                // Only reached for a command that changes authored content, so a version row is
                // required — and cannot be written for a case whose stored content does not
                // validate. See CaseEdit.of.
                yield context.reconstructable(row.getId())
                        ? new BulkVerdict.Changed()
                        : new BulkVerdict.Skipped(NEEDS_A_SAVE);
            }

            case BulkCommand.SetOwner owner -> java.util.Objects.equals(row.getOwnerId(), owner.ownerId())
                    ? new BulkVerdict.Unchanged()
                    : new BulkVerdict.Changed();

            case BulkCommand.MoveToSuite move -> {
                if (row.getSuiteId().equals(move.suiteId())) {
                    yield new BulkVerdict.Unchanged();
                }
                yield context.reconstructable(row.getId())
                        ? new BulkVerdict.Changed()
                        : new BulkVerdict.Skipped(NEEDS_A_SAVE);
            }

            case BulkCommand.AddTags add -> context.tags()
                    .getOrDefault(row.getId(), Set.of())
                    .containsAll(add.tags())
                    ? new BulkVerdict.Unchanged()
                    : new BulkVerdict.Changed();

            case BulkCommand.AttachCondition attach -> context.conditionsOf(row.getId()).stream()
                    .anyMatch(existing -> attach.conditionId().equals(existing.getConditionId())
                            && existing.getKind() == attach.kind())
                    ? new BulkVerdict.Unchanged()
                    : new BulkVerdict.Changed();

            case BulkCommand.DetachCondition detach -> context.conditionsOf(row.getId()).stream()
                    .anyMatch(existing -> detach.conditionId().equals(existing.getConditionId()))
                    ? new BulkVerdict.Changed()
                    : new BulkVerdict.Unchanged();
        };
    }

    /**
     * Plan §6g's state machine, per row — <strong>both halves of it, since §75.</strong>
     *
     * <p>The table itself moved to {@link LifecycleLadder}, which a suite now reads from the same
     * class: {@link LifecycleStatus} is the status of a suite <em>or</em> a case and §6g draws one
     * diagram, so two tables would be a hand-maintained copy of a neighbour (§58's E1). Every wording
     * here is the ladder's for the same reason the old one was pinned to the mock's — the same
     * failure must not read as two different problems depending on which screen reached it (§52).
     *
     * <h2>The permission half, and why it is per row</h2>
     *
     * <p>{@code BulkOperations.permission} gated the request on the <em>target</em>, so
     * {@code case:approve} was asked only for a move <em>into</em> {@code APPROVED}. Moves back
     * <em>out</em> of it cost {@code case:edit}, which every Test Engineer holds — so selecting two
     * hundred approved cases and asking for {@code DRAFT} rescinded a lead's sign-off silently. The
     * edge's real key is {@link LifecycleLadder#permissionFor}, and one end of the edge is this
     * row's stored status, so the check belongs here and not at the gate.
     *
     * <p>A row whose edge costs more than the caller holds is <strong>skipped with a reason</strong>,
     * not answered 403: the selection is a filter's output and may hold rows of several statuses, so
     * refusing the whole operation would refuse the rows the caller may in fact move. That is this
     * class's standing choice for every other partial failure.
     *
     * @param approver whether the caller holds {@code case:approve} on this project — resolved once
     *                 per operation rather than per row, and only for the command that has edges
     */
    private BulkVerdict evaluateStatus(
            TestCase row, LifecycleStatus target, boolean approver, Context context) {
        LifecycleStatus current = row.getLifecycleStatus();
        LifecycleLadder.Decision decision = LifecycleLadder.decide(current, target, "case");

        // Before the permission half, unlike a status endpoint, and the difference is what the
        // caller is asking for: a row already in the requested state is not a move, so refusing it
        // would report a permission problem about a change nobody is making — and a selection of
        // four hundred cases routinely contains some that are already there.
        if (decision instanceof LifecycleLadder.Decision.Unchanged) {
            return new BulkVerdict.Unchanged();
        }

        if (!approver && BulkOperations.APPROVE_CASE.equals(
                LifecycleLadder.permissionFor(current, target, BulkOperations.CASE_KEYS))) {
            return new BulkVerdict.Skipped(leadOnly(current, target));
        }

        // §6g's approval preconditions, AFTER the permission half and before the ladder's verdict is
        // turned into a change — the order the two status endpoints use. A caller who may not
        // approve at all is told that, rather than being told they are the author of a review they
        // could not have signed off either way.
        String refusal = context.reviewRefusals().get(row.getId());
        if (refusal != null) {
            return new BulkVerdict.Skipped(refusal);
        }

        return switch (decision) {
            // Answered above. Written out because the switch is exhaustive over a sealed type, so
            // this arm is what makes a new Decision a compile error here rather than a silent one.
            case LifecycleLadder.Decision.Unchanged ignored -> new BulkVerdict.Unchanged();
            case LifecycleLadder.Decision.Refused refused ->
                    new BulkVerdict.Skipped(refused.message());
            case LifecycleLadder.Decision.Move ignored -> new BulkVerdict.Changed();
        };
    }

    /**
     * Said to a caller holding {@code case:edit} about a row whose edge costs {@code case:approve}.
     *
     * <p>Worded by the <em>act</em> rather than by the key, for {@link BulkOperations#refusal}'s
     * reason: a Test Engineer changes cases every day, so "you cannot change cases" is simply false
     * and sends them to ask for a permission they already hold. The approve arm repeats that
     * method's sentence exactly — one refusal, one wording, whether it arrives as a 403 over the
     * whole request or as a skip on one row.
     */
    private static String leadOnly(LifecycleStatus current, LifecycleStatus target) {
        return switch (target) {
            case APPROVED -> "Only a test lead can approve cases. Submit them for review instead.";
            case DEPRECATED -> "Only a test lead can deprecate cases.";
            // The unmaking arm. `current` is APPROVED or DEPRECATED here, or permissionFor would
            // not have asked for the approve key — so the sentence names the state the row is in
            // rather than the one it was asked to reach, which is the fact the caller cannot see
            // from a selection of four hundred.
            case DRAFT, IN_REVIEW -> "Only a test lead can change a case that is already "
                    + current.label().toLowerCase(Locale.ROOT) + ".";
        };
    }

    /** Said to a case whose own stored content will not validate — see {@link CaseEdit#of}. */
    private static final String NEEDS_A_SAVE =
            "Open this case and save it once — it has content that must be completed first.";

    // -------------------------------------------------------------------- mutate ----

    /** Applies the change, and writes the version row when the change is authored content. */
    private void mutate(
            UUID companyId,
            UUID userId,
            UUID projectId,
            TestCase row,
            BulkCommand command,
            Context context,
            Instant now) {

        switch (command) {
            case BulkCommand.SetStatus status -> {
                row.moveToStatus(status.target(), userId, now);
                // Per case, inside the one transaction the whole operation runs in — so submitting
                // two hundred cases opens two hundred reviews, and approving them closes exactly
                // the ones that were open. This is the product's whole approval path for a case
                // (§62: CaseUpdateRequest omits the status deliberately), so a review record that
                // skipped it would exist for suites and plans and not for the artifact §6g calls
                // the common case. The version pinned is the case's current one, which is what
                // V1's own column comment asks for: an approval applies to a SPECIFIC version,
                // including its snapshotted conditions.
                reviews.moved(
                        projectId,
                        ReviewSubjectType.CASE,
                        row.getId(),
                        row.getCurrentVersionNo(),
                        LifecycleLadder.standingOf(status.target()),
                        userId,
                        now);
            }

            case BulkCommand.SetAutomationStatus automation -> row.applyAutomationStatus(
                    automation.target(), automation.notAutomatableReason(), userId, now);

            case BulkCommand.SetOwner owner -> row.assignOwner(owner.ownerId(), userId, now);

            case BulkCommand.MoveToSuite move -> row.moveToSuite(move.suiteId(), userId, now);

            case BulkCommand.AddTags add -> {
                Set<String> existing = context.tags().getOrDefault(row.getId(), Set.of());
                for (String tag : add.tags()) {
                    if (!existing.contains(tag)) {
                        caseTags.save(CaseTag.of(row.getId(), tag, companyId));
                    }
                }
                // The case row itself does not change, so updatedAt is deliberately NOT stamped: a
                // tag is not authored content and moving the timestamp would make every "edited
                // recently" list a list of who ran a bulk tag.
            }

            case BulkCommand.AttachCondition attach -> {
                int next = context.conditionsOf(row.getId()).stream()
                        .filter(existing -> existing.getKind() == attach.kind())
                        .mapToInt(CaseCondition::getPosition)
                        .max()
                        .orElse(-1) + 1;
                caseConditions.save(CaseCondition.of(
                        row.getId(), attach.conditionId(), attach.kind(), next, companyId));
            }

            case BulkCommand.DetachCondition detach -> caseConditions.deleteAll(
                    context.conditionsOf(row.getId()).stream()
                            .filter(existing -> detach.conditionId().equals(existing.getConditionId()))
                            .toList());
        }

        if (command.changesAuthoredContent()) {
            writeVersion(companyId, userId, row, context, now);
        }
    }

    /**
     * Records the change as an authored version, exactly as an ordinary save would.
     *
     * <p>Called only for the two commands whose fields {@link CaseVersions#hash} digests. Skipping
     * it would leave the next single-case save computing a hash that no longer matches the newest
     * stored version, and <strong>that</strong> save would write a version attributing this bulk
     * change to whoever saved next.
     *
     * <p>{@code decide} is asked after the row has been mutated, so the hash covers the new content
     * — the same order {@link CaseUpdateService} uses, and the reason the version number is stamped
     * before the snapshot is rendered.
     */
    private void writeVersion(
            UUID companyId, UUID userId, TestCase row, Context context, Instant now) {

        // Reconstructed BEFORE the mutation, in loadContext, and re-run here against the row as it
        // now stands. The prior edit is what proves the case was reconstructable at all; the hash
        // must cover the NEW content, so the second call is not redundant.
        CaseEdit edit = CaseEdit.of(
                        row,
                        stepRows.findForCase(row.getId()),
                        context.liveSuiteIds(),
                        context.classificationOf(row.getId()),
                        context.conditionIdsOf(row.getId(), ConditionKind.PRE),
                        context.conditionIdsOf(row.getId(), ConditionKind.POST),
                        key -> cases.existsByAutomationKeyOtherThan(key, row.getId()))
                // evaluate() already refused every case loadContext could not reconstruct, and the
                // only fields this class changes are ones validate accepts by construction — a
                // live suite it just checked, an automation status with its reason. So reaching
                // here means those two disagreed, which is a bug and not a user error.
                .orElseThrow(() -> new IllegalStateException(
                        "A case passed the reconstructable check and then failed to reconstruct: "
                                + row.getId()));

        CaseVersions.Decision decision = versions.decide(row, edit);
        row.advanceVersion(decision.versionNo());
        cases.saveAndFlush(row);

        versions.write(
                row,
                edit,
                decision,
                renderer.render(
                        row, context.liveSuites(), context.vocabularies(), companyId,
                        context.flakes(), context.attachments(), context.docLinks()),
                userId,
                now);
    }

    // --------------------------------------------------------------------- audit ----

    private String auditAction(BulkCommand command) {
        return switch (command) {
            case BulkCommand.SetStatus status ->
                    status.target() == LifecycleStatus.APPROVED
                            ? "case.bulk.approved"
                            : "case.bulk.status_changed";
            case BulkCommand.SetAutomationStatus ignored -> "case.bulk.automation_status_changed";
            case BulkCommand.SetOwner ignored -> "case.bulk.owner_changed";
            case BulkCommand.MoveToSuite ignored -> "case.bulk.moved";
            case BulkCommand.AddTags ignored -> "case.bulk.tagged";
            case BulkCommand.AttachCondition ignored -> "case.bulk.condition_attached";
            case BulkCommand.DetachCondition ignored -> "case.bulk.condition_detached";
        };
    }

    /**
     * The argument and the size, as JSON.
     *
     * <p>Approval gets its own action name above rather than being an argument here, because "who
     * approved what" is the question an auditor actually asks (§6g: "proving to an auditor that
     * someone competent signed off") and it should be answerable by filtering the action column
     * rather than by parsing detail out of every status change.
     */
    private String auditDetail(BulkCommand command, int count) {
        String argument = switch (command) {
            case BulkCommand.SetStatus status -> "\"lifecycleStatus\":\"" + status.target() + "\"";
            case BulkCommand.SetAutomationStatus automation ->
                    "\"automationStatus\":\"" + automation.target() + "\"";
            case BulkCommand.SetOwner owner -> "\"ownerId\":"
                    + (owner.ownerId() == null ? "null" : "\"" + owner.ownerId() + "\"");
            case BulkCommand.MoveToSuite move -> "\"suiteId\":\"" + move.suiteId() + "\"";
            case BulkCommand.AddTags add -> "\"tagCount\":" + add.tags().size();
            case BulkCommand.AttachCondition attach ->
                    "\"conditionId\":\"" + attach.conditionId() + "\",\"kind\":\"" + attach.kind() + "\"";
            case BulkCommand.DetachCondition detach ->
                    "\"conditionId\":\"" + detach.conditionId() + "\"";
        };
        return "{\"changed\":" + count + "," + argument + "}";
    }

    // ------------------------------------------------------------------- context ----

    /**
     * Everything the loop needs that does not vary per case, read once.
     *
     * <p>Without this the evaluation loop is an N+1 in four directions at a selection size of 500 —
     * the project's suites, its vocabularies, each case's tags and each case's conditions. Loading
     * conditionally on the command keeps a status change from paying for the condition query it
     * never reads.
     *
     * @param priorEdits the authored content each case had <em>before</em> the operation, for the
     *                   cases whose stored content validates. Empty for every command that does not
     *                   change authored content — building it reads every case's steps, which is
     *                   work no status change or tag has any use for. A case absent from this map
     *                   when the command needs it is skipped rather than changed: see
     *                   {@link CaseEdit#of}.
     */
    private record Context(
            List<Suite> liveSuites,
            Set<UUID> liveSuiteIds,
            List<ClassificationDimensionView> vocabularies,
            Map<UUID, Set<String>> tags,
            Map<UUID, List<CaseCondition>> conditions,
            Map<UUID, Set<UUID>> classifications,
            Map<UUID, CaseEdit> priorEdits,
            /**
             * Why §6g's policy refuses this case's approval, per case — empty for every case that
             * may proceed, and empty entirely for any command that is not a move into
             * {@code APPROVED}. See {@code ReviewService#refusesEach}.
             */
            Map<UUID, String> reviewRefusals,
            /**
             * The batch's flake rows, resolved once — see {@link CaseRenderer#flakesFor}.
             *
             * <p>Here rather than looked up in {@link #writeVersion} because that method runs once
             * per case: resolving there would be 500 queries to render a field no bulk action
             * changes. Empty for a command that writes no version, since nothing would read it.
             */
            Map<UUID, CaseFlakeView> flakes,
            /**
             * The batch's attached files, resolved once — {@link CaseRenderer#attachmentsFor}, and
             * here for {@link #flakes}' reason exactly. No bulk action attaches or removes a file;
             * the snapshot renders them because the snapshot is the whole response.
             */
            Map<UUID, List<CaseAttachmentView>> attachments,
            /**
             * The batch's documentation links, resolved once — {@link CaseRenderer#docLinksFor},
             * and here for {@link #flakes}' reason exactly (§167). No bulk action creates or removes
             * a link; the snapshot renders them because the snapshot is the whole response, and a v7
             * that recorded a case's traceability as empty because this map was not carried would be
             * describing a version that never existed.
             */
            Map<UUID, List<DocLinkView>> docLinks) {

        List<CaseCondition> conditionsOf(UUID caseId) {
            return conditions.getOrDefault(caseId, List.of());
        }

        /**
         * What the case is classified as — carried into the reconstructed edit so its hash
         * describes the whole case.
         *
         * <p>No bulk action changes classification, and that is exactly why this has to be here: an
         * edit reconstructed without it hashes as though the case had none, so the version this
         * class writes would differ from the one the next ordinary save computes for identical
         * content, and that save would be credited with restoring a classification nobody touched.
         * The same trap {@link #writeVersion}'s header describes for {@code suiteId}, on a field
         * this class never reads.
         */
        Set<UUID> classificationOf(UUID caseId) {
            return classifications.getOrDefault(caseId, Set.of());
        }

        /**
         * The case's own attached condition ids, in the author's order, one list per kind.
         *
         * <p>Carried into the reconstructed edit for {@link #classificationOf}'s reason exactly: no
         * bulk action changes a case's conditions, so an edit reconstructed without them hashes as
         * though the case had none, and the next ordinary save would be credited with restoring
         * attachments nobody touched.
         *
         * <p><strong>Reference rows only.</strong> {@code findForCases} deliberately includes inline
         * attachments — it feeds a decision about positions — while {@link CaseEdit} carries library
         * references, so the filter belongs here. Including an inline row would put a null into the
         * id list and hash a case as attaching "nothing" in a position.
         *
         * <p>The rows arrive ordered by {@code (caseId, kind, position)}, so this is a filter and
         * not a sort.
         */
        List<UUID> conditionIdsOf(UUID caseId, ConditionKind kind) {
            return conditionIds(conditionsOf(caseId), kind);
        }

        /** Whether a version row can be written for this case at all. */
        boolean reconstructable(UUID caseId) {
            return priorEdits.containsKey(caseId);
        }
    }

    /**
     * The reference ids of one kind, in stored order — the projection both
     * {@link Context#conditionIdsOf} and {@code loadContext} need, written once.
     *
     * <p>Static and null-tolerant because the two callers reach it from different sides: the record
     * has a defaulted map, this one has a raw {@code get} that is legitimately absent for a case
     * with no attachments.
     */
    private static List<UUID> conditionIds(List<CaseCondition> rows, ConditionKind kind) {
        if (rows == null) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (CaseCondition row : rows) {
            if (row.getKind() == kind && row.getConditionId() != null) {
                ids.add(row.getConditionId());
            }
        }
        return ids;
    }

    private Context loadContext(
            UUID userId, Project project, BulkCommand command, List<TestCase> found) {

        boolean authored = command.changesAuthoredContent();
        List<Suite> liveSuites =
                authored ? suites.findLiveForProject(project.getId()) : List.of();
        Set<UUID> liveSuiteIds = new HashSet<>();
        for (Suite suite : liveSuites) {
            liveSuiteIds.add(suite.getId());
        }

        Map<UUID, Set<String>> tags = new HashMap<>();
        if (command instanceof BulkCommand.AddTags && !found.isEmpty()) {
            for (CaseTag tag : caseTags.findForCases(found.stream().map(TestCase::getId).toList())) {
                tags.computeIfAbsent(tag.getCaseId(), key -> new HashSet<>()).add(tag.getTag());
            }
        }

        // Loaded for the two condition commands, which act on these rows — AND for every AUTHORED
        // command, which does not. The second half is §95's addition and it is the whole point:
        // CaseEdit now carries the case's attachments, so an edit reconstructed without them hashes
        // as though the case had none, and the next ordinary save would be credited with restoring
        // attachments a MOVE_TO_SUITE never touched. Exactly the trap `classificationOf` describes,
        // on a second field, and it would have been introduced by leaving this line alone.
        Map<UUID, List<CaseCondition>> byCase = new LinkedHashMap<>();
        if ((command instanceof BulkCommand.AttachCondition
                || command instanceof BulkCommand.DetachCondition
                || authored) && !found.isEmpty()) {
            for (CaseCondition attachment
                    : caseConditions.findForCases(found.stream().map(TestCase::getId).toList())) {
                byCase.computeIfAbsent(attachment.getCaseId(), key -> new ArrayList<>())
                        .add(attachment);
            }
        }

        // Reconstructed once, here, and read twice — by the verdict (may this case be changed at
        // all) and by the version writer. Doing it in both places would read every case's steps
        // twice per operation, and would let the two disagree.
        List<ClassificationDimensionView> vocabularies =
                authored ? renderer.vocabularies(userId, project.getId()) : List.of();

        // Resolved for the whole batch before the loop, like every other per-case lookup here: one
        // pass over the assignments rather than a query per case, and filtered through the same
        // vocabulary the renderer uses so a retired value is excluded here exactly as it is
        // everywhere else. Empty when the command changes nothing authored, because then no
        // version is written and nothing reads it.
        Map<UUID, Set<UUID>> classificationsByCase = new HashMap<>();
        if (authored && !found.isEmpty()) {
            Set<UUID> offered = ClassificationVocabulary.of(vocabularies).liveValueIds();
            for (CaseClassification assignment
                    : caseClassifications.findForCases(found.stream().map(TestCase::getId).toList())) {
                if (offered.contains(assignment.getValueId())) {
                    classificationsByCase
                            .computeIfAbsent(assignment.getCaseId(), key -> new HashSet<>())
                            .add(assignment.getValueId());
                }
            }
        }

        Map<UUID, CaseEdit> priorEdits = new HashMap<>();
        if (authored) {
            for (TestCase row : found) {
                CaseEdit.of(
                                row,
                                stepRows.findForCase(row.getId()),
                                liveSuiteIds,
                                classificationsByCase.getOrDefault(row.getId(), Set.of()),
                                conditionIds(byCase.get(row.getId()), ConditionKind.PRE),
                                conditionIds(byCase.get(row.getId()), ConditionKind.POST),
                                key -> cases.existsByAutomationKeyOtherThan(key, row.getId()))
                        .ifPresent(edit -> priorEdits.put(row.getId(), edit));
            }
        }

        // §6g's approval preconditions, resolved for the whole selection in two queries before the
        // loop — ReviewService#refusesEach exists for exactly this call site, because asking per row
        // is two queries per case over a selection of four hundred. Only a move INTO approval can be
        // refused by policy, so nothing is loaded for the other seven actions or for the six other
        // status targets.
        Map<UUID, String> reviewRefusals =
                command instanceof BulkCommand.SetStatus status
                        && status.target() == LifecycleStatus.APPROVED
                        && !found.isEmpty()
                        ? reviews.refusesEach(
                                project.getId(),
                                ReviewSubjectType.CASE,
                                found.stream().map(TestCase::getId).toList(),
                                userId)
                        : Map.of();

        return new Context(
                liveSuites,
                liveSuiteIds,
                vocabularies,
                tags,
                byCase,
                classificationsByCase,
                Map.copyOf(priorEdits),
                reviewRefusals,
                // Only for the authored commands — they are the ones that write a version snapshot,
                // and the snapshot is the only thing in this class that renders a case.
                authored && !found.isEmpty()
                        ? renderer.flakesFor(CompanyContext.require(), found)
                        : Map.of(),
                authored && !found.isEmpty()
                        ? renderer.attachmentsFor(CompanyContext.require(), found)
                        : Map.of(),
                authored && !found.isEmpty()
                        ? renderer.docLinksFor(found)
                        : Map.of());
    }
}
