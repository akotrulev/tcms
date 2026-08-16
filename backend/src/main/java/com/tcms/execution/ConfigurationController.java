package com.tcms.execution;

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
 * {@code GET /projects/{projectId}/configurations} — the project's environments.
 *
 * <p>One operation, and a controller of its own rather than a method on {@link RunController}: the
 * path is not under {@code /runs}, and a configuration outlives every run that names it. The nearest
 * sibling is {@code project.ProjectController}'s {@code /statuses}, which is the same kind of thing
 * — a project vocabulary an execution form reads — and lives in the module that owns its table, as
 * this does.
 *
 * <p>Unreachable project ⇒ <strong>404, never 403</strong>, per §6t. See
 * {@link ConfigurationService} for why the read gate is the project's.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/configurations")
public class ConfigurationController {

    private final ConfigurationService configurations;

    public ConfigurationController(ConfigurationService configurations) {
        this.configurations = configurations;
    }

    @GetMapping
    public ResponseEntity<List<ConfigurationView>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return configurations.list(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
