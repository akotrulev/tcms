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
 * {@code GET /projects/{projectId}/conditions} — the reusable pre/post condition library.
 *
 * <p>A separate controller from {@link SuiteController} and {@link CaseController} on the same
 * terms those two are separate from each other: the path expresses the scope, not the owner, and
 * merging them would make one class the home of every project-scoped read.
 *
 * <h2>What this closes</h2>
 *
 * <p>{@code CaseListPage} fetches this endpoint for its bulk-action dialog on every load, so until
 * now the biggest screen in the product raised a "Something didn't load" notification even when
 * everything on it worked (handoff §25). It was invisible before that slice only because
 * {@code /cases} 404'd first — which is the useful half of the observation: an error notification
 * the user learns to ignore is one that no longer reports anything.
 *
 * <h2>The caller comes from the token, and the company from the context</h2>
 *
 * <p>Neither is a parameter. A {@code userId} parameter answers "what may <em>that</em> user see",
 * and a {@code companyId} parameter lets request input select the tenancy discriminator — the one
 * thing {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD §0.1 point 2).
 *
 * <h2>404, never 403</h2>
 *
 * <p>A project that does not exist, one in another company, and one in this company the caller
 * holds no grant on all answer <strong>404 with no body</strong>. A 403 confirms the id names
 * something real, which makes the endpoint an oracle for another client's project ids (§6t,
 * handoff §3 lesson 13). {@link ConditionService#findLibrary} collapses the three into an empty
 * {@link java.util.Optional} so this method has nothing to un-collapse.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/conditions")
public class ConditionController {

    private final ConditionService conditions;

    public ConditionController(ConditionService conditions) {
        this.conditions = conditions;
    }

    /**
     * The library, most-used first, narrowed by an optional title fragment and an optional role.
     *
     * <p>{@code type} is bound as an enum, so a value outside {@code PRE|POST|BOTH} is a 400 rather
     * than a silently ignored filter. That matters for the same reason it does on the case list: a
     * dropped filter here offers postconditions in a precondition picker, and every one of them
     * looks like a plausible answer.
     *
     * <p>{@code []} is a valid 200 and means nothing matched — the state of a freshly provisioned
     * project, and of any typeahead narrowed past the data. It is deliberately not the 404 the
     * gate uses: a user who cannot load the dialog cannot clear the query that emptied it.
     */
    @GetMapping
    public ResponseEntity<List<ConditionView>> library(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ConditionType type) {

        return conditions
                .findLibrary(UUID.fromString(jwt.getSubject()), projectId, new ConditionQuery(q, type))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
