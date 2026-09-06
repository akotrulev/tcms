package com.tcms.reporting.analytics;

import com.tcms.project.GateResult;
import com.tcms.reporting.analytics.AnalyticsViews.BurnUpReport;
import com.tcms.reporting.analytics.AnalyticsViews.CumulativeFlowReport;
import com.tcms.reporting.analytics.AnalyticsViews.CycleTimeReport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delivery analytics — three reads, {@code report:view} on all of them, and no write side at all
 * (020, module M9).
 *
 * <h2>These are not report jobs, and the distinction is the module's first decision</h2>
 *
 * <p>{@code ReportController} one package up produces <em>documents</em>: asynchronous by contract,
 * polled, downloadable, stored as an artifact. This controller answers synchronously, because the
 * screen above it has a release picker — a control whose whole purpose is to change the answer in
 * under a second — and a PM who has to press <em>Generate</em> and watch a spinner to find out
 * whether 2.4 is converging will not open it twice.
 *
 * <p>{@code ReportModel}'s header warns that a synchronous preview endpoint beside a report is a
 * second code path producing a second answer. That warning is right and is about a document, where
 * the screen and the export must be the same bytes. Nothing here is registered in
 * {@code ReportComputations}, so there is no second path to disagree with: these three reports have
 * one implementation each, and the screen's export serialises the JSON it already holds (020 D-b).
 *
 * <h2>Three states, and the switch is exhaustive at compile time</h2>
 *
 * <p>{@link GateResult} is sealed. {@code Unreachable} is a bodiless 404, {@code Denied} a 403 with
 * a sentence, and — for the burn-up only — an {@code Allowed} carrying an empty {@link Optional} is
 * the same 404: a release id that names nothing in this project. That last case is a 404 rather than
 * a 400 because the picker's options came from this project, so the only way to reach it is a
 * hand-written query string.
 *
 * <p>The error envelope is Spring's {@link ProblemDetail}, following {@code ReportController} beside
 * it.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/analytics")
class AnalyticsController {

    /**
     * The windows the contract offers, refused rather than clamped.
     *
     * <p>Snapping 45 to 30 answers a question nobody asked and leaves the caller nothing to notice
     * it by — the chart would simply be about a different period than the one they named.
     */
    private static final List<Integer> CYCLE_TIME_WINDOWS = List.of(30, 90, 180);
    private static final List<Integer> FLOW_WINDOWS = List.of(8, 12, 26);

    private final BurnUpService burnUp;
    private final CycleTimeService cycleTime;
    private final FlowService flow;

    AnalyticsController(BurnUpService burnUp, CycleTimeService cycleTime, FlowService flow) {
        this.burnUp = burnUp;
        this.cycleTime = cycleTime;
        this.flow = flow;
    }

    /** Done against total scope for one release, and what arrived after the release existed. */
    @GetMapping("/burn-up")
    ResponseEntity<Object> burnUp(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam UUID releaseId) {

        GateResult<Optional<BurnUpReport>> outcome =
                burnUp.of(UUID.fromString(jwt.getSubject()), projectId, releaseId);

        return switch (outcome) {
            case GateResult.Unreachable<Optional<BurnUpReport>> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<Optional<BurnUpReport>> ignored -> denied();
            case GateResult.Allowed<Optional<BurnUpReport>> allowed -> allowed.value()
                    .<ResponseEntity<Object>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        };
    }

    /** Median and p90 days from creation to done, one band per issue type. */
    @GetMapping("/cycle-time")
    ResponseEntity<Object> cycleTime(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(required = false) Integer days) {

        Integer window = window(days, CYCLE_TIME_WINDOWS, CycleTimeService.DEFAULT_WINDOW_DAYS);
        if (window == null) {
            return badWindow("days", CYCLE_TIME_WINDOWS);
        }

        GateResult<CycleTimeReport> outcome =
                cycleTime.of(UUID.fromString(jwt.getSubject()), projectId, window);

        return switch (outcome) {
            case GateResult.Unreachable<CycleTimeReport> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<CycleTimeReport> ignored -> denied();
            case GateResult.Allowed<CycleTimeReport> allowed -> ResponseEntity.ok(allowed.value());
        };
    }

    /** Issues per status band, weekly — including the band nothing recorded. */
    @GetMapping("/flow")
    ResponseEntity<Object> flow(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestParam(required = false) Integer weeks) {

        Integer window = window(weeks, FLOW_WINDOWS, FlowService.DEFAULT_WINDOW_WEEKS);
        if (window == null) {
            return badWindow("weeks", FLOW_WINDOWS);
        }

        GateResult<CumulativeFlowReport> outcome =
                flow.of(UUID.fromString(jwt.getSubject()), projectId, window);

        return switch (outcome) {
            case GateResult.Unreachable<CumulativeFlowReport> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<CumulativeFlowReport> ignored -> denied();
            case GateResult.Allowed<CumulativeFlowReport> allowed ->
                    ResponseEntity.ok(allowed.value());
        };
    }

    /** The asked-for window, the default when none was asked for, or null when it is not offered. */
    private static Integer window(Integer asked, List<Integer> offered, int fallback) {
        if (asked == null) {
            return fallback;
        }
        return offered.contains(asked) ? asked : null;
    }

    private static ResponseEntity<Object> badWindow(String field, List<Integer> offered) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        // The product's one sentence for this, matching ReportController's.
        problem.setDetail("Check the highlighted fields.");
        problem.setProperty(
                "errors",
                Map.of(field, List.of("Must be one of " + offered + ".")));
        return ResponseEntity.badRequest().body(problem);
    }

    private static ResponseEntity<Object> denied() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail("You do not have permission to view reports in this project.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }
}
