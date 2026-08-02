package com.tcms.project;

import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
    private final ReviewRepository reviews;
    private final ReviewParticipationRepository participations;
    private final PeopleDirectory people;
    private final Clock clock;

    public ReviewService(
            ProjectService projects,
            ReviewRepository reviews,
            ReviewParticipationRepository participations,
            PeopleDirectory people,
            Clock clock) {

        this.projects = projects;
        this.reviews = reviews;
        this.participations = participations;
        this.people = people;
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
        Optional<Review> open = reviews.findOpenForSubject(projectId, subjectType, subjectId);

        if (to == ReviewStanding.IN_REVIEW) {
            if (open.isEmpty()) {
                reviews.saveAndFlush(Review.open(
                        companyId, projectId, subjectType, subjectId, versionNo, actorId, at));
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

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
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
        // open, and a caller who may is told the thing they can act on.
        if (review.getState() != ReviewState.IN_REVIEW) {
            return new Outcome.Closed(
                    "This review has already ended. Submit the "
                            + review.getSubjectType().name().toLowerCase(Locale.ROOT)
                            + " for review again to reopen it.");
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

        Map<UUID, List<ReviewParticipation>> byReview = new HashMap<>();
        for (ReviewParticipation row
                : participations.findForReviews(rows.stream().map(Review::getId).toList())) {
            byReview.computeIfAbsent(row.getReviewId(), id -> new ArrayList<>()).add(row);
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
        Map<UUID, UserRefView> resolved = people.resolve(companyId, peopleIds);

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
                    trail));
        }
        return views;
    }

    private static void addIfPresent(Set<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }
}
