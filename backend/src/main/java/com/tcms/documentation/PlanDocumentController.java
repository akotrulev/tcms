package com.tcms.documentation;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
 * <p>Two verbs and no more. Editing is {@code PATCH /doc-pages/{pageId}} and deleting is
 * {@code DELETE /doc-pages/{pageId}}, because the document is an ordinary page and a second write
 * path onto {@code doc_block} is the last thing this module needs — see {@link PlanDocumentService}.
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

    /** Starts the document from the template. */
    @PostMapping
    public ResponseEntity<Object> start(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId) {

        PlanDocumentService.Outcome outcome =
                documents.create(UUID.fromString(jwt.getSubject()), projectId, planId);

        return switch (outcome) {
            case PlanDocumentService.Outcome.Created created -> ResponseEntity
                    // The page's own URL, not this one: the client edits it as a page from here on,
                    // and pointing Location back at this route would send it to a resolver it has
                    // no further use for.
                    .created(URI.create(
                            "/api/v1/projects/" + projectId + "/doc-pages/" + created.view().id()))
                    .body(created.view());
            case PlanDocumentService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case PlanDocumentService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(forbidden());
            case PlanDocumentService.Outcome.AlreadyExists existing ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(alreadyExists(existing));
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
        problem.setDetail("This plan's document is " + existing.key() + ". Open it instead — "
                + "starting a second one would replace what is already written.");
        problem.setProperty("pageId", existing.pageId());
        return problem;
    }
}
