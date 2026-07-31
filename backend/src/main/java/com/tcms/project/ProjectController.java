package com.tcms.project;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The project list, one project, and that project's two vocabularies — the first endpoints past
 * authentication.
 *
 * <p>Everything the SPA does after sign-in starts here: the picker calls
 * {@code GET /projects}, and every other route is scoped under a project id it chose from
 * that response.
 *
 * <p>All four are one controller because all four are one contract tag and one gate. The gate
 * itself lives in {@link ProjectService#readVisible} and is <em>called</em>, never restated — a
 * project-scoped endpoint that answers "may they?" for itself is how the two implementations
 * diverge (plan §6b).
 *
 * <h2>The caller comes from the token, and the company from the context</h2>
 *
 * <p>Neither is a parameter, and both would be convenient as one. A {@code userId} parameter
 * would answer "what may <em>that</em> user see" — the bypass {@code MeController} documents.
 * A {@code companyId} parameter would let request input select the tenancy discriminator,
 * which is the single thing {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD
 * §0.1 point 2). The company is established by {@code CompanyContextFilter} from the session's
 * {@code co} claim before this controller runs.
 *
 * <h2>404, never 403</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller
 * holds no grant on all answer <strong>404 with no body</strong>. A 403 would confirm the id
 * names something real, which makes the endpoint an oracle for another client's project ids
 * (§6t, handoff §3 lesson 13). {@link ProjectService#findVisible} collapses the three cases
 * into an empty {@link java.util.Optional} so this method has nothing to un-collapse — the
 * same arrangement as {@code InvitationController}.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projects;
    private final ProjectConfigurationService configuration;
    private final ProjectCreateService creates;

    public ProjectController(
            ProjectService projects,
            ProjectConfigurationService configuration,
            ProjectCreateService creates) {

        this.projects = projects;
        this.configuration = configuration;
        this.creates = creates;
    }

    /**
     * Projects visible to the caller — possibly none.
     *
     * <p>An empty array is a correct and expected answer, not an error: a member invited to a
     * company and granted no project yet sees exactly this. The SPA renders it as the "no
     * projects" empty state rather than as a failure.
     */
    @GetMapping
    public List<ProjectView> list(@AuthenticationPrincipal Jwt jwt) {
        return projects.listVisible(UUID.fromString(jwt.getSubject()));
    }

    /**
     * Creates a project — <strong>the first write on this controller, and the only one in the
     * application whose gate is not a grant on its target.</strong>
     *
     * <h2>Why there is no 404 here</h2>
     *
     * <p>Every other write answers 404 for an unreachable target and 403 for a readable one it may
     * not change, and the order matters because telling the two apart is an oracle (§6t). This
     * request names no target: a caller without {@code project:create} has learned nothing from the
     * 403 except a fact about their own grants. So the two outcomes are 403 and 400, and adding a
     * 404 would mean inventing something for it to conceal.
     *
     * <h2>Why the errors are {@link ProblemDetail} and not a record</h2>
     *
     * <p>{@code repository.ApiProblem} and {@code InvitationController.ProblemBody} are two copies
     * of the RFC-7807 envelope already, and the first records in its own header that the right home
     * is a {@code shared} module which does not exist yet. A third copy would be the cost of that
     * postponement compounding. Spring's own {@link ProblemDetail} serializes to the same shape the
     * contract's {@code Problem} declares — {@code type}, {@code title}, {@code status},
     * {@code detail}, plus {@code errors} as a property — so this module needs no envelope of its
     * own and the eventual extraction has one fewer copy to reconcile.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt, @RequestBody ProjectWriteRequest request) {

        ProjectCreateService.Outcome outcome =
                creates.create(UUID.fromString(jwt.getSubject()), request);

        return switch (outcome) {
            case ProjectCreateService.Outcome.Created created -> ResponseEntity
                    .created(location(created.view().id()))
                    .body(created.view());
            case ProjectCreateService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case ProjectCreateService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbiddenProblem());
        };
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectView> detail(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return projects.findVisible(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The classification vocabularies that apply to this project (plan §6d).
     *
     * <p>Company-level and project-level dimensions in one array: Test Type, Test Level and
     * Execution Phase belong to the company so a client's projects stay comparable, Feature belongs
     * to the project. The distinction is deliberately invisible on the wire — a consumer picking
     * values has no use for it, and it is the field a future author would try to <em>send</em>.
     *
     * <p>{@code []} is a valid answer and means the project has no dimensions, which is what a
     * company provisioned before the vocabulary existed looks like. An unreachable project is a
     * 404, exactly as on {@link #detail} — same gate, same reason.
     */
    @GetMapping("/{projectId}/classification")
    public ResponseEntity<List<ClassificationDimensionView>> classification(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return configuration.findClassification(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * This project's configurable execution statuses — the six defaults, plus whatever it added.
     *
     * <p>Project-scoped, unlike the classification vocabularies above: two client products
     * legitimately differ in status vocabulary while "what counts as a Performance test" should not
     * (ERD §3). Reporting reads each status's {@code kind}, never its name.
     */
    @GetMapping("/{projectId}/statuses")
    public ResponseEntity<List<CaseStatusView>> statuses(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return configuration.findStatuses(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * A genuine race on {@code uq_project_key}, answered as the field error it would have been a
     * moment earlier rather than as a 500.
     *
     * <p>{@link ProjectCreateService} checks the key inside the write transaction, which closes the
     * window to the width of one statement and does not close it: two Company Admins submitting
     * {@code OLB} at the same instant both see no row and one of them loses at the constraint. The
     * loser's request is not a server fault — their input is simply now taken — so it gets the same
     * 400 on the same field, and the message reads identically whether it lost the race or arrived
     * a minute late.
     *
     * <p>Handled here rather than caught in the service for the reason {@code CaseController} gives
     * about its own race handler: the violation is thrown on <em>commit</em>, after the service
     * method has returned, so there is nothing inside it left to catch. Not covered by the suite —
     * reproducing it needs two interleaved transactions — which is stated rather than implied.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> onKeyRace(DataIntegrityViolationException violation) {
        return ResponseEntity.badRequest().body(validationProblem(Map.of(
                "key", List.of("Another project in this company already uses that key."))));
    }

    /**
     * The created project's own URL, built from this controller's mapping rather than from a
     * request-relative builder: {@code fromCurrentRequest} would echo whatever host or proxy prefix
     * the caller sent, which is a header-controlled value in a response.
     */
    private static URI location(UUID projectId) {
        return URI.create("/api/v1/projects/" + projectId);
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        // The SPA renders the per-field messages beside the inputs, so this line must not repeat
        // them — it says where to look, and the fields say what is wrong. Same sentence the
        // repository module's envelope uses, deliberately: two different ways of saying this is how
        // one product starts sounding like two.
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail forbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        // What they cannot do, in their words, and never the permission key: a key is not actionable
        // by someone who cannot grant it to themselves, and naming it publishes the catalogue to
        // exactly the caller who is not entitled to it.
        problem.setDetail("You do not have permission to create projects in this company.");
        return problem;
    }
}
