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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * <p>They are one controller because they are one contract tag and one gate. The gate itself lives
 * in {@link ProjectService#readVisible} and its write siblings, and is <em>called</em>, never
 * restated — a project-scoped endpoint that answers "may they?" for itself is how the two
 * implementations diverge (plan §6b).
 *
 * <p>The three classification-value writes are the exception that proves it: their permission is
 * not a constant here either, but resolved from the dimension row by
 * {@link ClassificationValueWriteService}, through {@link ProjectService#writeGrantedOnTarget}.
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
    private final ClassificationValueWriteService classificationValues;

    public ProjectController(
            ProjectService projects,
            ProjectConfigurationService configuration,
            ProjectCreateService creates,
            ClassificationValueWriteService classificationValues) {

        this.projects = projects;
        this.configuration = configuration;
        this.creates = creates;
        this.classificationValues = classificationValues;
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
     * Adds a value to one of this project's classification dimensions.
     *
     * <p>The gate is {@link ClassificationValueWriteService}'s and depends on the dimension: the
     * project's own Feature tree needs {@code project:settings}, the company's three shared axes
     * need {@code company:vocabulary} at company scope. A dimension this project does not have is a
     * 404 rather than a 403 — see that class, and {@link ProjectService#writeGrantedOnTarget}.
     */
    @PostMapping("/{projectId}/classification/values")
    public ResponseEntity<Object> createClassificationValue(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody ClassificationValueInput request) {

        return classificationValue(
                classificationValues.create(UUID.fromString(jwt.getSubject()), projectId, request));
    }

    /** Renames or recolours a value. A differing parent or dimension is refused, not ignored. */
    @PutMapping("/{projectId}/classification/values/{valueId}")
    public ResponseEntity<Object> updateClassificationValue(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID valueId,
            @RequestBody ClassificationValueInput request) {

        return classificationValue(classificationValues.update(
                UUID.fromString(jwt.getSubject()), projectId, valueId, request));
    }

    /** Retires a value. Soft, and refused while the value still has live children. */
    @DeleteMapping("/{projectId}/classification/values/{valueId}")
    public ResponseEntity<Object> retireClassificationValue(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID valueId) {

        return classificationValue(
                classificationValues.retire(UUID.fromString(jwt.getSubject()), projectId, valueId));
    }

    /**
     * One mapping for all three vocabulary writes, so a state cannot be handled in two of them and
     * defaulted in the third — {@code SuiteWriteService.gated}'s reason, at the HTTP layer.
     */
    private static ResponseEntity<Object> classificationValue(
            ClassificationValueWriteService.Outcome outcome) {

        return switch (outcome) {
            case ClassificationValueWriteService.Outcome.Created created -> ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(created.view());
            case ClassificationValueWriteService.Outcome.Updated updated ->
                    ResponseEntity.ok(updated.view());
            case ClassificationValueWriteService.Outcome.Retired ignored ->
                    ResponseEntity.noContent().build();
            case ClassificationValueWriteService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case ClassificationValueWriteService.Outcome.HasChildren blocked ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(childrenProblem(blocked));
            case ClassificationValueWriteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(vocabularyForbiddenProblem());
            case ClassificationValueWriteService.Outcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
        };
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

    /**
     * One sentence for both vocabulary gates, deliberately.
     *
     * <p>Naming which of the two the caller failed — "this is a company vocabulary" versus "this is
     * the project's" — would be more helpful and would also tell a Test Lead exactly which axis
     * belongs to whom, which is a fact about the company's configuration reached through a refusal.
     * The actionable half is the same either way: ask an administrator.
     */
    private static ProblemDetail vocabularyForbiddenProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("You do not have permission to edit this classification vocabulary.");
        return problem;
    }

    /**
     * The refusal to retire a value that still has children, with a sentence on {@code errors}.
     *
     * <p><s>The count is on the problem rather than in the sentence because the SPA renders it —
     * "3 values sit under this one" beside a tree it can expand — and a number embedded in prose
     * is one a client has to parse back out. {@code SuiteWriteService}'s {@code NotEmpty} does the
     * same for a folder.</s> → <strong>superseded §149. Both halves of that were wrong.</strong>
     *
     * <p>{@code SuiteWriteService}'s {@code NotEmpty} does the <em>opposite</em>: it puts the count
     * in the sentence, on {@code detail}, and writes no {@code errors} at all — so the precedent
     * this cited as agreement was the one site that disagreed with it. The reason it matters is the
     * other half: <strong>an {@code errors} value is a message, and two clients print it
     * verbatim.</strong> {@code @tcms/cli} prints {@code field: message} under the failure and
     * {@code @tcms/mcp} hands the same lines to a model, so this arm told a model
     * {@code children: 3} — a key naming no argument and a value that is not a sentence — where
     * §141's rule is that a refusal must say what to do next. The SPA was the only reader that
     * could turn it back into English, and only because that one dialog knew which of the two
     * conventions this endpoint used.
     *
     * <p>The singular/plural pair is {@link com.tcms.identity.TeamController}'s, which has always
     * sent sentences under the same {@code children} key. One key, one meaning.
     */
    private static ProblemDetail childrenProblem(
            ClassificationValueWriteService.Outcome.HasChildren blocked) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Still in use");
        problem.setDetail("Retire the values underneath this one first.");
        problem.setProperty("errors", Map.of(
                "children", List.of(blocked.children() == 1
                        ? "1 value sits under this one. Retire it first — retiring a whole branch "
                                + "at once would unclassify every case beneath it with nothing on "
                                + "screen naming them."
                        : blocked.children() + " values sit under this one. Retire those first — "
                                + "retiring a whole branch at once would unclassify every case "
                                + "beneath it with nothing on screen naming them.")));
        return problem;
    }
}
