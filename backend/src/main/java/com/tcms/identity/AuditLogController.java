package com.tcms.identity;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail over HTTP — one contract tag ({@code audit}), so one controller.
 *
 * <p>Two path families under one class for {@link AdminController}'s reason and against
 * {@link OrgChartController}'s: the deciding question is whether the endpoints are one surface, not
 * whether their URLs share a prefix. These two serve the same screen at two scopes and differ in
 * their gate alone, and splitting them would put the project trail's rendering in one class and the
 * company trail's in another with nothing keeping the two identical.
 *
 * <p>Every verdict is computed in {@link AuditLogService} and merely rendered here — an endpoint
 * that decides "may they?" for itself is how two implementations diverge (plan §6b).
 *
 * <h2>{@code Instant} query parameters bind without a format annotation</h2>
 *
 * <p>Spring's {@code InstantFormatter} parses {@code ISO_INSTANT} first and an offset date-time
 * second, so both {@code 2026-08-18T09:00:00Z} and {@code 2026-08-18T11:00:00+02:00} arrive. A
 * {@code @DateTimeFormat} here would narrow that to one spelling for no gain — the contract says
 * {@code format: date-time}, which is the wider of the two.
 *
 * <h2>The caller comes from the token and the company from the context</h2>
 *
 * <p>Neither is a parameter, for the reasons {@link AdminController} sets out.
 */
@RestController
@RequestMapping("/api/v1")
public class AuditLogController {

    private final AuditLogService trail;

    public AuditLogController(AuditLogService trail) {
        this.trail = trail;
    }

    /** One project's acts, newest first. {@code project:audit}. */
    @GetMapping("/projects/{projectId}/audit-events")
    public ResponseEntity<Object> forProject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant occurredAfter,
            @RequestParam(required = false) Instant occurredBefore,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {

        return read(
                trail.forProject(caller(jwt), projectId, new AuditLogQuery(
                        null, actorUserId, action, occurredAfter, occurredBefore, page, size)),
                "You do not have permission to see this project's audit trail.");
    }

    /**
     * The company's acts, newest first, project-scoped rows included. {@code audit:view}.
     *
     * <p>{@code projectId} here is a filter and not a scope: an id naming no project in this
     * company matches nothing rather than refusing, exactly as an unknown filter value does on the
     * case list. Refusing it would make the query string an oracle for which project ids exist.
     */
    @GetMapping("/audit-events")
    public ResponseEntity<Object> forCompany(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant occurredAfter,
            @RequestParam(required = false) Instant occurredBefore,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {

        return read(
                trail.forCompany(caller(jwt), new AuditLogQuery(
                        projectId, actorUserId, action, occurredAfter, occurredBefore, page, size)),
                "You do not have permission to see this company's audit trail.");
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * The read states, for both operations.
     *
     * <p>{@link GateResult.Unreachable} is only ever produced by the project read — the company
     * trail names nothing that could fail to exist. It is handled here anyway because the switch is
     * exhaustive over a sealed type, which is the property that stops a fourth outcome being
     * handled in one of the two endpoints and defaulted in the other.
     *
     * <p><strong>A refusal is a 403 with a body, never an empty page.</strong> An empty audit trail
     * reads as "nothing happened" and the reader has no way to tell that apart from "you were not
     * allowed to look" — see {@link AuditLogService}'s header.
     */
    private static ResponseEntity<Object> read(GateResult<?> gated, String refusal) {
        return switch (gated) {
            case GateResult.Unreachable<?> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<?> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(refusal));
            case GateResult.Allowed<?> allowed -> ResponseEntity.ok(allowed.value());
        };
    }
}
