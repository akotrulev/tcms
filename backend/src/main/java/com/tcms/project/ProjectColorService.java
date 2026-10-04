package com.tcms.project;

import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Setting the colour a project's key chip is drawn in (023 D17).
 *
 * <p>Gated on {@code project:settings} by key, the one {@code PUT /projects/{projectId}/client}
 * checks, and a no-op when the project already has the colour: nothing is written and no audit row
 * is added, as {@link ProjectArchiveService} does for the same reason.
 */
@Service
public class ProjectColorService {

    private static final String PROJECT_SETTINGS = "project:settings";

    private final ProjectService projects;
    private final ProjectRepository repository;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public ProjectColorService(
            ProjectService projects,
            ProjectRepository repository,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.repository = repository;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What the request did. Sealed, so the controller's switch is exhaustive under {@code -Werror}. */
    public sealed interface Outcome {

        record NotFound() implements Outcome {
        }

        record Forbidden() implements Outcome {
        }

        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Updated(ProjectView view) implements Outcome {
        }
    }

    @Transactional
    public Outcome set(UUID userId, UUID projectId, ProjectColorRequest request) {
        String color = request == null ? null : request.color();
        if (color == null || !Project.COLORS.contains(color)) {
            return new Outcome.Invalid(Map.of(
                    "color", List.of("Choose one of the project colours.")));
        }

        var gated = projects.writeGranted(userId, projectId, PROJECT_SETTINGS, project ->
                apply(userId, project, color));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(UUID userId, Project project, String color) {
        if (!color.equals(project.getColor())) {
            project.recolor(color);
            repository.saveAndFlush(project);

            auditEvents.save(AuditEvent.record(
                    project.getId(),
                    userId,
                    "project.recolored",
                    "project",
                    List.of(project.getId()),
                    "{\"key\":\"" + project.getKey() + "\",\"color\":\"" + color + "\"}",
                    clock.instant()));
        }
        return new Outcome.Updated(projects.viewOfChanged(project, userId));
    }
}
