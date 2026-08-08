package com.tcms.documentation;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The documentation space (plan §6i) — the {@code documentation} module's only controller.
 *
 * <p>The caller comes from the token and the company from the context, neither as a parameter, for
 * the reasons {@code ProjectController} sets out: a {@code userId} parameter answers "what may
 * <em>that</em> user see", and a {@code companyId} parameter lets request input select the tenancy
 * discriminator — the one thing {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD §0.1
 * point 2).
 *
 * <h2>404, never 403, for anything about reachability</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller holds
 * no grant on all answer <strong>404 with no body</strong>. A 403 would confirm the id names
 * something real, which makes the endpoint an oracle for another client's ids (§6t). The 403s below
 * are only ever about a permission on a project the caller can already read.
 *
 * <h2>{@code ProblemDetail} rather than a fourth hand-rolled envelope</h2>
 *
 * <p>{@code repository.ApiProblem} is package-private and its own header records that the second
 * copy ({@code InvitationController.ProblemBody}) should have been extracted to a {@code shared}
 * module. This module does not make it a third: {@code shared} would be a fourth foundation module,
 * which {@code ModuleStructureTest} treats as a decision rather than an import, and that decision is
 * not this slice's to take quietly. Spring's own {@code ProblemDetail} serializes to the same RFC
 * 7807 shape the contract's {@code Problem} declares, with {@code errors} added as a property — the
 * call {@code work.WorkItemController} already made.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/doc-pages")
public class DocPageController {

    /**
     * The header carrying one <em>user intent</em> to create, so a retry is not a second page.
     *
     * <p>Spelled here rather than imported: {@code CaseController} declares the same constant and
     * lives in another module, which may not be imported from this one. Not on {@code HttpHeaders}
     * either — it is an IETF draft header, not an RFC 9110 one.
     */
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final DocPageService pages;
    private final BlockLinkService blockLinks;
    private final DocPageWriteService writes;

    public DocPageController(
            DocPageService pages, BlockLinkService blockLinks, DocPageWriteService writes) {

        this.pages = pages;
        this.blockLinks = blockLinks;
        this.writes = writes;
    }

    /**
     * The project's pages, as a flat list carrying {@code parentId} — the client builds the tree.
     *
     * <p>{@code []} means the project has no pages, which is the state of every freshly provisioned
     * one. Nothing seeds a first page: inventing a client's specification is the same mistake as
     * seeding their Feature tree.
     */
    @GetMapping
    public ResponseEntity<List<DocPageSummaryView>> tree(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return pages.findTree(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** One page, with its blocks in display order. */
    @GetMapping("/{pageId}")
    public ResponseEntity<DocPageView> page(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID pageId) {

        return pages.findPage(UUID.fromString(jwt.getSubject()), projectId, pageId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Creates a page.
     *
     * <p>{@code key} and {@code path} are server-owned: the key from the company's counter
     * ({@code DOC-n}, §6t) and the path derived from it rather than from the title, so a retitle
     * never rewrites a path.
     *
     * <p>Always {@code DRAFT}. Publishing is {@link #status} and a different permission.
     *
     * <p>{@code Idempotency-Key} arrives with {@code V42}, the column that honours it, and never
     * before — §54's rule. The header identifies one <em>intent</em>, not one attempt; a replay
     * answers 200 with the page that intent made. It matters more here than anywhere else the
     * header is offered, because a duplicate page splits traceability across two sets of block ids
     * — see {@link DocPageWriteService#create}.
     */
    @PostMapping
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody DocPageInput input) {

        DocPageWriteService.Outcome outcome = writes.create(
                UUID.fromString(jwt.getSubject()), projectId, input, trimToNull(idempotencyKey));

        return switch (outcome) {
            case DocPageWriteService.Outcome.Created created -> ResponseEntity
                    .created(location(projectId, created.view().id()))
                    .body(created.view());
            // 200, and no Location: the resource was not created by this request, and a client that
            // followed a Location on a replay would read it as having made something.
            case DocPageWriteService.Outcome.Replayed replayed ->
                    ResponseEntity.ok(replayed.view());
            default -> problem(outcome, "create documentation pages in this project");
        };
    }

    /**
     * Retitles a page and replaces its blocks.
     *
     * <p>{@code If-Match} carries the {@code lockVersion} the client read. A missing header is
     * last-write-wins, which is right for an importer with no prior read; the SPA always sends it.
     *
     * <p>The 409 has two causes and they are told apart in {@code detail}: a stale {@code If-Match},
     * and an edit that would remove a block a case still links to. The second is the one worth
     * reading — see {@link PageContent}.
     */
    @PatchMapping("/{pageId}")
    public ResponseEntity<Object> edit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID pageId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody DocPageInput input) {

        Long expected;
        try {
            expected = parseIfMatch(ifMatch);
        } catch (NumberFormatException notAVersion) {
            // 400 rather than 409: answering 409 would send a client with a malformed header into a
            // reload-and-retry loop that cannot succeed.
            return ResponseEntity.badRequest().body(validationProblem(Map.of(
                    "If-Match",
                    List.of("Send the page's lockVersion as the If-Match header, or omit the "
                            + "header."))));
        }

        DocPageWriteService.Outcome outcome = writes.edit(
                UUID.fromString(jwt.getSubject()), projectId, pageId, input, expected);

        return switch (outcome) {
            case DocPageWriteService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            default -> problem(outcome, "edit documentation pages in this project");
        };
    }

    /**
     * Publishes, unpublishes or archives a page.
     *
     * <p>403 here is {@code doc:publish}, a different permission from the one the other three writes
     * ask: a Test Engineer may write pages and may not publish them (roles spec §A2). So this is the
     * one endpoint in the module where a caller who just successfully saved a page can be refused,
     * which is the intended shape rather than an inconsistency.
     */
    @PutMapping("/{pageId}/status")
    public ResponseEntity<Object> status(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID pageId,
            @RequestBody DocPageStatusInput input) {

        DocPageWriteService.Outcome outcome =
                writes.setStatus(UUID.fromString(jwt.getSubject()), projectId, pageId, input);

        return switch (outcome) {
            case DocPageWriteService.Outcome.Updated updated -> ResponseEntity.ok(updated.view());
            default -> problem(outcome, "publish documentation pages in this project");
        };
    }

    /** Soft-deletes a page that has no children and no links into it. */
    @DeleteMapping("/{pageId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID pageId) {

        DocPageWriteService.Outcome outcome =
                writes.delete(UUID.fromString(jwt.getSubject()), projectId, pageId);

        return switch (outcome) {
            case DocPageWriteService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            default -> problem(outcome, "delete documentation pages in this project");
        };
    }

    /**
     * Links a case or suite to a block.
     *
     * <p>Two success codes, and the distinction is the endpoint's design: 201 when a link was
     * written, 200 when one was already there. A client that treats them alike is correct; one that
     * counts creates is not misled.
     */
    @PostMapping("/{pageId}/blocks/{blockId}/links")
    public ResponseEntity<Object> link(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID pageId,
            @PathVariable UUID blockId,
            @RequestBody BlockLinkInput input) {

        BlockLinkService.Outcome outcome = blockLinks.link(
                UUID.fromString(jwt.getSubject()), projectId, pageId, blockId, input);

        return switch (outcome) {
            case BlockLinkService.Outcome.Created created ->
                    ResponseEntity.status(HttpStatus.CREATED).body(created.view());
            case BlockLinkService.Outcome.Existing existing -> ResponseEntity.ok(existing.view());
            default -> linkProblem(outcome, "link test cases to documentation in this project");
        };
    }

    /** Removes a link. Hard, not soft — see {@link BlockLinkService}. */
    @DeleteMapping("/{pageId}/links/{linkId}")
    public ResponseEntity<Object> unlink(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID pageId,
            @PathVariable UUID linkId) {

        BlockLinkService.Outcome outcome =
                blockLinks.unlink(UUID.fromString(jwt.getSubject()), projectId, pageId, linkId);

        return switch (outcome) {
            case BlockLinkService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            default -> linkProblem(outcome, "unlink test cases from documentation in this project");
        };
    }

    /**
     * The failure half of both link writes.
     *
     * <p>A second mapper rather than a widened first one: {@link BlockLinkService.Outcome} and
     * {@link DocPageWriteService.Outcome} are different sealed hierarchies, and collapsing them
     * would mean one of the two carrying states the other cannot produce — a {@code Stale} on a link
     * write, a {@code WouldBreak} on a page's. An arm that cannot be reached is an arm nobody
     * maintains. The two agree where it matters, which is that an unreachable anything is a bodiless
     * 404 (§6t).
     */
    private static ResponseEntity<Object> linkProblem(
            BlockLinkService.Outcome outcome, String action) {

        return switch (outcome) {
            case BlockLinkService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case BlockLinkService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(forbiddenProblem("You do not have permission to " + action + "."));
            case BlockLinkService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case BlockLinkService.Outcome.Created ignored ->
                    throw new IllegalStateException("A created link was mapped as a problem");
            case BlockLinkService.Outcome.Existing ignored ->
                    throw new IllegalStateException("An existing link was mapped as a problem");
            case BlockLinkService.Outcome.Deleted ignored ->
                    throw new IllegalStateException("A deleted link was mapped as a problem");
        };
    }

    /**
     * The failure half of all four writes, mapped once.
     *
     * <p>Four copies of this switch is four places a status code can drift apart, and the drift that
     * matters is the 404: a create answering 403 where an edit answers 404 for the same unreachable
     * project turns the pair into an oracle (§6t).
     *
     * @param action the sentence fragment naming what was refused, so the 403 reads as the user's
     *               own attempt rather than as a permission key (§32)
     */
    private static ResponseEntity<Object> problem(
            DocPageWriteService.Outcome outcome, String action) {

        return switch (outcome) {
            case DocPageWriteService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case DocPageWriteService.Outcome.Forbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(forbiddenProblem("You do not have permission to " + action
                                    + "."));
            case DocPageWriteService.Outcome.Invalid invalid ->
                    ResponseEntity.badRequest().body(validationProblem(invalid.errors()));
            case DocPageWriteService.Outcome.Stale ignored ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(staleProblem());
            case DocPageWriteService.Outcome.WouldBreak refused ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(wouldBreakProblem(refused));
            // The four success shapes. Reached only if a call site above routes one here — each
            // handles its own success and delegates the rest — so these arms exist to satisfy
            // exhaustiveness, not as a fallback that quietly renders a created page as an error.
            case DocPageWriteService.Outcome.Created ignored ->
                    throw new IllegalStateException("A created page was mapped as a problem");
            case DocPageWriteService.Outcome.Replayed ignored ->
                    throw new IllegalStateException("A replayed page was mapped as a problem");
            case DocPageWriteService.Outcome.Updated ignored ->
                    throw new IllegalStateException("An updated page was mapped as a problem");
            case DocPageWriteService.Outcome.Deleted ignored ->
                    throw new IllegalStateException("A deleted page was mapped as a problem");
        };
    }

    /**
     * The refusal that protects traceability, and the most user-facing sentence in this module.
     *
     * <p>It says what the link <em>is</em> rather than restating the rule, because a user who tried
     * to delete a paragraph does not know that a case elsewhere in the product points at it. The
     * count is there for the same reason a suite's delete names its case count: "1 case" and "14
     * cases" are different decisions and the user cannot see either from here.
     */
    private static ProblemDetail wouldBreakProblem(
            DocPageWriteService.Outcome.WouldBreak refused) {

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        if (refused.childPages() > 0) {
            problem.setTitle("Page is not empty");
            problem.setDetail("This page has " + refused.childPages()
                    + (refused.childPages() == 1 ? " child page" : " child pages")
                    + ". Move or delete them first.");
            return problem;
        }
        problem.setTitle("Something still links here");
        problem.setDetail(refused.linkedCases()
                + (refused.linkedCases() == 1 ? " link points" : " links point")
                + " at content you are removing, and deleting it would break them silently. "
                + "Remove the links first, or archive the page instead — an archived page keeps "
                + "its links resolvable.");
        return problem;
    }

    private static ProblemDetail staleProblem() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Someone else saved first");
        problem.setDetail("Reload the page to see the newer version, then reapply your change.");
        return problem;
    }

    /**
     * Package-private rather than private since #49: {@link DocPageAttachmentController} is a second
     * controller in this module and must answer with the same envelope. A copy there would be the
     * fourth hand-rolled problem body in the product, which is the exact thing this module's header
     * declines to become.
     */
    static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        // The SPA renders the per-field messages beside the inputs, so this line must not repeat
        // them — it says where to look, and the fields say what is wrong.
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty("errors", new HashMap<>(errors));
        return problem;
    }

    /** Package-private for {@link #validationProblem}'s reason. */
    static ProblemDetail forbiddenProblem(String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail(detail);
        return problem;
    }

    /**
     * A genuine write race, answered as a 409 rather than a 500 — {@code CaseController#onWriteRace}
     * carries the full account of why this is a handler rather than a {@code catch} in the service.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> onWriteRace(OptimisticLockingFailureException race) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(staleProblem());
    }

    /**
     * The created page's own URL, built from this controller's mapping rather than from a
     * request-relative builder: {@code fromCurrentRequest} would echo whatever host or proxy prefix
     * the caller sent, which is a header-controlled value in a response.
     */
    private static URI location(UUID projectId, UUID pageId) {
        return URI.create("/api/v1/projects/" + projectId + "/doc-pages/" + pageId);
    }

    /**
     * Blank is the same as absent for {@code Idempotency-Key}.
     *
     * <p>{@code Idempotency-Key: } (empty) would otherwise be stored as {@code ''} and become a key
     * every other empty-header request in the project replays against — one page, permanently, for
     * every caller that sends the header wrong. {@code CaseController.trimToNull} is the same
     * method; it is copied rather than shared for {@link #IDEMPOTENCY_KEY}'s reason.
     */
    private static String trimToNull(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Reads {@code If-Match}, tolerating the quoted form — {@code CaseController} explains both. */
    private static Long parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return Long.valueOf(value);
    }
}
