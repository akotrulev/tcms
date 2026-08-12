package com.tcms.project;

import com.tcms.api.TeamRef;
import com.tcms.api.UserRef;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reviewer apparatus — <strong>the last unbuilt half of plan §6g</strong>, and the first writer
 * {@code review} and {@code review_participation} have ever had.
 *
 * <p>Both tables have been in {@code V1} since the beginning with no entity and no native query.
 * Every item-side ladder now exists — a case's (§62), a plan's (§73), a suite's (§75) — so what was
 * missing is what §6g puts <em>inside</em> {@code IN_REVIEW}: who was asked, what they said, and who
 * eventually signed it off. That is also why {@code case:review}, {@code suite:review} and
 * {@code plan:review} have had no consumer in Java, which §73 and §75 both recorded as correct
 * rather than as an omission. They have one now.
 *
 * <h2>Why this lives in {@code project}</h2>
 *
 * <p>Three writers in two modules have to reach it: {@code SuiteStatusService} and
 * {@code CaseBulkService} in {@code repository}, {@code PlanStatusService} in {@code execution}.
 * Those two modules may not depend on each other, so the only homes a shared review record can have
 * are the three foundation modules everything may call — and of those, {@code project} is the one a
 * review <em>is</em> scoped to: {@code review.project_id} is {@code NOT NULL}, every gate on it is a
 * project-scope permission, and its every foreign key (company, project, {@code app_user}) already
 * points at exactly the tables plan §3 permits.
 *
 * <p>The alternative — a {@code review} module of its own — was considered and refused. It needs
 * plan §3's module list amended and {@code ModuleStructureTest}'s expected list with it, and then
 * still cannot be <em>called</em> by {@code repository} or {@code execution}, because a module
 * outside the foundation three may not be named in their {@code allowedDependencies}. The remaining
 * route is a domain event — the product's first — whose type could live in neither end and would
 * therefore land in {@code tenancy}, and which would have to be a synchronous in-transaction
 * listener anyway, because an approval whose review row was lost is precisely the audit hole this
 * record exists to close. That is a great deal of machinery in service of a diagram. §67 refused a
 * {@code storage} module on the same reasoning at smaller scale.
 *
 * <p>What this package pays for the placement is that it cannot see {@code identity}, so names are
 * resolved through {@link PeopleDirectory} — the port shape this package's own comment prescribes,
 * and {@link ProjectAccess}'s second instance.
 *
 * <h2>The ladder tells this class where the artifact landed, and nothing else</h2>
 *
 * <p>{@link #moved} takes a {@link ReviewStanding}, never a status: the three status enums live in
 * modules this one cannot import, and each ladder already owns the mapping. It deliberately does
 * <strong>not</strong> take where the artifact came <em>from</em>. The open review row is that fact,
 * and it is the better one — a subject with no open review has nothing to close, whatever its
 * previous status suggested, and two adjacent same-typed enum parameters are a swap the compiler
 * cannot see ({@code LifecycleLadder.Keys}' reason).
 */
@Service
public class ReviewService {

    private final ProjectService projects;
    private final ProjectSettingsService settings;
    private final ReviewRepository reviews;
    private final ReviewParticipationRepository participations;
    private final ReviewCommentRepository comments;
    private final ReviewAssignmentRepository assignments;
    private final PeopleDirectory people;
    private final TeamDirectory teams;
    /**
     * Asked about the <strong>assignee</strong>, never about the caller.
     *
     * <p>The caller's gate is {@link ProjectService}'s and stays there — this class holds the port
     * directly only because {@link ReviewAssigneeEdit} needs to know whether <em>somebody else</em>
     * can see the project, which no gate signature answers and which is a validation question rather
     * than an authorization one. Both roads lead to the same {@code PermissionEvaluator}, so this is
     * not the second authorization-resolution path {@link ProjectAccess}'s header refuses; it is the
     * same one asked about a different person.
     */
    private final ProjectAccess access;
    private final Clock clock;

    public ReviewService(
            ProjectService projects,
            ProjectSettingsService settings,
            ReviewRepository reviews,
            ReviewParticipationRepository participations,
            ReviewCommentRepository comments,
            ReviewAssignmentRepository assignments,
            PeopleDirectory people,
            TeamDirectory teams,
            ProjectAccess access,
            Clock clock) {

        this.projects = projects;
        this.settings = settings;
        this.reviews = reviews;
        this.participations = participations;
        this.comments = comments;
        this.assignments = assignments;
        this.people = people;
        this.teams = teams;
        this.access = access;
        this.clock = clock;
    }

    /** What a peer's verdict did. Sealed, so the controller's switch is exhaustive. */
    public sealed interface Outcome {

        /** Unreachable project, or a review id that names nothing in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code *:review} for this subject type is not held. */
        record Forbidden() implements Outcome {
        }

        /** Not a peer verdict. 400, on the field. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The review has already ended. 409 — nothing about the request is malformed. */
        record Closed(String message) implements Outcome {
        }

        /** Recorded. 201 with the review, so the caller re-renders the whole trail. */
        record Recorded(ReviewView view) implements Outcome {
        }
    }

    // ---------------------------------------------------------------- the ladders ----

    /**
     * Told by an artifact's status ladder where that artifact just landed.
     *
     * <p>Called from inside the ladder's own transaction, so the review row and the status change
     * commit together or not at all. An approval whose review row was rolled back separately would
     * leave the product asserting a sign-off with no record of who gave it, which is the failure
     * this table exists to prevent.
     *
     * <p>Three rules, and everything else writes nothing:
     *
     * <ul>
     *   <li><strong>Into {@code IN_REVIEW}</strong> — open a review pinned to the version submitted.
     *       Idempotent: an already-open review is left exactly as it is, because re-stamping its
     *       version would silently move a reviewer's attention onto content they never saw.</li>
     *   <li><strong>Out of {@code IN_REVIEW} to {@code APPROVED}</strong> — close it as approved and
     *       write the {@code APPROVAL} participation that names the signer. {@code V1}'s rule that
     *       only {@code APPROVAL} rows may transition the state, honoured from the one side that can
     *       honour it.</li>
     *   <li><strong>Out of {@code IN_REVIEW} to anything else</strong> — close it, as a withdrawal
     *       if the person taking it back is the person who submitted it and as a rejection
     *       otherwise. Derived from the two ids rather than sent, because a client that could
     *       nominate which one it was could record a rejection as a withdrawal.</li>
     * </ul>
     *
     * <p>A move between two states that are both outside review — a draft being deprecated, an
     * approved plan being started — writes nothing at all, and neither does an approval on an
     * artifact that has no open review. The second is not defensive: every artifact approved before
     * this slice existed has none, and inventing one retroactively would fabricate a review nobody
     * held.
     *
     * @param versionNo the subject's current version, read only when a review is opened
     * @param actorId   the authenticated caller, never a request field
     */
    @Transactional
    public void moved(
            UUID projectId,
            ReviewSubjectType subjectType,
            UUID subjectId,
            int versionNo,
            ReviewStanding to,
            UUID actorId,
            Instant at) {

        UUID companyId = CompanyContext.require();
        ReviewSettings policy = settings.effective(projectId);
        Optional<Review> open = reviews.findOpenForSubject(projectId, subjectType, subjectId);

        // §6g's opt-in, and the ONE place it is applied to the record. A project with review off for
        // this artifact type keeps no review at all: nothing is opened here, so there is no panel to
        // render, no participation to record and no approval precondition to meet.
        //
        // The ladder is deliberately untouched — see ReviewSettings#reviewEnabledFor. What this must
        // NOT do is close an already-open review when the setting is switched off: those rows are a
        // record of something that happened, and a project turning the feature off mid-flight has
        // not thereby caused everyone to withdraw. They stay open, and stay closable by the moves
        // below, which is why this guard covers only the OPENING arm.
        if (to == ReviewStanding.IN_REVIEW) {
            if (open.isEmpty() && policy.reviewEnabledFor(subjectType)) {
                reviews.saveAndFlush(Review.open(
                        companyId,
                        projectId,
                        subjectType,
                        subjectId,
                        versionNo,
                        policy.requiredPeerReviews(),
                        actorId,
                        at));
            }
            return;
        }

        if (open.isEmpty()) {
            return;
        }
        Review review = open.get();

        if (to == ReviewStanding.APPROVED) {
            review.approve(actorId, at);
            participations.save(ReviewParticipation.of(
                    companyId,
                    review.getId(),
                    actorId,
                    ReviewParticipation.Kind.APPROVAL,
                    ReviewParticipation.Verdict.APPROVED,
                    null,
                    at));
        } else {
            // Written out rather than left to equals(), because TWO absences are in play: a review
            // whose submitter no longer resolves, and — in no path today, but the compiler does not
            // know that — a null actor. Either way the answer is "this is not the submitter taking
            // it back", so it is a rejection, and an unattributable withdrawal is not silently
            // credited to whoever is standing there. §66's shape.
            boolean withdrawnByItsSubmitter =
                    actorId != null && actorId.equals(review.getCreatedBy());
            review.close(withdrawnByItsSubmitter);
            if (!withdrawnByItsSubmitter) {
                participations.save(ReviewParticipation.of(
                        companyId,
                        review.getId(),
                        actorId,
                        ReviewParticipation.Kind.APPROVAL,
                        ReviewParticipation.Verdict.REJECTED,
                        null,
                        at));
            }
        }
        reviews.saveAndFlush(review);
    }

    /**
     * Whether §6g's review policy <strong>refuses</strong> a move the ladder would otherwise allow —
     * asked by each ladder <em>before</em> it applies anything, and answering the message to refuse
     * with.
     *
     * <h2>Why this is a second method and not a check inside {@link #moved}</h2>
     *
     * <p>{@link #moved} is told what already happened: it runs after the artifact's status has been
     * written, inside the same transaction, and its job is to keep the record in step. A
     * precondition discovered there could only be enforced by throwing, which would turn an ordinary
     * business refusal into a rolled-back transaction and a 500 — and the three call sites each need
     * to render the refusal their own way (a status endpoint's 409, a bulk operation's per-row
     * skip). So the question is asked first and separately, and the answer is a message.
     *
     * <h2>The two rules, and when they bind</h2>
     *
     * <p>Both are properties of an <strong>open review</strong>, so both are inert on a project with
     * review switched off for this artifact type — there is no review, nobody was asked, and there is
     * nothing to be a peer of. That is not a loophole: it is what "off by default" means, and a
     * project that wants the rules turns the workflow on.
     *
     * <ul>
     *   <li><strong>Author cannot approve own work</strong> (§6g: <em>"the single most valuable
     *       setting here"</em>, default on). "Author" is whoever <em>submitted</em> the review, not
     *       whoever created the artifact — the submitter is the person putting it forward, and on a
     *       case written by one engineer and submitted by their lead it is the lead's sign-off that
     *       would be self-granted. An <strong>unattributable</strong> review (a null
     *       {@code createdBy}, which the column permits) does <em>not</em> bind the rule: refusing
     *       everybody from approving a review whose submitter is unknown creates an artifact that
     *       can never be approved by anyone, which is a dead end where the rule's purpose was to
     *       route the decision to a second person.</li>
     *   <li><strong>Minimum peer reviews</strong> (§6g, default 0). Read from the <em>review</em>'s
     *       pinned count, never the live setting — {@link Review#getRequiredPeerReviews()} says
     *       why.</li>
     * </ul>
     *
     * @param to the standing the ladder is about to move the artifact to; only
     *           {@link ReviewStanding#APPROVED} can be refused here, because the other two take
     *           nothing away that a policy could protect
     * @return the refusal, or empty when the move may proceed
     */
    @Transactional(readOnly = true)
    public Optional<String> refuses(
            UUID projectId,
            ReviewSubjectType subjectType,
            UUID subjectId,
            ReviewStanding to,
            UUID actorId) {

        if (to != ReviewStanding.APPROVED) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                refusesEach(projectId, subjectType, List.of(subjectId), actorId).get(subjectId));
    }

    /**
     * {@link #refuses}' batch — the same two rules over many subjects in <strong>two queries</strong>
     * rather than two per subject.
     *
     * <p>This exists for {@code CaseBulkService}, which evaluates a filtered selection of up to four
     * hundred cases and would otherwise ask {@link ReviewRepository#findOpenForSubject} and then the
     * participations once per row — the N+1 that looks harmless because each query is fast
     * ({@code CaseOwners}' reason). The single-subject form above <em>delegates to this</em> rather
     * than carrying its own copy of the two rules: §58's E1 is a hand-maintained pair under an
     * instruction to keep them in step, and the pair here would be a policy check whose bulk and
     * single-item answers could silently diverge — which is precisely the class of defect §75 found
     * between {@code BulkOperations.permission} and {@code PlanLifecycle}.
     *
     * @return only the subjects that are refused, keyed by subject id. A subject absent from the map
     *         may proceed, which is the common case and the cheap one to check.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> refusesEach(
            UUID projectId,
            ReviewSubjectType subjectType,
            Collection<UUID> subjectIds,
            UUID actorId) {

        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        List<Review> open = reviews.findOpenForSubjects(projectId, subjectType, subjectIds);
        if (open.isEmpty()) {
            // Nothing to enforce against. An artifact approved with no open review is §76's case —
            // every artifact approved before the record existed has none — and inventing a
            // precondition for it would refuse a move the product has always allowed.
            return Map.of();
        }
        ReviewSettings policy = settings.effective(projectId);
        Map<UUID, Integer> lgtms = standingLgtms(open);

        Map<UUID, String> refused = new HashMap<>();
        for (Review review : open) {
            if (!policy.authorMayApproveOwnWork()
                    && review.getCreatedBy() != null
                    && review.getCreatedBy().equals(actorId)) {

                refused.put(review.getSubjectId(),
                        "You submitted this " + noun(subjectType) + " for review, and this project "
                                + "asks somebody else to approve it.");
                continue;
            }

            int required = review.getRequiredPeerReviews();
            int held = lgtms.getOrDefault(review.getId(), 0);
            if (required > 0 && held < required) {
                refused.put(review.getSubjectId(),
                        "This " + noun(subjectType) + " needs " + required + " peer "
                                + (required == 1 ? "review" : "reviews") + " before it can be "
                                + "approved, and has " + held + ".");
            }
        }
        return refused;
    }

    /**
     * How many <em>distinct people other than the submitter</em> currently stand behind each review.
     *
     * <p>Counted in Java rather than SQL because the rule is "the person's <strong>latest</strong>
     * verdict is LGTM", and {@link ReviewParticipation} is append-only precisely so that relenting
     * in either direction is visible: somebody who said LGTM and then requested changes has two rows
     * and is not standing behind it, and somebody who did the reverse is. A {@code COUNT(DISTINCT
     * user_id) WHERE verdict = 'LGTM'} would count the first of those, which is the reading that
     * makes a review trail decorative.
     *
     * <p><strong>The submitter's own LGTM does not count</strong>, whatever the author-may-approve
     * setting says: the two settings answer different questions, and a peer review by the person who
     * asked for it is not a second opinion. {@code APPROVAL}-kind rows are excluded for the same
     * reason — they are the sign-off itself, not a peer's advice.
     */
    private Map<UUID, Integer> standingLgtms(List<Review> reviewRows) {
        Map<UUID, UUID> submitters = new HashMap<>();
        for (Review review : reviewRows) {
            submitters.put(review.getId(), review.getCreatedBy());
        }

        Map<UUID, Map<UUID, ReviewParticipation.Verdict>> latest = new HashMap<>();
        for (ReviewParticipation row
                : participations.findForReviews(reviewRows.stream().map(Review::getId).toList())) {

            if (row.getKind() != ReviewParticipation.Kind.PEER_REVIEW || row.getUserId() == null) {
                continue;
            }
            if (row.getUserId().equals(submitters.get(row.getReviewId()))) {
                continue;
            }
            // findForReviews is ordered oldest-first, so the last write for a user wins.
            latest.computeIfAbsent(row.getReviewId(), id -> new HashMap<>())
                    .put(row.getUserId(), row.getVerdict());
        }

        Map<UUID, Integer> counts = new HashMap<>();
        latest.forEach((reviewId, byUser) -> counts.put(reviewId, (int) byUser.values().stream()
                .filter(verdict -> verdict == ReviewParticipation.Verdict.LGTM)
                .count()));
        return counts;
    }

    private static String noun(ReviewSubjectType subjectType) {
        return subjectType.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether an edit to an artifact that is currently approved must <strong>return it to
     * draft</strong> — §6g's edit-after-approval rule, asked by the three write paths.
     *
     * <p>The caller checks the artifact's own status; this answers only the policy half, because the
     * three aggregates carry three different status enums in two modules this one cannot import.
     * What each caller then does is identical and is described on {@link EditAfterApproval#RE_REVIEW}
     * — including the decision that the demotion is <strong>not</strong> gated on {@code *:approve},
     * which is the one thing about this feature that looks wrong until the reason is read.
     *
     * <p><strong>Tied to the opt-in.</strong> A project with review switched off for this artifact
     * type has opted out of §6g for it, and having one §6g rule keep firing there would be the
     * surprise — an edit silently un-approving something on a project whose team was told review was
     * off. The default is off and the default mode is {@code RETAIN}, so this answers {@code false}
     * twice over until somebody deliberately configures it.
     */
    @Transactional(readOnly = true)
    public boolean reReviewOnEdit(UUID projectId, ReviewSubjectType subjectType) {
        ReviewSettings policy = settings.effective(projectId);
        return policy.reviewEnabledFor(subjectType)
                && policy.editAfterApproval() == EditAfterApproval.RE_REVIEW;
    }

    // ------------------------------------------------------------------- the reads ----

    /**
     * A subject's reviews, newest first — usually one, and never many.
     *
     * <p>Gated by project visibility alone. There is no {@code review:read} key in the catalogue and
     * inventing one is a 403 nobody can grant themselves out of (§67's argument for the evidence
     * download): a caller who may see the artifact may see who reviewed it, which is the entire
     * point of keeping the record.
     *
     * <p>Empty {@link Optional} ⇒ the project is unreachable ⇒ 404. An empty <em>list</em> is the
     * ordinary answer for an artifact nobody has ever submitted, and the panel renders it as such.
     */
    @Transactional(readOnly = true)
    public Optional<List<ReviewView>> findForSubject(
            UUID userId, UUID projectId, ReviewSubjectType subjectType, UUID subjectId) {

        UUID companyId = CompanyContext.require();
        return projects.readVisible(userId, projectId, project ->
                render(companyId, reviews.findForSubject(project.getId(), subjectType, subjectId)));
    }

    // ------------------------------------------------------------ peer participation ----

    /**
     * Records one peer's verdict on an open review — LGTM, or changes requested.
     *
     * <p><strong>The gate is decided by the row.</strong> Which of the three {@code *:review} keys
     * this costs is a property of the review's subject type, not of anything the request sends, so
     * it goes through {@link ProjectService#writeGrantedOnTarget} — §69's seam, and the same
     * argument: the permission to ask <em>is</em> a property of the target, so a review that does
     * not resolve is 404 before any permission is named.
     *
     * <p><strong>This endpoint cannot write an {@code APPROVAL} row.</strong> Approving is the
     * ladder's act, gated on {@code *:approve} by §6g's single gate; a participation endpoint that
     * accepted {@code APPROVED} as a verdict would be a second, cheaper door to the same sign-off,
     * held by everyone with the advisory key. So the two binding verdicts are refused as field
     * errors here and written only by {@link #moved}.
     *
     * <p>Repeat verdicts are allowed and are not an error. {@link ReviewParticipation} is
     * append-only: somebody who requests changes and later relents leaves both rows, in order, which
     * is the fact a review trail is kept for.
     */
    @Transactional
    public Outcome record(
            UUID userId, UUID projectId, UUID reviewId, ReviewParticipationRequest request) {

        UUID companyId = CompanyContext.require();
        ReviewParticipation.Verdict verdict =
                parseVerdict(request == null ? null : request.verdict());

        var gated = projects.writeGrantedOnTarget(
                userId,
                projectId,
                project -> reviews.findInProject(project.getId(), reviewId),
                review -> ProjectService.RequiredPermission.onProject(
                        review.getSubjectType().reviewPermission()),
                (project, review) -> apply(companyId, userId, review, verdict,
                        request == null ? null : request.comment()));

        return unwrap(gated);
    }

    private Outcome apply(
            UUID companyId,
            UUID userId,
            Review review,
            ReviewParticipation.Verdict verdict,
            String comment) {

        if (verdict == null) {
            return new Outcome.Invalid(Map.of("verdict", List.of(
                    "Choose a verdict: LGTM or CHANGES_REQUESTED.")));
        }
        // After the permission and before the state check, the order §72 argued for: a caller who
        // may not review at all is refused before learning whether this particular review is still
        // open, and a caller who may is told the thing they can act on. The sentence itself lives in
        // refuseIfClosed, because the two comment endpoints refuse identically.
        Optional<Outcome> closed = refuseIfClosed(review);
        if (closed.isPresent()) {
            return closed.get();
        }

        Instant now = clock.instant();
        participations.saveAndFlush(ReviewParticipation.of(
                companyId,
                review.getId(),
                userId,
                ReviewParticipation.Kind.PEER_REVIEW,
                verdict,
                comment == null || comment.isBlank() ? null : comment.trim(),
                now));

        return new Outcome.Recorded(render(companyId, List.of(review)).getFirst());
    }

    // ------------------------------------------------------------------- comments ----

    /**
     * Writes one remark on an open review — §6g's threads, and {@code review_comment}'s first
     * writer.
     *
     * <p><strong>The gate is {@code case:comment}, whatever the subject is</strong>, and
     * {@link ReviewSubjectType#COMMENT_PERMISSION} carries the reasoning: the catalogue has one
     * comment key where it has three review keys, because commenting is a <em>tier</em> and not a
     * per-artifact judgement. It still resolves the review before asking, through
     * {@link ProjectService#writeGrantedOnTarget}, for §69's reason — a review that does not resolve
     * is 404 before any permission is named — even though the answer this time does not depend on
     * the row. Using the cheaper {@code writeGranted} would ask the permission first and leak, by
     * the difference between 403 and 404, that a review id names something.
     *
     * <p><strong>A closed review takes no writes</strong>, this one included: 409, the same sentence
     * {@link #record} gives, from the one place that spells it. A review is history once it ends —
     * letting comments accumulate on it would grow a conversation under a decision nobody can act on
     * any more, and the way forward is the same one the verdict endpoint names.
     */
    @Transactional
    public Outcome comment(
            UUID userId, UUID projectId, UUID reviewId, ReviewCommentRequest request) {

        UUID companyId = CompanyContext.require();

        var gated = projects.writeGrantedOnTarget(
                userId,
                projectId,
                project -> reviews.findInProject(project.getId(), reviewId),
                review -> ProjectService.RequiredPermission.onProject(
                        ReviewSubjectType.COMMENT_PERMISSION),
                (project, review) -> addComment(companyId, userId, review, request));

        return unwrap(gated);
    }

    private Outcome addComment(
            UUID companyId, UUID userId, Review review, ReviewCommentRequest request) {

        Optional<Outcome> closed = refuseIfClosed(review);
        if (closed.isPresent()) {
            return closed.get();
        }

        // Resolved through the REVIEW, never findById: a comment id from a sibling project's review
        // satisfies RLS, which is scoped to the company (§64/§68/§69's family). Null when none was
        // asked for and null when one was asked for and did not resolve — ReviewCommentEdit tells
        // the two apart by reading the request, which is why it takes both.
        UUID parentId = request == null ? null : request.parentId();
        ReviewComment parent = parentId == null
                ? null
                : comments.findInReview(review.getId(), parentId).orElse(null);

        return switch (ReviewCommentEdit.validate(request, parent)) {
            case ReviewCommentEdit.Outcome.Invalid invalid -> new Outcome.Invalid(invalid.errors());
            case ReviewCommentEdit.Outcome.Valid valid -> {
                comments.saveAndFlush(ReviewComment.of(
                        companyId,
                        review.getId(),
                        valid.edit().parentId(),
                        valid.edit().body(),
                        userId,
                        clock.instant()));
                yield new Outcome.Recorded(render(companyId, List.of(review)).getFirst());
            }
        };
    }

    /**
     * Marks a thread dealt with, or re-opens it.
     *
     * <p><strong>This costs {@code *:review}, not {@code case:comment}</strong>, and the asymmetry
     * with {@link #comment} is the decision. Writing a remark is the bottom tier's act; declaring
     * one dealt with is a statement about the review's progress, which is the tier that runs the
     * review. The alternative considered was "the reviewing tier <em>or</em> the comment's own
     * author", the shape {@code evidence:delete} has — refused because the only caller it adds is a
     * Viewer resolving their own comment, and a client marking their own feedback dealt with takes
     * it off the reviewer's list without anybody having addressed it. A second door needs a caller
     * it is right for, and this one has none.
     *
     * <p><strong>Only a root.</strong> Resolution is a property of the thread, so a reply is a field
     * error rather than a silent no-op — a client that could resolve a reply would render half a
     * conversation as dealt with.
     *
     * <p>Setting a thread to the state it is already in writes nothing and answers 200:
     * {@code SuiteStatusService}'s call, and for its reason — the request asked for a state and the
     * state is what it asked for.
     */
    @Transactional
    public Outcome resolveComment(
            UUID userId,
            UUID projectId,
            UUID reviewId,
            UUID commentId,
            ReviewCommentResolutionRequest request) {

        UUID companyId = CompanyContext.require();

        var gated = projects.writeGrantedOnTarget(
                userId,
                projectId,
                project -> reviews.findInProject(project.getId(), reviewId),
                review -> ProjectService.RequiredPermission.onProject(
                        review.getSubjectType().reviewPermission()),
                (project, review) -> applyResolution(companyId, userId, review, commentId, request));

        return unwrap(gated);
    }

    private Outcome applyResolution(
            UUID companyId,
            UUID userId,
            Review review,
            UUID commentId,
            ReviewCommentResolutionRequest request) {

        Optional<ReviewComment> found = comments.findInReview(review.getId(), commentId);
        if (found.isEmpty()) {
            // A path segment naming nothing is a 404, not a field error — and it is bodiless and
            // identical to the review's own 404, so the pair cannot be used to probe which ids
            // exist (§6t).
            return new Outcome.NotFound();
        }
        // Closed before validation — §74's order, and its reason: a caller told to fix a field on a
        // review that has ended is being told to do something that will not help.
        Optional<Outcome> closed = refuseIfClosed(review);
        if (closed.isPresent()) {
            return closed.get();
        }
        if (request == null || request.resolved() == null) {
            return new Outcome.Invalid(Map.of("resolved", List.of(
                    "Say whether the thread is resolved: true or false.")));
        }

        ReviewComment comment = found.get();
        if (!comment.isRoot()) {
            return new Outcome.Invalid(Map.of("resolved", List.of(
                    "Resolve the comment that started the thread, not a reply to it.")));
        }

        // Already in the requested state: 200, and NOTHING IS WRITTEN. Not a micro-optimisation —
        // resolving twice would re-stamp resolvedAt and hand resolvedBy to whoever clicked last, so
        // the two columns V24 added to say who dealt with this and when would name the second
        // person to agree rather than the person who decided. SuiteStatusService's call, and this
        // is the reason it matters more here than it did there.
        if (comment.isResolved() != request.resolved()) {
            comment.resolve(request.resolved(), userId, clock.instant());
            comments.saveAndFlush(comment);
        }
        return new Outcome.Recorded(render(companyId, List.of(review)).getFirst());
    }

    // ------------------------------------------------------------------ assignment ----

    /**
     * Asks a person or a team to review — §6g's <em>"assign a user or a team; any member of the team
     * can action it"</em>, and the last unbuilt half of its apparatus.
     *
     * <p><strong>This costs {@code *:review}, the subject's own key.</strong> The catalogue has no
     * key for asking, and none was invented — a new key is seeded to nobody, so on every existing
     * instance it is a 403 no administrator can grant their way out of (§67's argument against
     * {@code evidence:read}, §79's against {@code suite:comment}). So the question is which
     * existing key this is: asking somebody to look at an artifact is a statement
     * about the review's progress, which is exactly the argument §79 settled for resolving a thread.
     * The two cheaper readings were considered. {@code case:comment} would let a Viewer — often the
     * client themselves — put work on a contributor's queue, which is the one thing the bottom tier
     * is defined not to do. {@code *:approve} would confine the request to the people whose sign-off
     * it exists to <em>prepare</em>, so a Test Engineer could not ask a colleague to look at their
     * own case before submitting it for a lead's approval. The gate is decided by the row, through
     * {@link ProjectService#writeGrantedOnTarget} — §69's seam, and a review that does not resolve is
     * 404 before any permission is named.
     *
     * <p><strong>Re-asking somebody already asked writes nothing and answers 201.</strong>
     * {@link #resolveComment}'s decision and §79's reason, which lands harder here: re-stamping would
     * hand {@code assigned_by} to whoever clicked last, so the column kept to record who asked would
     * name the second person to agree that this was a good idea. {@code V25}'s partial unique indexes
     * are the backstop behind the check, not the check.
     *
     * <p><strong>A closed review takes no writes</strong>, this one included — 409, in the one
     * sentence {@link #refuseIfClosed} holds for all four write paths. Asking somebody to review a
     * decision that has already been taken is the clearest case in the family.
     */
    @Transactional
    public Outcome assign(
            UUID userId, UUID projectId, UUID reviewId, ReviewAssignmentRequest request) {

        UUID companyId = CompanyContext.require();

        var gated = projects.writeGrantedOnTarget(
                userId,
                projectId,
                project -> reviews.findInProject(project.getId(), reviewId),
                review -> ProjectService.RequiredPermission.onProject(
                        review.getSubjectType().reviewPermission()),
                (project, review) -> addAssignee(companyId, userId, project, review, request));

        return unwrap(gated);
    }

    private Outcome addAssignee(
            UUID companyId,
            UUID actorId,
            Project project,
            Review review,
            ReviewAssignmentRequest request) {

        // Closed before validation — §74's order, and its reason: a caller told to fix a field on a
        // review that has ended is being told to do something that will not help.
        Optional<Outcome> closed = refuseIfClosed(review);
        if (closed.isPresent()) {
            return closed.get();
        }

        UUID assigneeId = request == null ? null : request.userId();
        UUID teamId = request == null ? null : request.teamId();

        // Both resolved BEFORE validation, so ReviewAssigneeEdit refuses facts rather than ids —
        // ReviewCommentEdit's arrangement. canView is asked of the ASSIGNEE here, not of the caller,
        // whose gate has already run one frame up.
        boolean reaches = assigneeId != null && access.canView(assigneeId, project.getId());
        TeamRef team = teamId == null ? null : teams.find(companyId, teamId).orElse(null);

        return switch (ReviewAssigneeEdit.validate(request, review, reaches, team)) {
            case ReviewAssigneeEdit.Outcome.Invalid invalid -> new Outcome.Invalid(invalid.errors());
            case ReviewAssigneeEdit.Outcome.Valid valid -> {
                store(companyId, actorId, review.getId(), valid.edit());
                yield new Outcome.Recorded(render(companyId, List.of(review)).getFirst());
            }
        };
    }

    /**
     * Writes the request, unless it is already there.
     *
     * <p>The read is the check and the unique index is the backstop ({@code V20}'s and {@code V21}'s
     * rule). Two people asking the same colleague at the same instant is a real race and the loser
     * hits the index — which is the one case this method cannot make idempotent, and it surfaces as
     * the constraint violation it is rather than as a silent second row.
     */
    private void store(UUID companyId, UUID actorId, UUID reviewId, ReviewAssigneeEdit edit) {
        Instant now = clock.instant();

        if (edit.userId() != null) {
            if (assignments.findForUser(reviewId, edit.userId()).isPresent()) {
                return;
            }
            assignments.saveAndFlush(
                    ReviewAssignment.ofUser(companyId, reviewId, edit.userId(), actorId, now));
            return;
        }

        if (assignments.findForTeam(reviewId, edit.teamId()).isPresent()) {
            return;
        }
        assignments.saveAndFlush(
                ReviewAssignment.ofTeam(companyId, reviewId, edit.teamId(), actorId, now));
    }

    /**
     * Withdraws a request.
     *
     * <p><strong>A hard delete, and that is the decision.</strong> An assignment is a worklist entry
     * and not part of the audit record — {@code V25}'s header carries the argument in full, and
     * {@link ReviewComment}'s refusal to delete a comment is the same question answered the other
     * way for the opposite reason: a remark is something somebody <em>said</em> and a request is
     * something somebody was <em>asked</em>. Asking the wrong person and correcting it should leave
     * nothing behind; a trail of retracted requests reads as a history of suspicion.
     *
     * <p><strong>There is no author arm.</strong> {@code evidence:delete}'s {@code OWN} shape — the
     * asker, or the reviewing tier — was considered and refused for §79's reason at one remove: the
     * only caller it would add is somebody withdrawing a request they made while lacking
     * {@code *:review}, and nobody can be in that state, because making the request cost the key in
     * the first place. A second door needs a caller it is right for.
     *
     * <p>Resolved through {@link ReviewAssignmentRepository#findInReview}, never {@code findById},
     * for the reason that method gives.
     */
    @Transactional
    public Outcome unassign(UUID userId, UUID projectId, UUID reviewId, UUID assignmentId) {

        UUID companyId = CompanyContext.require();

        var gated = projects.writeGrantedOnTarget(
                userId,
                projectId,
                project -> reviews.findInProject(project.getId(), reviewId),
                review -> ProjectService.RequiredPermission.onProject(
                        review.getSubjectType().reviewPermission()),
                (project, review) -> removeAssignee(companyId, review, assignmentId));

        return unwrap(gated);
    }

    private Outcome removeAssignee(UUID companyId, Review review, UUID assignmentId) {
        Optional<ReviewAssignment> found =
                assignments.findInReview(review.getId(), assignmentId);
        if (found.isEmpty()) {
            // A path segment naming nothing is a 404, bodiless and identical to the review's own,
            // so the pair cannot be used to probe which ids exist (§6t) — resolveComment's rule.
            return new Outcome.NotFound();
        }
        // Closed AFTER the lookup and before the write, resolveComment's order: a caller who names
        // a request that does not exist is told that, whatever state the review is in.
        Optional<Outcome> closed = refuseIfClosed(review);
        if (closed.isPresent()) {
            return closed.get();
        }

        assignments.delete(found.get());
        assignments.flush();
        return new Outcome.Recorded(render(companyId, List.of(review)).getFirst());
    }

    /**
     * The one spelling of "this review has ended", read by all five writes.
     *
     * <p>Five copies of one sentence is §58's E1 at sentence scale: the verdict endpoint, the two
     * comment endpoints and the two assignment endpoints refuse for identical reasons and must name
     * the identical way forward, and a copy that drifts tells one caller to resubmit and another
     * nothing.
     */
    private static Optional<Outcome> refuseIfClosed(Review review) {
        if (review.getState() == ReviewState.IN_REVIEW) {
            return Optional.empty();
        }
        return Optional.of(new Outcome.Closed(
                "This review has already ended. Submit the "
                        + noun(review.getSubjectType())
                        + " for review again to reopen it."));
    }

    /** The gate's three answers, mapped once rather than at each of the three call sites. */
    private static Outcome unwrap(GateResult<Outcome> gated) {
        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * Only the two advisory verdicts. {@code APPROVED} and {@code REJECTED} are spelled out as
     * refusals rather than falling into the unknown-value arm, because they are the two a client
     * would plausibly send and "choose LGTM or CHANGES_REQUESTED" is a better answer than silence
     * about why the obvious word was rejected.
     */
    private static ReviewParticipation.Verdict parseVerdict(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "LGTM" -> ReviewParticipation.Verdict.LGTM;
            case "CHANGES_REQUESTED" -> ReviewParticipation.Verdict.CHANGES_REQUESTED;
            default -> null;
        };
    }

    // --------------------------------------------------------------------- render ----

    /**
     * Reviews and their participations, with every person resolved in <strong>one</strong> directory
     * call — submitters, approvers and participants together, because they are the same question
     * asked about one set of ids and two calls would double a two-query resolution.
     */
    private List<ReviewView> render(UUID companyId, List<Review> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        List<UUID> reviewIds = rows.stream().map(Review::getId).toList();

        Map<UUID, List<ReviewParticipation>> byReview = new HashMap<>();
        for (ReviewParticipation row : participations.findForReviews(reviewIds)) {
            byReview.computeIfAbsent(row.getReviewId(), id -> new ArrayList<>()).add(row);
        }

        Map<UUID, List<ReviewComment>> commentsByReview = new HashMap<>();
        for (ReviewComment row : comments.findForReviews(reviewIds)) {
            commentsByReview.computeIfAbsent(row.getReviewId(), id -> new ArrayList<>()).add(row);
        }

        Map<UUID, List<ReviewAssignment>> assignedByReview = new HashMap<>();
        for (ReviewAssignment row : assignments.findForReviews(reviewIds)) {
            assignedByReview.computeIfAbsent(row.getReviewId(), id -> new ArrayList<>()).add(row);
        }

        Set<UUID> peopleIds = new HashSet<>();
        for (Review review : rows) {
            addIfPresent(peopleIds, review.getCreatedBy());
            addIfPresent(peopleIds, review.getApprovedBy());
        }
        for (List<ReviewParticipation> forOne : byReview.values()) {
            for (ReviewParticipation row : forOne) {
                addIfPresent(peopleIds, row.getUserId());
            }
        }
        for (List<ReviewComment> forOne : commentsByReview.values()) {
            for (ReviewComment row : forOne) {
                addIfPresent(peopleIds, row.getCreatedBy());
                addIfPresent(peopleIds, row.getResolvedBy());
            }
        }
        // The assignees join the SAME id set — submitters, approvers, participants, commenters,
        // resolvers, the people asked and the people who asked them are one question about one set
        // of ids, and a second call would double a two-query resolution to render one more column.
        Set<UUID> teamIds = new HashSet<>();
        for (List<ReviewAssignment> forOne : assignedByReview.values()) {
            for (ReviewAssignment row : forOne) {
                addIfPresent(peopleIds, row.getAssigneeUserId());
                addIfPresent(peopleIds, row.getAssignedBy());
                addIfPresent(teamIds, row.getAssigneeTeamId());
            }
        }
        Map<UUID, UserRef> resolved = people.resolve(companyId, peopleIds);
        // A second port, so unavoidably a second call — and skipped entirely when nothing names a
        // team, which is the common case: an empty collection bound into SQL `IN ()` is a syntax
        // error, and the port answers an empty map rather than making every caller remember that.
        Map<UUID, TeamRef> resolvedTeams = teams.resolve(companyId, teamIds);

        List<ReviewView> views = new ArrayList<>(rows.size());
        for (Review review : rows) {
            List<ReviewParticipationView> trail =
                    byReview.getOrDefault(review.getId(), List.of()).stream()
                            .map(row -> new ReviewParticipationView(
                                    row.getId(),
                                    resolved.get(row.getUserId()),
                                    row.getKind(),
                                    row.getVerdict(),
                                    row.getComment(),
                                    row.getCreatedAt()))
                            .toList();

            List<ReviewAssigneeView> asked =
                    assignedByReview.getOrDefault(review.getId(), List.of()).stream()
                            .map(row -> new ReviewAssigneeView(
                                    row.getId(),
                                    row.isTeam() ? null : resolved.get(row.getAssigneeUserId()),
                                    row.isTeam() ? resolvedTeams.get(row.getAssigneeTeamId()) : null,
                                    row.getAssignedBy() == null
                                            ? null : resolved.get(row.getAssignedBy()),
                                    row.getAssignedAt()))
                            .toList();

            views.add(new ReviewView(
                    review.getId(),
                    review.getSubjectType(),
                    review.getSubjectId(),
                    review.getSubjectVersionNo(),
                    review.getState(),
                    review.getCreatedBy() == null ? null : resolved.get(review.getCreatedBy()),
                    review.getCreatedAt(),
                    review.getApprovedBy() == null ? null : resolved.get(review.getApprovedBy()),
                    review.getApprovedAt(),
                    asked,
                    trail,
                    threads(commentsByReview.getOrDefault(review.getId(), List.of()), resolved)));
        }
        return views;
    }

    /**
     * A review's flat comment rows, assembled into roots-with-replies.
     *
     * <p>Both loops walk the same oldest-first list, so a reply is never seen before its root and
     * the order inside a thread is the order it was written. <strong>A reply whose root is not in
     * the list is surfaced as a root of its own</strong> rather than dropped — §68's
     * {@code buildValueTree} rule, and the reason it matters more here than it did there: a remark
     * nothing renders is one nobody can answer or resolve, and it would sit in the database looking
     * to its author like a comment they made. Nothing today can produce one, since {@code parent_id}
     * has a foreign key and the parent is validated into the same review; that is exactly the state
     * {@code SuiteTree}'s orphan-dropping was in before it was not.
     */
    private static List<ReviewCommentView> threads(
            List<ReviewComment> rows, Map<UUID, UserRef> resolved) {

        if (rows.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<ReviewCommentView>> repliesByRoot = new HashMap<>();
        Set<UUID> rootIds = new HashSet<>();
        for (ReviewComment row : rows) {
            if (row.isRoot()) {
                rootIds.add(row.getId());
            }
        }
        for (ReviewComment row : rows) {
            if (!row.isRoot() && rootIds.contains(row.getParentId())) {
                repliesByRoot.computeIfAbsent(row.getParentId(), id -> new ArrayList<>())
                        .add(view(row, resolved, List.of()));
            }
        }

        List<ReviewCommentView> threads = new ArrayList<>();
        for (ReviewComment row : rows) {
            if (row.isRoot()) {
                threads.add(view(
                        row, resolved, repliesByRoot.getOrDefault(row.getId(), List.of())));
            } else if (!rootIds.contains(row.getParentId())) {
                threads.add(view(row, resolved, List.of()));
            }
        }
        return threads;
    }

    private static ReviewCommentView view(
            ReviewComment row, Map<UUID, UserRef> resolved, List<ReviewCommentView> replies) {

        return new ReviewCommentView(
                row.getId(),
                row.getCreatedBy() == null ? null : resolved.get(row.getCreatedBy()),
                row.getBody(),
                row.getCreatedAt(),
                row.getResolvedAt(),
                row.getResolvedBy() == null ? null : resolved.get(row.getResolvedBy()),
                replies);
    }

    private static void addIfPresent(Set<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }
}
