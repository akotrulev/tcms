package com.tcms.tracker;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /issues/{key}/doc-links} — the specification blocks this issue links to.
 *
 * <p><strong>Read-only, and there is no write here.</strong> Creating the link is
 * {@code documentation}'s {@code POST /projects/{projectId}/documents/{pageId}/blocks/{blockId}/links}
 * with {@code sourceType: ISSUE}, because that module owns {@code doc_link} and this one may not
 * import it (D-e). What is served here is this module's own projection, {@code issue_doc_link},
 * which is what carries {@code stale}.
 */
@RestController
@RequestMapping("/api/v1/issues/{key}/doc-links")
public class DocLinkController {

    private final DocLinkService links;

    public DocLinkController(DocLinkService links) {
        this.links = links;
    }

    @GetMapping
    public ResponseEntity<Object> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return links.list(UUID.fromString(jwt.getSubject()), key)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
