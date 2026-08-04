package com.tcms.project;

import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
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
 * Reads and writes one project's §6g policy — and, through {@link #effective}, is the
 * <strong>one place any consumer anywhere learns what a project's review settings are</strong>.
 *
 * <h2>The two gates are deliberately different, and the read one is the interesting half</h2>
 *
 * <p>Writing needs {@code project:settings} on this project, which is the key §69 already gates the
 * classification vocabulary on. <strong>Reading needs only project visibility.</strong> That is not
 * a relaxation, it is what makes §6g's first requirement implementable: <em>"a project that never
 * turns it on should see no extra UI at all"</em> is a decision every client has to make on every
 * artifact screen, for every reader — a Test Engineer, a Viewer — and none of them holds
 * {@code project:settings}. Gating the read on the write key would leave the SPA unable to find out
 * whether to render the review panel, and the only ways out of that are worse: render it always
 * (the requirement, unimplemented) or infer the setting from a 409 after somebody clicks (§61's
 * disable-don't-hide, inverted).
 *
 * <p>Nothing here is sensitive in the way the key suggests. These six values are policy about how
 * the project works, visible in the product's behaviour to anybody who can see the project at all;
 * concealing them would conceal nothing they could not deduce by pressing a button.
 *
 * <h2>Why {@link #effective} tolerates a missing row</h2>
 *
 * <p>{@code V23} backfills every project and {@code ProjectCreateService} writes one for each new
 * project, so the empty case should be unreachable. It is handled anyway and the reason is stated on
 * {@link ProjectSettings}: the safe answer is the defaults — review off, nothing extra rendered,
 * approval behaving exactly as it did before §76 — and the unsafe answer is a 500 on a case list.
 */
@Service
public class ProjectSettingsService {

    /** Roles spec: the project's own configuration, held per project. §69's key, second consumer. */
    private static final String PROJECT_SETTINGS = "project:settings";

    /**
     * {@code V23}'s {@code ck_project_settings_required_peer_reviews}. Duplicated here because this
     * is the check and the constraint is the backstop — V20's and V21's rule — and because a
     * {@code 23514} reaching a client as a 500 is not an answer anybody can act on.
     */
    private static final int MAX_PEER_REVIEWS = 20;

    private final ProjectService projects;
    private final ProjectSettingsRepository settings;
    private final PeopleDirectory people;
    private final Clock clock;

    public ProjectSettingsService(
            ProjectService projects,
            ProjectSettingsRepository settings,
            PeopleDirectory people,
            Clock clock) {

        this.projects = projects;
        this.settings = settings;
        this.people = people;
        this.clock = clock;
    }

    /** What a save did. Sealed, so the controller's switch is exhaustive. */
    public sealed interface Outcome {

        /** Unreachable project — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code project:settings} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** 400, on the fields. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** Saved. 200 with the settings as they now stand. */
        record Updated(ProjectSettingsView view) implements Outcome {
        }
    }

    // ------------------------------------------------------------------ the one read ----

    /**
     * A project's settings as every consumer must read them — <strong>never
     * {@code settings.findForProject(...).get()}</strong> at a call site.
     *
     * <p>Public because two other modules need it: {@code repository} for a case's and a suite's
     * edit-after-approval mode, {@code execution} for a plan's. Both may depend on {@code project}
     * (plan §3's foundation rule), so this is a direct call and not the domain event a
     * {@code review} module would have forced (§76's placement argument, one table over).
     *
     * <p><strong>Takes no user and asks no permission.</strong> Every caller has already been
     * through its own gate on the project — a case edit through {@code case:edit}, a status move
     * through the ladder's key — and asking a second, unrelated permission here would mean a Test
     * Engineer's perfectly authorized edit failed on a settings read. The value is not the caller's
     * to see or not see; it is the project's own behaviour.
     */
    @Transactional(readOnly = true)
    public ReviewSettings effective(UUID projectId) {
        return settings.findForProject(projectId)
                .map(ProjectSettings::toSettings)
                .orElseGet(ProjectSettings::defaults);
    }

    /**
     * The settings screen's read, gated on project visibility alone — see the class header.
     *
     * <p>Empty {@link Optional} ⇒ the project is unreachable ⇒ 404.
     */
    @Transactional(readOnly = true)
    public Optional<ProjectSettingsView> find(UUID userId, UUID projectId) {
        UUID companyId = CompanyContext.require();
        return projects.readVisible(userId, projectId, project ->
                render(companyId, project.getId()));
    }

    // ----------------------------------------------------------------- the one write ----

    /**
     * Replaces a project's settings.
     *
     * <p><strong>Creates the row if it is missing</strong> rather than 404ing. A project with no
     * settings row is a project on its defaults ({@link #effective} says so), and refusing to let
     * anybody configure it because nothing has configured it yet is a dead end reachable by ordinary
     * use — §69's and §68's bound, from the same side.
     */
    @Transactional
    public Outcome update(UUID userId, UUID projectId, ProjectSettingsRequest request) {
        UUID companyId = CompanyContext.require();

        var gated = projects.writeGranted(userId, projectId, PROJECT_SETTINGS, project ->
                apply(companyId, userId, project.getId(), request));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(UUID companyId, UUID userId, UUID projectId, ProjectSettingsRequest req) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (req == null) {
            return new Outcome.Invalid(Map.of(
                    "settings", List.of("Send the project's settings.")));
        }

        EditAfterApproval mode = parseMode(req.editAfterApproval());
        if (mode == null) {
            errors.put("editAfterApproval", List.of(
                    "Choose what an edit to an approved artifact does: RETAIN or RE_REVIEW."));
        }

        Integer peers = req.requiredPeerReviews();
        if (peers == null) {
            errors.put("requiredPeerReviews", List.of("Give a number of required peer reviews."));
        } else if (peers < 0 || peers > MAX_PEER_REVIEWS) {
            // The upper bound refused here rather than left to V23's CHECK, so the message names the
            // range instead of arriving as a constraint violation nobody can read.
            errors.put("requiredPeerReviews", List.of(
                    "Required peer reviews must be between 0 and " + MAX_PEER_REVIEWS + "."));
        }

        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        Instant now = clock.instant();
        ProjectSettings row = settings.findForProject(projectId)
                .orElseGet(() -> ProjectSettings.initial(companyId, projectId, now));

        row.apply(
                new ReviewSettings(
                        Boolean.TRUE.equals(req.reviewEnabledCase()),
                        Boolean.TRUE.equals(req.reviewEnabledSuite()),
                        Boolean.TRUE.equals(req.reviewEnabledPlan()),
                        peers,
                        Boolean.TRUE.equals(req.authorMayApproveOwnWork()),
                        mode,
                        Boolean.TRUE.equals(req.requireApprovedCaseForRun()),
                        Boolean.TRUE.equals(req.requireApprovedPlanToStart())),
                userId,
                now);
        settings.saveAndFlush(row);

        return new Outcome.Updated(view(companyId, row));
    }

    // ------------------------------------------------------------------ provisioning ----

    /**
     * The row a project gets at creation. Called by {@code ProjectCreateService} inside its own
     * transaction, so a project and its settings commit together.
     *
     * <p>Idempotent — a second call on a project that already has a row leaves it alone. That is not
     * defensive: {@code V23}'s backfill and this method both write the same row for any project
     * created in the window around the release, and the one that loses must not overwrite the one
     * that won with defaults.
     */
    @Transactional
    public void createFor(UUID companyId, UUID projectId, Instant now) {
        if (settings.findForProject(projectId).isEmpty()) {
            settings.saveAndFlush(ProjectSettings.initial(companyId, projectId, now));
        }
    }

    // ------------------------------------------------------------------------ render ----

    private ProjectSettingsView render(UUID companyId, UUID projectId) {
        return settings.findForProject(projectId)
                .map(row -> view(companyId, row))
                .orElseGet(() -> new ProjectSettingsView(
                        projectId, ProjectSettings.defaults(), null, null));
    }

    private ProjectSettingsView view(UUID companyId, ProjectSettings row) {
        UserRefView editor = row.getUpdatedBy() == null
                ? null
                : people.resolve(companyId, Set.of(row.getUpdatedBy()))
                        .get(row.getUpdatedBy());

        return new ProjectSettingsView(
                row.getProjectId(), row.toSettings(), editor, row.getUpdatedAt());
    }

    /**
     * Spelled out rather than {@code Enum.valueOf}, so an unknown value is a field error instead of
     * an {@code IllegalArgumentException} surfacing as a 500 — the shape
     * {@code ReviewService#parseVerdict} uses one class over.
     */
    private static EditAfterApproval parseMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "RETAIN" -> EditAfterApproval.RETAIN;
            case "RE_REVIEW" -> EditAfterApproval.RE_REVIEW;
            default -> null;
        };
    }
}
