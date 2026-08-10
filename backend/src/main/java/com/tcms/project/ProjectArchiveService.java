package com.tcms.project;

import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retiring a project, and bringing it back — <strong>the operation {@code project.archived_at} has
 * been waiting for since {@code V1}</strong> (§156's finding, §159's slice).
 *
 * <h2>What archiving means here, stated because a reader will otherwise assume the stronger form</h2>
 *
 * <p>It <strong>marks</strong> a project. It does not lock it. An archived project is still listed
 * by {@code GET /projects}, still readable, and every write in the contract still succeeds against
 * it. That is deliberately {@code DocPageStatus.ARCHIVED} one level up — a state that takes
 * something out of circulation while keeping it, and everything pointing at it, resolvable — and it
 * is not a soft delete: {@code deleted_at} is a different column with a different meaning, and this
 * one is reversible by the person who set it.
 *
 * <p>The stronger form — <em>an archived project refuses writes</em> — was considered and is a
 * separate decision rather than a piece of this one. Every project-scoped write in the application
 * passes through {@link ProjectService#writeGranted} and its three siblings, so the enforcement
 * point exists and is single; what a refusal would need is a fourth {@link GateResult} arm handled
 * at <em>53 call sites</em>, and that type's own header names the failure mode of doing it
 * carelessly (a fourth outcome handled in two endpoints of three). More importantly it is a product
 * rule about what a retired project is for, and inheriting it as a side effect of giving a column a
 * writer would be deciding it without noticing.
 *
 * <h2>Idempotent, and the no-op is silent all the way down</h2>
 *
 * <p>Archiving an archived project answers 200 with the project and writes <em>nothing</em>: no
 * timestamp, no audit row, no {@code lock_version} bump. Two administrators agreeing is not a
 * conflict ({@code DocPageWriteService#setStatus}'s wording), and an audit row per repeated click
 * buries the change that actually happened. The state is compared before either mutator is called,
 * which is also why {@link Project#archive} does not defend itself — see its javadoc.
 */
@Service
public class ProjectArchiveService {

    /**
     * Roles spec §A3: the project's own configuration, held per project by Test Lead and up.
     *
     * <p>Not the COMPANY-scope {@code project:create} that the create beside it needs. Three
     * reasons, and the third is the one that decided it:
     *
     * <ul>
     *   <li>Archiving destroys nothing and is reversible by the same key, so it is not the
     *       counterweight to a create; it is the largest of the project's settings.</li>
     *   <li>Company Admin and Instance Admin hold {@code project:settings} on <em>every</em>
     *       project through their company-wide grant, so gating on the project key withholds
     *       nothing from the roles a company key would have named.</li>
     *   <li>{@link ProjectService#createGranted} has <strong>no {@code Unreachable} arm</strong> —
     *       correctly, because a create names no target. Gating this on a company key would mean
     *       answering 403 for a project id the caller cannot see, which is the oracle §6t exists to
     *       prevent, one gate over.</li>
     * </ul>
     *
     * <p>Roles spec §A6a withholds this key from both token-only roles, so <strong>no CI credential
     * and no agent can retire a project</strong> — a property of the catalogue rather than of this
     * class, and the reason no extra check is written here.
     */
    private static final String PROJECT_SETTINGS = "project:settings";

    private static final String AUDIT_TARGET = "project";

    private final ProjectService projects;
    private final ProjectRepository repository;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public ProjectArchiveService(
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

        /** Unreachable project — one bodiless 404, for all three reasons alike (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code project:settings} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** 400, on the field. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** 200 with the project as the picker renders it — whether or not the state moved. */
        record Updated(ProjectView view) implements Outcome {
        }
    }

    /**
     * Sets the project's archived state to the one the request names.
     *
     * @param request the target state. A missing body or a missing {@code archived} field is a field
     *                error and not a default: defaulting either way turns a malformed request into a
     *                lifecycle change nobody asked for, and both directions have a caller who would
     *                be astonished.
     */
    @Transactional
    public Outcome set(UUID userId, UUID projectId, ProjectArchiveRequest request) {
        if (request == null || request.archived() == null) {
            return new Outcome.Invalid(Map.of(
                    "archived", List.of("Say whether the project should be archived: true or false.")));
        }
        boolean target = request.archived();

        var gated = projects.writeGranted(userId, projectId, PROJECT_SETTINGS, project ->
                apply(userId, project, target));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private Outcome apply(UUID userId, Project project, boolean target) {
        if (project.isArchived() == target) {
            // The no-op, decided before anything is written — see the class header. The view is
            // still assembled and returned, so a client that pressed the button twice gets the same
            // answer both times rather than having to treat one of them as a special case.
            return new Outcome.Updated(projects.viewOfChanged(project, userId));
        }

        Instant now = clock.instant();
        if (target) {
            project.archive(now);
        } else {
            project.unarchive();
        }
        repository.saveAndFlush(project);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                target ? "project.archived" : "project.unarchived",
                AUDIT_TARGET,
                List.of(project.getId()),
                // The key rather than the name: an audit row is read long after the fact and the
                // name may have been edited since, while the key is immutable (Project.key). No
                // escaping question either, which is why this one is not built through
                // AuditEvent.jsonString — the column's CHECK format allows only [A-Z0-9]{2,10}.
                "{\"key\":\"" + project.getKey() + "\",\"archived\":" + target + "}",
                now));

        return new Outcome.Updated(projects.viewOfChanged(project, userId));
    }
}
