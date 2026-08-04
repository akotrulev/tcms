package com.tcms.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /projects/{projectId}/step-suggestions} — what the step editor completes from.
 *
 * <p>A separate controller from {@link CaseController} and {@link ConditionController} on the terms
 * those two are separate from each other: the path expresses the scope, not the owner, and merging
 * them would make one class the home of every project-scoped read.
 *
 * <h2>What this closes</h2>
 *
 * <p>Steps were typed from scratch every time. The {@code shared_step} library was built to prevent
 * exactly that and could not, because it only ever held what somebody deliberately curated — so it
 * sat empty, its picker opened on nothing, and the same twenty steps were retyped across a project
 * in twenty slightly different wordings. Nothing surfaced what the project already said.
 *
 * <p>This endpoint says it, derived rather than curated. The corpus is every step already written
 * in the project, so it is populated the moment the project has a case and needs no maintenance to
 * stay that way.
 *
 * <h2>A suggestion is text, not a reference</h2>
 *
 * <p>Picking one <strong>copies</strong> the action, test data and expected result into the author's
 * step. It creates no link: the step belongs to their case, and editing it afterwards changes their
 * case and nothing else. That is deliberately the opposite of how conditions work — a condition is
 * attached by id and an edit propagates to every case using it — and the two are different because
 * a shared precondition is a fact about the system while a step is a sentence somebody wrote.
 * Nothing in the response carries an id, so the distinction is enforced by the payload's shape and
 * not by a convention a later consumer can drift from.
 *
 * <h2>The caller comes from the token, and the company from the context</h2>
 *
 * <p>Neither is a parameter. A {@code userId} parameter answers "what may <em>that</em> user see",
 * and a {@code companyId} parameter lets request input select the tenancy discriminator — the one
 * thing {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD §0.1 point 2).
 *
 * <h2>404, never 403</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller holds
 * no grant on all answer <strong>404 with no body</strong>. A 403 confirms the id names something
 * real, which makes the endpoint an oracle for another client's project ids (§6t, handoff §3 lesson
 * 13). {@link StepSuggestionService#findSuggestions} collapses the three into an empty
 * {@link java.util.Optional} so this method has nothing to un-collapse.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/step-suggestions")
public class StepSuggestionController {

    private final StepSuggestionService suggestions;

    public StepSuggestionController(StepSuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    /**
     * The corpus, most-used first, narrowed by an optional fragment of the action.
     *
     * <p>{@code []} is a valid 200 and means nothing matched — the state of a project whose cases
     * have no steps yet, and of any typeahead narrowed past the corpus. It is deliberately not the
     * 404 the gate uses: an author who cannot load suggestions can still write the step by hand, and
     * an error at that moment would read as the editor being broken.
     */
    @GetMapping
    public ResponseEntity<List<StepSuggestionView>> suggestions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String q) {

        return suggestions
                .findSuggestions(
                        UUID.fromString(jwt.getSubject()), projectId, new StepSuggestionQuery(q))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
