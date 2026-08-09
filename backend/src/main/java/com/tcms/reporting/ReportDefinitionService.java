package com.tcms.reporting;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved reports: list mine, save one, delete one.
 *
 * <p>§6e's <em>"saved as part of the {@code ReportDefinition} so the same cycle report regenerates
 * next release with one click"</em>, and until this class the {@code GET} was a declared operation
 * nothing served — a dead route the SPA answered with an empty array from its mock.
 *
 * <h2>Every method is scoped by owner, and that is the access control</h2>
 *
 * <p>{@link ReportDefinition}'s header has the argument: a definition belongs to the person who
 * saved it and v1 cannot share one, so the caller's own id is a predicate on every query rather than
 * a check after one. {@link ReportDefinitionRepository} deliberately offers no method that could
 * find somebody else's row, which is what keeps that from being a rule this file has to remember.
 *
 * <p>The consequence is stated in the contract: <strong>two people asking for the same project's
 * saved reports receive different lists.</strong>
 *
 * <h2>{@code report:view} for all three, including the two writes</h2>
 *
 * <p>No new key — §67's argument against {@code evidence:read}, §79's, §80's and §82's, arriving at
 * a surface where the choice is between the two keys {@code V3} already seeds. Saving is not
 * exporting: nothing leaves the server, and the row is visible to one person. Requiring
 * {@code report:export} would mean the persona §6e's split exists for — <em>may read on screen, may
 * not carry off</em>, the custom role §87 had to build because no built-in lacked either key —
 * could not save the report it is allowed to read, which turns a viewing role into one that must
 * retype its selection every release.
 *
 * <p>The gates are {@link ProjectService#readGranted} for the list and
 * {@link ProjectService#writeGranted} for the two writes: both answer 404 for an unreachable project
 * before any permission is consulted (§6t), and 403 only to a caller who is demonstrably looking at
 * the project.
 */
@Service
class ReportDefinitionService {

    /** Roles spec §A5 / {@code V3}. See the class header for why the writes cost it too. */
    private static final String VIEW_REPORT = "report:view";

    private final ProjectService projects;
    private final ReportDefinitionRepository definitions;
    private final ReportTypes types;
    private final PeopleDirectory people;
    private final Clock clock;

    ReportDefinitionService(
            ProjectService projects,
            ReportDefinitionRepository definitions,
            ReportTypes types,
            PeopleDirectory people,
            Clock clock) {

        this.projects = projects;
        this.definitions = definitions;
        this.types = types;
        this.people = people;
        this.clock = clock;
    }

    /** What a call produced. Sealed, so the controller's switch is exhaustive at compile time. */
    sealed interface Outcome<T> {

        /** Unreachable project, or an id that names no definition of <em>this</em> caller's. */
        record NotFound<T>() implements Outcome<T> {
        }

        /** The caller can see the project and does not hold {@code report:view}. */
        record Forbidden<T>() implements Outcome<T> {
        }

        /** Field name to messages, so a form lands each one on its own input. */
        record Invalid<T>(Map<String, List<String>> errors) implements Outcome<T> {
        }

        /** Here it is. {@code Void} for the delete, which has nothing to hand back. */
        record Done<T>(T value) implements Outcome<T> {
        }
    }

    /** What a client may set. Deliberately three fields — see {@link ReportDefinition#create}. */
    record Input(String type, String name, String parameters) {
    }

    /** The caller's saved reports for this project, newest first. */
    @Transactional(readOnly = true)
    Outcome<List<ReportDefinitionView>> list(UUID userId, UUID projectId) {
        UUID companyId = CompanyContext.require();

        return collapse(projects.readGranted(userId, projectId, VIEW_REPORT, project -> {
            List<ReportDefinition> rows = definitions
                    .findByProjectIdAndOwnerIdOrderByCreatedAtDesc(project.getId(), userId);
            // One name lookup for the whole list rather than one per row — the N+1 that looks
            // harmless because each query is fast (ArtifactPeople's note, four modules over). It
            // resolves a single id today, since every row here is the caller's.
            UserRef owner = owner(companyId, userId);
            List<ReportDefinitionView> views = new ArrayList<>();
            for (ReportDefinition row : rows) {
                views.add(ReportDefinitionView.of(row, owner));
            }
            return new Outcome.Done<>(views);
        }));
    }

    /**
     * Saves one, or refuses it.
     *
     * <p>Two refusals and they are collected rather than short-circuited, for {@code
     * ReportJobService#validate}'s reason: a form that reveals its next objection only after the
     * first is fixed makes one mistake into two round trips.
     *
     * <p><strong>{@code parameters} is checked for being a JSON object and for nothing else.</strong>
     * A definition outlives the release it was saved in, so validating it against the report type's
     * current requirements would make a saved report unsaveable the day a parameter becomes
     * required — while the generate it feeds already refuses a selection the report cannot use, with
     * that report's own sentence, at the moment somebody is waiting for the answer.
     */
    @Transactional
    Outcome<ReportDefinitionView> create(UUID userId, UUID projectId, Input input) {
        UUID companyId = CompanyContext.require();

        return collapse(projects.writeGranted(userId, projectId, VIEW_REPORT, project -> {
            Map<String, List<String>> errors = validate(input);
            if (!errors.isEmpty()) {
                return new Outcome.Invalid<ReportDefinitionView>(errors);
            }
            return new Outcome.Done<>(save(companyId, project, userId, input));
        }));
    }

    /**
     * Deletes one.
     *
     * <p>A definition the caller does not own is the same {@code NotFound} as one that does not
     * exist — they cannot list it, so distinguishing the two would make this route an oracle over
     * other people's saved reports (§6t at row level).
     *
     * <p>Nothing cascades away with it: {@code report_job.definition_id} ({@code V27}) and
     * {@code report_artifact.definition_id} ({@code V28}) are both {@code ON DELETE SET NULL}, so
     * the jobs it produced and the files they wrote survive as ad-hoc ones — which is what they now
     * are.
     */
    @Transactional
    Outcome<Void> delete(UUID userId, UUID projectId, UUID definitionId) {
        return collapse(projects.writeGranted(userId, projectId, VIEW_REPORT, project -> {
            Optional<ReportDefinition> row = definitions
                    .findByIdAndProjectIdAndOwnerId(definitionId, project.getId(), userId);
            if (row.isEmpty()) {
                return new Outcome.NotFound<Void>();
            }
            definitions.delete(row.get());
            return new Outcome.Done<Void>(null);
        }));
    }

    private ReportDefinitionView save(UUID companyId, Project project, UUID userId, Input input) {
        ReportDefinition definition = definitions.save(ReportDefinition.create(
                companyId,
                project.getId(),
                input.type().trim(),
                input.name().trim(),
                input.parameters() == null || input.parameters().isBlank()
                        ? "{}"
                        : input.parameters(),
                userId,
                clock.instant()));

        return ReportDefinitionView.of(definition, owner(companyId, userId));
    }

    private Map<String, List<String>> validate(Input input) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        types.refusal(input.type()).ifPresent(message -> errors.put("type", List.of(message)));

        if (input.name() == null || input.name().isBlank()) {
            errors.put("name", List.of("Give this saved report a name."));
        }

        if (input.parameters() != null && !input.parameters().isBlank()) {
            try {
                ReportParameters.parse(input.parameters());
            } catch (IllegalArgumentException malformed) {
                // The message, never the exception: these sentences are written to be shown to the
                // person who typed the thing (see ReportParameters' header).
                errors.put("parameters", List.of(malformed.getMessage()));
            }
        }

        return errors;
    }

    /**
     * The caller, as the contract's {@code UserRef}.
     *
     * <p>The fallback is not defensive noise: {@code report_definition.owner_id} references
     * {@code app_user}, which is instance-level with no cascade, so "the account that saved this has
     * since been removed from the instance" is a state a row can reach — and this view's
     * {@code owner} is a required contract field, so answering with an absent one would be a
     * response no generated client can read. It names the state rather than inventing a person.
     */
    private UserRef owner(UUID companyId, UUID userId) {
        Map<UUID, UserRef> resolved = people.resolve(companyId, Set.of(userId));
        UserRef person = resolved.get(userId);
        return person == null ? new UserRef(userId, "Former member", "", null) : person;
    }

    /**
     * Collapses a gate result whose value is already an {@link Outcome}.
     *
     * <p>Restated arm by arm rather than cast, {@link ReportReadService#open}'s reason: the refusals
     * carry no value, so the "same" outcome at a different {@code T} is a new record and the switch
     * being exhaustive means a fourth gate result would be a compile error here.
     */
    private static <T> Outcome<T> collapse(GateResult<Outcome<T>> result) {
        return switch (result) {
            case GateResult.Unreachable<Outcome<T>> ignored -> new Outcome.NotFound<>();
            case GateResult.Denied<Outcome<T>> ignored -> new Outcome.Forbidden<>();
            case GateResult.Allowed<Outcome<T>> allowed -> allowed.value();
        };
    }
}
