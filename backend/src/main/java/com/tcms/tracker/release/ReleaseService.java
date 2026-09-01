package com.tcms.tracker.release;

import com.tcms.api.PatchBody;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's releases — create, edit, delete and publish.
 *
 * <h2>Three keys, and this module adds none of them</h2>
 *
 * <p>{@code release:read}, {@code release:manage} and {@code release:publish} were seeded by M0's
 * {@code V2} and have been in the roles spec §A3 since it was written. So there is no catalogue
 * edit here and no {@code matrixCopies.test.ts} churn — the permissions arrived a module before the
 * table did, which is the ordinary shape in this plan and not an accident.
 *
 * <p>{@code release:publish} is separate from {@code release:manage} for the reason
 * {@code doc:publish} is separate from {@code doc:edit}: publishing moves a state, and it is its own
 * gate and its own audit row. Both are held by the same three roles, which is why the two verbs may
 * share one MCP tool without §2's failure — but they are still two gates here, because the day
 * somebody grants one without the other, the split has to already exist.
 *
 * <h2>{@code readGranted}, not {@code readVisible}</h2>
 *
 * <p>{@code BoardConfigService} reads a project's boards on visibility alone, because
 * {@code board:read} is held by all seven roles and gating would hide nothing from anybody who can
 * reach the screen. <strong>{@code release:read} is the first gate on this surface that is not</strong>:
 * Automation Service does not hold it (§A3). Reading a project's releases on visibility alone would
 * serve a delivery plan to a CI token, which is precisely the caller that key excludes.
 */
@Service
public class ReleaseService {

    private static final String READ = "release:read";
    private static final String MANAGE = "release:manage";
    private static final String PUBLISH = "release:publish";
    private static final int MAX_VERSION = 40;

    private final ProjectService projects;
    private final ReleaseRepository releases;
    private final ReadinessService readiness;
    private final Clock clock;

    ReleaseService(
            ProjectService projects,
            ReleaseRepository releases,
            ReadinessService readiness,
            Clock clock) {

        this.projects = projects;
        this.releases = releases;
        this.readiness = readiness;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** A field the caller can fix — a blank version, or one over the length limit. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** Another release in this project already has that version, case-insensitively. */
        record Duplicate(String version) implements Outcome {
        }

        record Written(ReleaseView view) implements Outcome {
        }

        record Deleted() implements Outcome {
        }
    }

    /**
     * {@code GET /projects/{projectId}/releases}, with each row's readiness inline.
     *
     * <p>Readiness is computed per row rather than left to the client, because the list draws two
     * meters and a badge per row and a screen that resolved them one at a time would be N+1 over
     * answers the server already has. A project has tens of releases, not thousands.
     */
    @Transactional(readOnly = true)
    public GateResult<List<ReleaseSummaryView>> list(UUID userId, UUID projectId) {
        return projects.readGranted(userId, projectId, READ, project ->
                releases.findByProjectIdOrderBySortKeyAsc(project.getId()).stream()
                        .map(release -> new ReleaseSummaryView(
                                release.getId(), release.getVersion(), release.getDescription(),
                                release.getTargetDate(), release.getStatus(),
                                release.getReleasedAt(), release.getCreatedAt(),
                                readiness.summaryOf(project.getId(), release)))
                        .toList());
    }

    @Transactional(readOnly = true)
    public GateResult<ReleaseView> get(UUID userId, UUID releaseId) {
        Optional<Release> resolved = releases.findScopedById(releaseId);
        if (resolved.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.readGranted(userId, resolved.get().getProjectId(), READ,
                project -> ReleaseView.of(releases.findScopedById(releaseId).orElseThrow()));
    }

    public GateResult<Outcome> create(UUID userId, UUID projectId, ReleaseInput input) {
        return projects.writeGranted(userId, projectId, MANAGE,
                project -> applyCreate(project, userId, input));
    }

    public GateResult<Outcome> update(
            UUID userId, UUID releaseId, ReleaseUpdate request, PatchBody patch) {

        return onRelease(userId, releaseId, MANAGE,
                (project, release) -> applyUpdate(project, release, userId, request, patch));
    }

    public GateResult<Outcome> delete(UUID userId, UUID releaseId) {
        return onRelease(userId, releaseId, MANAGE, (project, release) -> {
            // The issues pointing at it keep existing and lose their target: the foreign key is
            // ON DELETE SET NULL, because a release is a PLAN and deleting a plan must not require
            // deleting or reassigning the work first. They return to the backlog's "Unscheduled".
            releases.delete(release);
            return new Outcome.Deleted();
        });
    }

    public GateResult<Outcome> publish(UUID userId, UUID releaseId) {
        return onRelease(userId, releaseId, PUBLISH, (project, release) -> {
            release.publish(userId, clock.instant());
            return new Outcome.Written(ReleaseView.of(releases.save(release)));
        });
    }

    // =====================================================================

    /**
     * Resolve, then gate, then <strong>re-read inside the gate</strong>.
     *
     * <p>The first read is outside any transaction of ours, so its entity is committed and detached
     * by the time {@code writeGranted} opens one — 012 T4 lost a round trip to exactly this, and
     * {@code EntityManager.refresh} refuses a detached entity. The row is resolved first anyway
     * because the project to gate against is a property of it: a caller whose release does not exist
     * cannot be told 403 without the server inventing which project they failed on.
     */
    private GateResult<Outcome> onRelease(
            UUID userId, UUID releaseId, String permission,
            BiFunction<Project, Release, Outcome> write) {

        Optional<Release> resolved = releases.findScopedById(releaseId);
        if (resolved.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.writeGranted(userId, resolved.get().getProjectId(), permission,
                project -> releases.findScopedById(releaseId)
                        .map(release -> write.apply(project, release))
                        .orElseThrow());
    }

    private Outcome applyCreate(Project project, UUID userId, ReleaseInput input) {
        String version = input == null || input.version() == null ? "" : input.version().strip();
        Optional<Outcome> refused = validate(version);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (taken(project.getId(), version, null)) {
            return new Outcome.Duplicate(version);
        }
        Release created = releases.save(Release.create(
                UUID.randomUUID(), CompanyContext.require(), project.getId(), version,
                blankToNull(input.description()), input.targetDate(), userId, clock.instant()));
        return new Outcome.Written(ReleaseView.of(created));
    }

    private Outcome applyUpdate(
            Project project, Release release, UUID userId, ReleaseUpdate request, PatchBody patch) {

        if (patch.has("version")) {
            String version = request.version() == null ? "" : request.version().strip();
            Optional<Outcome> refused = validate(version);
            if (refused.isPresent()) {
                return refused.get();
            }
            if (taken(project.getId(), version, release.getId())) {
                return new Outcome.Duplicate(version);
            }
            // Recomputes sort_key with it, so a release renamed 1.9 -> 1.10 MOVES in the list
            // rather than staying where its old text sorted it.
            release.rename(version);
        }
        String description = patch.has("description")
                ? blankToNull(request.description()) : release.getDescription();
        LocalDate targetDate = patch.has("targetDate")
                ? request.targetDate() : release.getTargetDate();

        release.revise(description, targetDate, userId, clock.instant());
        return new Outcome.Written(ReleaseView.of(releases.save(release)));
    }

    /**
     * A blank version and an over-long one, and nothing else.
     *
     * <p>There is deliberately no format rule. {@code ReleaseSortKey} falls back to raw collation
     * for anything it cannot parse, which is what makes {@code nightly} and {@code 2026-W31} legal
     * release names — refusing them would make a common practice unrecordable, and this product does
     * not get to decide how a client names what it ships.
     *
     * <p>Nor is a past {@code targetDate} refused: a release that slipped is a real state, and a
     * server that would not record it would make the roadmap a fiction.
     */
    private Optional<Outcome> validate(String version) {
        if (version.isBlank()) {
            return Optional.of(new Outcome.Invalid(Map.of("version", List.of(
                    "A release needs a version — the string you ship, like 2.4."))));
        }
        if (version.length() > MAX_VERSION) {
            return Optional.of(new Outcome.Invalid(Map.of("version", List.of(
                    "A version is at most " + MAX_VERSION + " characters."))));
        }
        return Optional.empty();
    }

    /** Matched the way {@code uq_release_version} matches — on {@code lower(version)}. */
    private boolean taken(UUID projectId, String version, UUID except) {
        return releases.findByProjectIdAndVersionIgnoreCase(projectId, version)
                .filter(other -> !other.getId().equals(except))
                .isPresent();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
