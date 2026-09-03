package com.tcms.tracker.agent;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /issues/{key}/agent-context} — the one read an assistant makes before it starts.
 *
 * <p>Its own class rather than a fifth method on {@code IssueController}, for
 * {@code TransitionController}'s reason: it is its own <em>shape</em>, assembled from four reads,
 * and a reader looking for "what does an agent get handed" should find one file.
 *
 * <p>404 covers both "no such key" and "not reachable from this session" — including a token scoped
 * to another project, which is where the scope property is actually enforced. §6t: the status must
 * not tell a caller that an issue they cannot reach exists.
 */
@RestController
@RequestMapping("/api/v1/issues/{key}/agent-context")
public class AgentContextController {

    private final AgentContextService context;

    public AgentContextController(AgentContextService context) {
        this.context = context;
    }

    @GetMapping
    public ResponseEntity<Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return context.contextFor(UUID.fromString(jwt.getSubject()), key)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
