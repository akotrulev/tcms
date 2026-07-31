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
 * Test plans.
 *
 * <p>The caller comes from the token and the company from the context — neither is a parameter, for
 * the reasons {@code ProjectController} sets out: a {@code userId} parameter answers "what may
 * <em>that</em> user see", and a {@code companyId} parameter lets request input select the tenancy
 * discriminator, which is the one thing {@link com.tcms.tenancy.CompanyContext} exists to prevent
 * (ERD §0.1 point 2).
 *
 * <p>404, never 403, on both reads: an absent project, one in another company, one this caller holds
 * no grant on, and a plan in a sibling project all collapse into an empty {@link java.util.Optional}
 * before this class sees them, so there is nothing here to un-collapse (§6t).
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/plans")
public class PlanController {

    private final PlanService plans;

    public PlanController(PlanService plans) {
        this.plans = plans;
    }

    /**
     * The project's plans, newest first.
     *
     * <p>{@code []} is a valid answer and means the project has no plans — the state of every
     * freshly provisioned project, since {@code tcms_seed_project_defaults} seeds a configuration
     * and a status vocabulary and deliberately not a plan.
     */
    @GetMapping
    public ResponseEntity<List<TestPlanView>> list(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID projectId) {

        return plans.list(UUID.fromString(jwt.getSubject()), projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{planId}")
    public ResponseEntity<TestPlanView> detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @PathVariable UUID planId) {

        return plans.findOne(UUID.fromString(jwt.getSubject()), projectId, planId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
