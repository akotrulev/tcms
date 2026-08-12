package com.tcms.documentation;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A case's links to things outside this product — a range of lines in a repository, or a URL
 * (plan §6m; open question #26, resolved <em>build it</em> 2026-08-02 and built in §169).
 *
 * <h2>The path is a case's and the controller is {@code documentation}'s</h2>
 *
 * <p>{@code /projects/{id}/cases/{caseId}/external-links} sits under an aggregate
 * {@code repository} owns, and the row it writes is {@code doc_link}, which this module owns.
 * {@link PlanDocumentController} is the precedent and the same split: it serves
 * {@code /projects/{id}/plans/{planId}/document}, a path under {@code execution}'s aggregate, from
 * the module that owns the page.
 *
 * <p>The alternative — a {@code /doc-links} collection taking the case in the body — was refused
 * because it makes the case an argument rather than a location, and every gate in this product
 * resolves the project from the path. A URL that names what is being written to is also what makes
 * the 404 honest: an unreachable case and an unreachable project are the same bodiless answer here,
 * and a body-supplied id would have to answer 400 for one of them.
 *
 * <p>What this controller must never grow is a {@code GET}. A case's links are already served, on
 * the case itself, as {@code TestCase.relatedDocuments} — off {@code case_doc_link}, the read model
 * {@code repository} owns for exactly this reason. A read here would be a second answer to a
 * question already answered, from the other side of a module boundary, and the two would disagree
 * for as long as the mirror lagged.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/cases/{caseId}/external-links")
public class ExternalLinkController {

    private final ExternalLinkService links;

    public ExternalLinkController(ExternalLinkService links) {
        this.links = links;
    }

    /**
     * Links this case to a repository range or a URL.
     *
     * <p>Two success codes, as on the block link and for the same reason: 201 when a link was
     * written, 200 when the identical target was already linked. A client that treats them alike is
     * correct; one that counts creates is not misled.
     *
     * <p><strong>No {@code Location} header on the 201.</strong> §119 is the standing warning about
     * the opposite — an endpoint that answered 201 with a {@code Location} naming a resource that
     * 404s, for four sections. There is no {@code GET} here by design (see the class header), so
     * the honest answer is the body and no header.
     */
    @PostMapping
    public ResponseEntity<Object> link(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId,
            @RequestBody ExternalLinkInput input) {

        ExternalLinkService.Outcome outcome =
                links.link(UUID.fromString(jwt.getSubject()), projectId, caseId, input);

        return switch (outcome) {
            case ExternalLinkService.Outcome.Created created ->
                    ResponseEntity.status(HttpStatus.CREATED).body(created.view());
            case ExternalLinkService.Outcome.Existing existing -> ResponseEntity.ok(existing.view());
            default -> problem(outcome, "edit test cases in this project");
        };
    }

    /** Removes one of this case's external links. A hard delete — {@link ExternalLinkService}. */
    /**
     * Corrects what one of this case's links claims (§174).
     *
     * <p>The case end of the act {@code DocPageController.relabel} performs on the documentation
     * end, gated on {@code case:edit} rather than {@code doc:edit} for this controller's standing
     * reason. Both ends exist because §173 spent a slice removing exactly this asymmetry from the
     * withdrawal of the same rows — see {@link ExternalLinkService#relabel}.
     */
    @PatchMapping("/{linkId}")
    public ResponseEntity<Object> relabel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId,
            @PathVariable UUID linkId,
            @RequestBody LinkRelabelInput input) {

        ExternalLinkService.Outcome outcome =
                links.relabel(UUID.fromString(jwt.getSubject()), projectId, caseId, linkId, input);

        return switch (outcome) {
            case ExternalLinkService.Outcome.Relabelled done -> ResponseEntity.ok(done.view());
            default -> problem(outcome, "edit test cases in this project");
        };
    }

    @DeleteMapping("/{linkId}")
    public ResponseEntity<Object> unlink(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID caseId,
            @PathVariable UUID linkId) {

        ExternalLinkService.Outcome outcome =
                links.unlink(UUID.fromString(jwt.getSubject()), projectId, caseId, linkId);

        return switch (outcome) {
            case ExternalLinkService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            default -> problem(outcome, "edit test cases in this project");
        };
    }

    /**
     * The failure half of both writes.
     *
     * <p>{@link DocPageController}'s envelope helpers, called rather than copied — they are
     * package-private for precisely this, since #49 made {@code DocPageAttachmentController} the
     * second controller here. A fourth hand-rolled problem body is the thing this module's header
     * declines to become.
     *
     * <p>The action named in the 403 is <em>editing cases</em> and not writing documentation, which
     * is the whole point of {@link ExternalLinkService}'s gate: a reader who is refused must be told
     * which access to ask for, and being told to ask for documentation rights they do not need is
     * worse than being told nothing (§32).
     */
    private static ResponseEntity<Object> problem(
            ExternalLinkService.Outcome outcome, String action) {

        return switch (outcome) {
            case ExternalLinkService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case ExternalLinkService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(DocPageController.forbiddenProblem(
                                    "You do not have permission to " + action + "."));
            case ExternalLinkService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest()
                            .body(DocPageController.validationProblem(invalid.errors()));
            // Reached only if a call site above routes a success here; each handles its own.
            case ExternalLinkService.Outcome.Created ignored ->
                    throw new IllegalStateException("A created link was mapped as a problem");
            case ExternalLinkService.Outcome.Existing ignored ->
                    throw new IllegalStateException("An existing link was mapped as a problem");
            case ExternalLinkService.Outcome.Deleted ignored ->
                    throw new IllegalStateException("A deleted link was mapped as a problem");
            case ExternalLinkService.Outcome.Relabelled ignored ->
                    throw new IllegalStateException("A relabelled link was mapped as a problem");
        };
    }
}
