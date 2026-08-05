package com.tcms.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /projects/{projectId}/tags} — the tags in use in this project.
 *
 * <p>Its own controller on {@link StepSuggestionController}'s terms: the path expresses the scope,
 * not the owner, and folding every project-scoped read into {@link CaseController} would make one
 * class the home of all of them.
 *
 * <h2>No permission key beyond project visibility</h2>
 *
 * <p>Deliberate, and it is the eighth time this argument has been made rather than a key invented
 * (§67, §79, §80, §82, §84 …): a key {@code V3} seeds to nobody is a 403 no administrator can grant
 * out of. There is also nothing here to protect that the caller cannot already read — every tag this
 * returns is on a case in a project they hold a grant on, and the case list serves the tags
 * themselves on every row.
 *
 * <h2>404, never 403</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller holds
 * no grant on all answer <strong>404 with no body</strong>. A 403 confirms the id names something
 * real, which makes the endpoint an oracle for another client's project ids (§6t).
 * {@link TagService#findTagsInUse} collapses the three into an empty {@link java.util.Optional} so
 * this method has nothing to un-collapse.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tags")
public class TagController {

    private final TagService tags;

    public TagController(TagService tags) {
        this.tags = tags;
    }

    /**
     * The tags, most-used first and then alphabetically.
     *
     * <p>{@code []} is a valid 200 and is the ordinary answer for a project nobody has tagged — not
     * the 404 the gate uses. An author who cannot load the list can still type the tag by hand, and
     * an error at that moment would read as the control being broken.
     */
    @GetMapping
    public ResponseEntity<List<TagUsageView>> tagsInUse(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return tags.findTagsInUse(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
