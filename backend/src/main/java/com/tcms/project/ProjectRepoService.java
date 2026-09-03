package com.tcms.project;

import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's repositories — reference data (D16), read by anybody who can open the project and
 * written by whoever configures it.
 *
 * <h2>Two gates, and the read one is the wider on purpose</h2>
 *
 * <p>Writing costs {@code project:settings}, the key §69 already gates the classification vocabulary
 * on. Reading costs {@code project:read}, which every project role holds — because the list is what
 * a new joiner is told to check out and what an assistant is handed with its task, and gating that
 * on the administrative key would leave the two readers who actually need it unable to ask.
 * {@code ProjectSettingsService}'s asymmetry, one table over, with the same shape and the same
 * reason.
 */
@Service
public class ProjectRepoService {

    private static final String PROJECT_SETTINGS = "project:settings";

    private static final String PROJECT_READ = "project:read";

    private static final int MAX_NAME = 200;
    private static final int MAX_URL = 500;
    private static final int MAX_BRANCH = 200;
    private static final int MAX_SUBDIRECTORY = 500;
    private static final int MAX_BUILD_COMMAND = 1000;

    private final ProjectService projects;
    private final ProjectRepoRepository repositories;
    private final Clock clock;

    public ProjectRepoService(
            ProjectService projects, ProjectRepoRepository repositories, Clock clock) {

        this.projects = projects;
        this.repositories = repositories;
        this.clock = clock;
    }

    /** What a write did. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** No such repository in this project. */
        record NotFound() implements Outcome {
        }

        /** Field name to messages, for the form to land them on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Written(ProjectRepoView view) implements Outcome {
        }

        /** Removed, or already gone. Both are 204. */
        record Deleted() implements Outcome {
        }
    }

    /**
     * The project's repositories, by position. {@code []} for a project that spans none, which is
     * the ordinary case and not a degraded one (D16).
     */
    @Transactional(readOnly = true)
    public GateResult<List<ProjectRepoView>> list(UUID userId, UUID projectId) {
        return projects.readGranted(userId, projectId, PROJECT_READ,
                project -> views(project.getId()));
    }

    /**
     * The same list with <strong>no gate of its own</strong> — for a caller that has already been
     * through one on this project.
     *
     * <p>{@code ProjectSettingsService.effective}'s shape and for its reason: the agent-context
     * composite has already gated the issue on its project's visibility, and asking a second,
     * unrelated permission here would mean a perfectly authorized read failed on the repository
     * list. Public because {@code tracker} needs it and may depend on this module.
     */
    @Transactional(readOnly = true)
    public List<ProjectRepoView> forProject(UUID projectId) {
        return views(projectId);
    }

    @Transactional
    public GateResult<Outcome> add(UUID userId, UUID projectId, ProjectRepoInput input) {
        return projects.writeGranted(userId, projectId, PROJECT_SETTINGS, project -> {
            List<ProjectRepo> existing = repositories.findByProjectIdOrderByPositionAsc(
                    project.getId());
            Map<String, List<String>> errors = validate(input, existing, null);
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }
            Instant now = clock.instant();
            int next = existing.isEmpty()
                    ? 0
                    : existing.get(existing.size() - 1).getPosition() + 1;
            ProjectRepo created = repositories.save(ProjectRepo.add(
                    CompanyContext.require(), project.getId(), next, input, userId, now));
            return written(project.getId(), created);
        });
    }

    @Transactional
    public GateResult<Outcome> update(
            UUID userId, UUID projectId, UUID repositoryId, ProjectRepoInput input) {

        return projects.writeGranted(userId, projectId, PROJECT_SETTINGS, project -> {
            Optional<ProjectRepo> found = scoped(project.getId(), repositoryId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            List<ProjectRepo> existing = repositories.findByProjectIdOrderByPositionAsc(
                    project.getId());
            Map<String, List<String>> errors = validate(input, existing, repositoryId);
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }
            ProjectRepo row = found.get();
            row.apply(input, userId, clock.instant());
            return written(project.getId(), repositories.save(row));
        });
    }

    /**
     * Removes one. Nothing references a repository row, so the delete is clean — and the positions
     * of the survivors are deliberately left alone: they are an order, not a sequence, and
     * renumbering would rewrite rows nobody touched to close a gap nobody can see.
     */
    @Transactional
    public GateResult<Outcome> delete(UUID userId, UUID projectId, UUID repositoryId) {
        return projects.writeGranted(userId, projectId, PROJECT_SETTINGS, project -> {
            Optional<ProjectRepo> found = scoped(project.getId(), repositoryId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            repositories.delete(found.get());
            return new Outcome.Deleted();
        });
    }

    private Optional<ProjectRepo> scoped(UUID projectId, UUID repositoryId) {
        return repositories.findScopedById(repositoryId)
                .filter(row -> row.getProjectId().equals(projectId));
    }

    private Outcome written(UUID projectId, ProjectRepo row) {
        // Re-read rather than computed from the list this method already held: `add` appended and
        // `update` may have been the only row, so the lowest position has to come from the state
        // after the write for `primary` to be true of what was actually saved.
        return new Outcome.Written(ProjectRepoView.of(row, lowestPositionOf(projectId)));
    }

    private List<ProjectRepoView> views(UUID projectId) {
        List<ProjectRepo> rows = repositories.findByProjectIdOrderByPositionAsc(projectId);
        int lowest = rows.isEmpty() ? -1 : rows.get(0).getPosition();
        return rows.stream().map(row -> ProjectRepoView.of(row, lowest)).toList();
    }

    private int lowestPositionOf(UUID projectId) {
        List<ProjectRepo> rows = repositories.findByProjectIdOrderByPositionAsc(projectId);
        return rows.isEmpty() ? -1 : rows.get(0).getPosition();
    }

    /**
     * The rules, in one method. {@code uq_project_repository_name} is the real guarantee; this is
     * what turns the collision into a field error instead of a duplicate-key 500.
     */
    private static Map<String, List<String>> validate(
            ProjectRepoInput input, List<ProjectRepo> existing, UUID editing) {

        Map<String, List<String>> errors = new LinkedHashMap<>();
        if (input == null) {
            errors.put("name", List.of("Send the repository's details."));
            return errors;
        }

        String name = required(errors, "name", input.name(), MAX_NAME,
                "Give the repository a name, so it can be told apart in the list.");
        required(errors, "url", input.url(), MAX_URL,
                "Give the repository's clone URL — it is what somebody is told to check out.");
        required(errors, "defaultBranch", input.defaultBranch(), MAX_BRANCH,
                "Name the branch work starts from.");
        required(errors, "subdirectory", input.subdirectory(), MAX_SUBDIRECTORY,
                "Give the working subdirectory, or \"/\" for the repository root.");
        required(errors, "buildCommand", input.buildCommand(), MAX_BUILD_COMMAND,
                "Give the command that verifies this repository. One per repository, never one per "
                        + "project: a wrapper says \"the build broke\" without saying which stack.");

        if (name != null && existing.stream()
                .filter(row -> !row.getId().equals(editing))
                .anyMatch(row -> row.getName().equalsIgnoreCase(name))) {

            errors.put("name", List.of("This project already has a repository called \"" + name
                    + "\". Two rows with one name is two rows nobody can tell apart."));
        }
        return errors;
    }

    private static String required(
            Map<String, List<String>> errors, String field, String value, int max, String missing) {

        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            errors.put(field, List.of(missing));
            return null;
        }
        if (trimmed.length() > max) {
            errors.put(field, List.of("Keep this to " + max + " characters or fewer."));
            return null;
        }
        return trimmed;
    }
}
