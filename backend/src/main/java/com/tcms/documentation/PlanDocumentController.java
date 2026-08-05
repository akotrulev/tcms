package com.tcms.documentation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A test plan's document — {@code /projects/{projectId}/plans/{planId}/document}.
 *
 * <h2>Why this route lives in `documentation` and not on the plan's own controller</h2>
 *
 * <p>The URL is under {@code /plans}, which makes it look like {@code execution}'s. It is not, and it
 * could not be: {@code execution} may not depend on {@code documentation} (plan §3, ArchUnit), so a
 * handler there could not assemble a {@link DocPageView}. Spring routes by annotation and not by
 * package, so the module that owns the response owns the endpoint, and the plan controller stays
 * unaware — which is the same arrangement that lets a plan be deleted without this module being
 * consulted.
 *
 * <p>Three verbs, and none of them touches content. {@code GET} resolves, {@code PUT} adopts a page
 * that already exists and {@code DELETE} releases it; editing is {@code PATCH /doc-pages/{pageId}}
 * and deleting the page itself is {@code DELETE /doc-pages/{pageId}}, because the document is an
 * ordinary page and a second write path onto {@code doc_block} is the last thing this module needs —
 * see {@link PlanDocumentService}.
 *
 * <p><s>{@code POST} starts the document from the five-section template.</s> → <strong>retired
 * 2026-08-05 (§97)</strong>, with {@code PlanDocumentTemplate}. The reasoning is on
 * {@link PlanDocumentService}; the shape of the replacement is that this module no longer creates
 * anything, so {@code DELETE} here cannot destroy a page and says so in its own javadoc.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/plans/{planId}/document")
public class PlanDocumentController {

    private final PlanDocumentService documents;

    public PlanDocumentController(PlanDocumentService documents) {
        this.documents = documents;
    }

    /**
     * The plan's document.
     *
     * <p>404 is the ordinary first answer — a plan is created long before anybody writes its
     * document — and is deliberately the same 404 an unreachable project gets. A client tells the
     * two apart by whether it could load the plan at all, which it already has.
     */
    @GetMapping
    public ResponseEntity<DocPageView> document(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId) {

        return documents.find(UUID.fromString(jwt.getSubject()), projectId, planId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Adopts an existing page as this plan's document.
     *
     * <p>{@code 200} and not {@code 201}: nothing was created. The body is the page as {@code GET}
     * would now answer it, and there is no {@code Location} header, because the page already had a
     * URL before this request and the client is usually holding it — it is what the picker chose.
     */
    @PutMapping
    public ResponseEntity<Object> linkPage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId,
            @RequestBody(required = false) PlanDocumentLinkInput input) {

        PlanDocumentService.Outcome outcome =
                documents.link(UUID.fromString(jwt.getSubject()), projectId, planId, input);

        return switch (outcome) {
            case PlanDocumentService.Outcome.Linked linked -> ResponseEntity.ok(linked.view());
            default -> problem(outcome);
        };
    }

    /**
     * Releases this plan's document.
     *
     * <p><strong>The page survives.</strong> This endpoint clears two columns; deleting the page is
     * {@code DELETE /doc-pages/{pageId}}, a different act with a different blast radius, and the
     * distinction is why the refusal for a plan with no document is a {@code 404} rather than a
     * {@code 204} — a client told "done" by an unlink that unlinked nothing has no way to notice it
     * was pointed at the wrong plan.
     */
    @DeleteMapping
    public ResponseEntity<Object> unlinkPage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId) {

        PlanDocumentService.Outcome outcome =
                documents.unlink(UUID.fromString(jwt.getSubject()), projectId, planId);

        return switch (outcome) {
            case PlanDocumentService.Outcome.Unlinked ignored -> ResponseEntity.noContent().build();
            default -> problem(outcome);
        };
    }

    /**
     * Every refusal both writes can produce, in one place.
     *
     * <p>Exhaustive over the sealed interface rather than a {@code default}: a fourth refusal added
     * to the service is a compile error here, which is what stops it reaching a client as a 500.
     */
    private static ResponseEntity<Object> problem(PlanDocumentService.Outcome outcome) {
        return switch (outcome) {
            case PlanDocumentService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case PlanDocumentService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbidden());
            case PlanDocumentService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validation(invalid.errors()));
            case PlanDocumentService.Outcome.AlreadyExists existing ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(alreadyExists(existing));
            case PlanDocumentService.Outcome.TakenByAnotherPlan taken ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(takenByAnotherPlan(taken));
            case PlanDocumentService.Outcome.Linked linked -> ResponseEntity.ok(linked.view());
            case PlanDocumentService.Outcome.Unlinked ignored ->
                    ResponseEntity.noContent().build();
        };
    }

    private static ProblemDetail forbidden() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        // Names the act, not the key (§32) — and names it as documentation, which is what the
        // permission actually is, so a reader who is refused knows which access to ask for.
        problem.setDetail("You do not have permission to write documentation in this project.");
        return problem;
    }

    private static ProblemDetail alreadyExists(PlanDocumentService.Outcome.AlreadyExists existing) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("This plan already has a document");
        // The key, because that is what the reader will see in the tree and in the tab they are
        // about to be sent to. The id is the machine-readable half and is a property rather than
        // prose.
        problem.setDetail("This plan's document is " + existing.key() + ". Unlink it first if you "
                + "mean to point this plan at a different page — swapping it silently is how a plan "
                + "comes to document a different release than its reader believes.");
        problem.setProperty("pageId", existing.pageId());
        return problem;
    }

    private static ProblemDetail takenByAnotherPlan(
            PlanDocumentService.Outcome.TakenByAnotherPlan taken) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("That page is already another plan's document");
        // Named where it can be named. A plan that has since been deleted still holds the page's
        // subject slot, and its key is still the most useful thing to say — but if the row cannot
        // be read at all, the sentence says so rather than printing "null" at somebody.
        problem.setDetail(taken.planKey() == null
                ? "That page is already the document of another plan. Unlink it there first."
                : "That page is already " + taken.planKey() + "'s document. Unlink it there first — "
                        + "one page cannot be two plans' requirements.");
        problem.setProperty("planId", taken.planId());
        return problem;
    }

    private static ProblemDetail validation(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Check the details");
        problem.setDetail("Some of what was sent could not be accepted.");
        problem.setProperty("errors", errors);
        return problem;
    }
}
