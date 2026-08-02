package com.tcms.project;

import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Creating a project — <strong>the last thing standing between a provisioned company and a usable
 * product.</strong>
 *
 * <p>Until this class existed, "provisioned company" meant "provisioned company <em>plus</em>
 * hand-written SQL": {@code provision-company} takes an optional first project and there was no
 * second path, so every company that wanted another one needed an operator with a psql prompt.
 * Three consecutive slices named it as the blocker and two of them skipped their live browser pass
 * for exactly this reason (handoff §31, §32).
 *
 * <h2>The gate is COMPANY-scope, and that is what makes this endpoint different from every other
 * write</h2>
 *
 * <p>Every other write in the application is gated on a permission held <em>on the project being
 * written to</em> — {@code case:edit} on this case's project, {@code suite:delete} on this folder's.
 * There is no project here yet, so the question has to be asked one level up:
 * {@link ProjectService#createGranted} asks {@link ProjectAccess#canDoInCompany}, which resolves
 * through the evaluator's company-wide term (SYSTEM + COMPANY) and deliberately not through the
 * union of the caller's project grants. Roles spec §A3 puts {@code project:create} with Company
 * Admin and Instance Admin only: "Test Lead → Company Admin — same powers, wider target, plus
 * company configuration and project creation."
 *
 * <h2>No grant is minted for the creator, and the reason is not laziness</h2>
 *
 * <p>The tempting move is to give the creator a PROJECT-scope grant so their new project is visible
 * to them. It is unnecessary and it is wrong:
 *
 * <ul>
 *   <li><strong>Unnecessary.</strong> Every route to {@code project:create} already carries
 *       {@code project:read} over every project in the company — a Company Admin's COMPANY-scope
 *       grant covers "every project, present and future" (that is what
 *       {@code CompanyProvisioningService} says when it creates one), and an Instance Admin's
 *       SYSTEM term covers everything everywhere. The new project is in their {@code GET /projects}
 *       on the next request, which {@code ProjectCreateEndpointTest} asserts over HTTP rather than
 *       leaving to this paragraph.</li>
 *   <li><strong>Wrong.</strong> A PROJECT-scope grant is what {@code myRole} reports, and handoff
 *       §22 settled that a Company Admin's {@code myRole} is null: they reach every project through
 *       a grant that names no project, and labelling one project with a role would state a fact
 *       about the company as though it were a fact about that project. Minting a grant here would
 *       make exactly one of their projects — the one they happened to create — claim a per-project
 *       role they do not hold.</li>
 * </ul>
 *
 * <p>That the two facts coincide is not luck, but it is also not enforced by any type, so it is
 * enforced by a test: {@code ProjectCreateEndpointTest.everyRoleThatMayCreateMayAlsoRead} derives
 * from the seeded catalogue that no role holds {@code project:create} without {@code project:read},
 * and fails the build the day one does. A catalogue-wide invariant needs a computed check rather
 * than a careful reading (handoff §6 lesson 9).
 *
 * <h2>What commits together, and why the seed call is inside the transaction</h2>
 *
 * <p>The project row, its seeded defaults and the audit row, in one transaction. A project that
 * exists without its six execution statuses, its Feature dimension and its Default configuration is
 * the half-provisioned company {@code CompanyProvisioningService}'s header describes at company
 * level: it fails later, in another subsystem, in a way that reads as a bug in that subsystem —
 * {@code test_run.configuration_id} is {@code NOT NULL} and there would be no Default to point it
 * at.
 */
@Service
public class ProjectCreateService {

    /**
     * Roles spec §A3, COMPANY scope. Company Admin and Instance Admin; deliberately not Test Lead.
     */
    private static final String CREATE_PROJECT = "project:create";

    private static final String AUDIT_ACTION = "project.created";
    private static final String AUDIT_TARGET = "project";

    /**
     * The same seed call {@code provision-company} makes for a first project.
     *
     * <p>{@code V2}'s own {@code COMMENT ON FUNCTION} names both callers — "called once per project,
     * <em>by project creation</em> and by provision-company for the first project" — so this is the
     * second and only other one. It is idempotent, refuses a project it cannot see, and raises
     * SQLSTATE 42501 unless {@code app.current_company} matches, which is what turns a context bug
     * here into a loud failure rather than rows the policy then hides.
     *
     * <p>The ERD's "provision-company calls them; nothing else should" (§10) is about not growing a
     * <em>second</em> definition of what a seeded project contains — inlining these inserts here
     * would be that, and would leave a CLI-created project and an API-created one subtly different
     * the first time one of the two lists changed. Calling the one function is the rule being kept,
     * not broken.
     */
    private static final String SEED_PROJECT_DEFAULTS =
            "SELECT tcms_seed_project_defaults(CAST(:companyId AS uuid), CAST(:projectId AS uuid))";

    private final ProjectService projects;
    private final ProjectRepository repository;
    private final ProjectSettingsService settings;
    private final AuditEventRepository auditEvents;
    private final EntityManager entityManager;
    private final Clock clock;

    public ProjectCreateService(
            ProjectService projects,
            ProjectRepository repository,
            ProjectSettingsService settings,
            AuditEventRepository auditEvents,
            EntityManager entityManager,
            Clock clock) {

        this.projects = projects;
        this.repository = repository;
        this.settings = settings;
        this.auditEvents = auditEvents;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** The caller does not hold {@code project:create} in this company. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The project row, its defaults and the audit entry all committed. */
        record Created(ProjectView view) implements Outcome {
        }
    }

    /**
     * Creates a project in the company the session is open on.
     *
     * <p><strong>The permission is checked before the payload is looked at at all.</strong> That
     * ordering is not tidiness: the duplicate-key check below reports whether a key is in use, and
     * running it for a caller who may not create projects would turn a 403 endpoint into a probe for
     * which keys their company has taken. Same reason {@code writeGrantedByAuthorship} refuses a
     * held-for-nothing caller before reading any row.
     *
     * @param userId the authenticated caller, from the token — never a parameter of the request
     */
    public Outcome create(UUID userId, ProjectWriteRequest request) {
        GateResult<Outcome> gated =
                projects.createGranted(userId, CREATE_PROJECT, () -> validateAndCreate(userId, request));

        return switch (gated) {
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            // createGranted has no unreachable state — there is no target to conceal (see its
            // javadoc). The arm exists so that adding one to GateResult is a compile error here
            // rather than a silently mishandled outcome.
            case GateResult.Unreachable<Outcome> ignored -> throw new IllegalStateException(
                    "createGranted answered Unreachable, which it has no target to answer about");
        };
    }

    /**
     * Runs inside {@code createGranted}'s transaction, so {@code app.current_company} is set and
     * every insert below is covered by the RLS {@code WITH CHECK} rather than merely intended to be.
     */
    private Outcome validateAndCreate(UUID userId, ProjectWriteRequest request) {
        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();

        // Company-scoped by the Hibernate filter and by RLS, so this asks "does MY company use this
        // key", which is the uniqueness uq_project_key actually enforces. A global check would
        // refuse a key because another client uses it — and would tell the caller so.
        String submittedKey = request.key() == null ? null : request.key().strip();
        boolean keyTaken = submittedKey != null && repository.existsByKey(submittedKey);

        ProjectEdit.Outcome validated = ProjectEdit.validateCreate(request, keyTaken);
        if (validated instanceof ProjectEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        ProjectEdit edit = ((ProjectEdit.Outcome.Valid) validated).edit();

        UUID projectId = UUID.randomUUID();
        // saveAndFlush, not save: tcms_seed_project_defaults refuses a project it cannot see, and a
        // native query is not the persistence context. Hibernate's AUTO flush mode would flush
        // ahead of the query anyway — the explicit call is what stops this depending on a flush
        // mode nobody set deliberately, which is the same survivor CompanyProvisioningService
        // records against the same call.
        Project project = repository.saveAndFlush(Project.create(
                projectId, companyId, edit.key(), edit.name(), edit.description(), now));

        seedDefaults(companyId, projectId);

        // The project's §6g policy row, on its defaults (V23). In the same transaction as the
        // project, so a project can never exist without one — which is what lets every consumer read
        // the settings as a row rather than as an Optional. ProjectSettingsService#effective
        // tolerates the row's absence anyway, and says why.
        settings.createFor(companyId, projectId, now);

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                List.of(projectId),
                // Escaped through the shared helper: a project named `Acme "Retail" \ Wealth`
                // concatenated raw produces a malformed JSONB document, the insert fails, the whole
                // create rolls back, and the error names the audit table rather than the name.
                "{\"key\":" + AuditEvent.jsonString(edit.key())
                        + ",\"name\":" + AuditEvent.jsonString(edit.name()) + "}",
                now));

        return new Outcome.Created(projects.viewOfCreated(project, userId));
    }

    /**
     * The Feature dimension, the six execution statuses and the Default configuration.
     *
     * <p>{@code getResultList()} rather than {@code executeUpdate()}: the function returns
     * {@code void}, which Postgres delivers as one row holding NULL — so it is a query. The same
     * distinction {@code CompanyProvisioningService} documents, for the same function.
     */
    private void seedDefaults(UUID companyId, UUID projectId) {
        entityManager.createNativeQuery(SEED_PROJECT_DEFAULTS)
                .setParameter("companyId", companyId.toString())
                .setParameter("projectId", projectId.toString())
                .getResultList();
    }
}
