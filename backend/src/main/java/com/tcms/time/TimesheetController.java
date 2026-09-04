package com.tcms.time;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Periods, submission and the approval queue — {@code /timesheets/*}.
 *
 * <p>Thin, as every controller here is: resolve the caller from the session, delegate, map the
 * sealed outcome. <strong>The company is never a parameter</strong> — it comes from the
 * authenticated session and {@code CompanyAwareTransactionManager} puts it on the connection.
 */
@RestController
@RequestMapping("/api/v1")
public class TimesheetController {

    private final TimesheetService timesheets;

    public TimesheetController(TimesheetService timesheets) {
        this.timesheets = timesheets;
    }

    @GetMapping("/timesheets/periods/current")
    public ResponseEntity<Object> current(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "on", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {

        return read(timesheets.current(UUID.fromString(jwt.getSubject()), on),
                "You do not have permission to log time.");
    }

    @GetMapping("/timesheets/periods")
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "limit", required = false) Integer limit) {

        return read(timesheets.list(UUID.fromString(jwt.getSubject()), limit),
                "You do not have permission to log time.");
    }

    @GetMapping("/timesheets/periods/{periodId}")
    public ResponseEntity<Object> find(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID periodId) {

        return read(timesheets.find(UUID.fromString(jwt.getSubject()), periodId),
                "You do not have permission to read somebody else's timesheet.");
    }

    @GetMapping("/timesheets/approvals")
    public ResponseEntity<Object> approvals(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "on", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {

        return read(timesheets.queue(UUID.fromString(jwt.getSubject()), on),
                "You do not have permission to approve timesheets on any project.");
    }

    @PostMapping("/timesheets/periods/{periodId}/submit")
    public ResponseEntity<Object> submit(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID periodId) {

        return answer(
                timesheets.submit(UUID.fromString(jwt.getSubject()), periodId),
                "You can only submit your own timesheet.",
                "Already submitted");
    }

    @PostMapping("/timesheets/periods/{periodId}/decision")
    public ResponseEntity<Object> decide(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID periodId,
            @RequestBody TimesheetDecisionInput submitted) {

        return answer(
                timesheets.decide(UUID.fromString(jwt.getSubject()), periodId, submitted),
                "You do not have permission to approve this timesheet.",
                "That decision does not apply here");
    }

    private static <T> ResponseEntity<Object> read(GateResult<T> gate, String forbidden) {
        return switch (gate) {
            case GateResult.Unreachable<T> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<T> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(forbidden));
            case GateResult.Allowed<T> allowed -> ResponseEntity.ok(allowed.value());
        };
    }

    /**
     * One mapping for both writes, because their outcomes are one sealed set.
     *
     * <p>The exhaustive switch is what makes a new outcome a compile error here rather than a silent
     * fall-through to a 200 with the wrong body.
     */
    private static ResponseEntity<Object> answer(
            GateResult<TimesheetService.Outcome> gate, String forbidden, String conflictTitle) {

        return switch (gate) {
            case GateResult.Unreachable<TimesheetService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<TimesheetService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(forbidden));
            case GateResult.Allowed<TimesheetService.Outcome> allowed -> switch (allowed.value()) {
                case TimesheetService.Outcome.Invalid invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case TimesheetService.Outcome.NotInThatState wrong -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict(conflictTitle,
                                "This period is " + wrong.state() + ". Reload it to see where it "
                                        + "stands — somebody may have acted on it since this "
                                        + "screen was opened."));
                case TimesheetService.Outcome.Decided decided ->
                        ResponseEntity.ok(decided.view());
            };
        };
    }
}
