package com.tcms.tracker;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/issues/search} — the palette's issue group (021 D-j).
 *
 * <p>A controller of its own rather than a method on {@code IssueController}, for the reason
 * {@code CrossProjectSearchController} gives one module over: this read has no project segment, and
 * hanging it off a class whose every operation resolves a project would need a second, projectless
 * mapping on a class whose whole shape is "one project's issues".
 *
 * <p><strong>Declared before {@code /issues/&#123;key&#125;} would be an issue</strong> — it is
 * not, because that mapping lives on another class and Spring resolves a literal segment ahead of a
 * path variable across classes. Said out loud because the reverse is the trap the documentation
 * controller records paying for.
 *
 * <p>No 403 arm: {@code issue:read} is what {@code readAllVisible} already answers, per project,
 * and a caller who holds it nowhere gets an empty array rather than a refusal — which is the honest
 * answer to "search my projects" when there are none.
 */
@RestController
@RequestMapping("/api/v1/issues")
public class IssueSearchController {

    private final IssueSearchService search;

    public IssueSearchController(IssueSearchService search) {
        this.search = search;
    }

    @GetMapping("/search")
    public ResponseEntity<Object> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam String q,
            @RequestParam(required = false) Integer limit) {

        return ResponseEntity.ok(search.search(UUID.fromString(jwt.getSubject()), q, limit));
    }
}
