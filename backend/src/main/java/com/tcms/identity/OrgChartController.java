package com.tcms.identity;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /org-chart} — one path family, one controller.
 *
 * <p>{@link TeamController}'s stated rule, and the reason {@code /teams} moved off
 * {@link AdminController} in the first place. This is not {@code /teams}' sixth method: the team
 * endpoints are the org chart's <em>structure</em> and five of them are writes gated on
 * {@code company:teams}, while this is a read every member may make and writes nothing.
 *
 * <h2>200 with an empty document, never 403</h2>
 *
 * <p>The contract declares only {@code 200} here — {@link OrgChartService}'s header argues why, and
 * it is the same argument the four other identity reads make. The verdict is computed in the
 * service and merely rendered here; an endpoint that decides "may they?" for itself is how two
 * implementations diverge (plan §6b).
 *
 * <p>The caller comes from the token and the company from the context. Neither is a parameter, and
 * a {@code companyId} one would let request input select the tenancy discriminator — the one thing
 * {@link com.tcms.tenancy.CompanyContext} exists to prevent (ERD §0.1 point 2).
 */
@RestController
@RequestMapping("/api/v1/org-chart")
public class OrgChartController {

    private final OrgChartService chart;

    public OrgChartController(OrgChartService chart) {
        this.chart = chart;
    }

    /** The company's people and teams, and the matrix clipped to what this caller may see. */
    @GetMapping
    public OrgChartView chart(@AuthenticationPrincipal Jwt jwt) {
        return chart.chartFor(caller(jwt));
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
