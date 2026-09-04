package com.tcms.time;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The entries themselves — {@code /timesheets/entries}.
 *
 * <h2>{@code Idempotency-Key} is declared by the contract and not honoured here yet</h2>
 *
 * <p>Stated rather than left to be discovered: the contract's {@code 200} arm on the create is a
 * <em>replay</em>, and answering one needs the key stored beside the row it created — which is what
 * {@code test_case.created_idempotency_key} does one module over. {@code V9} gave {@code time_entry}
 * no such column, so a retry after a timeout logs the hour twice. The fix is a column and a lookup,
 * not a change here, and it is the one part of this surface that is declared and unimplemented.
 */
@RestController
@RequestMapping("/api/v1")
public class TimeEntryController {

    private final TimeEntryService entries;

    public TimeEntryController(TimeEntryService entries) {
        this.entries = entries;
    }

    @PostMapping("/timesheets/entries")
    public ResponseEntity<Object> log(
            @AuthenticationPrincipal Jwt jwt, @RequestBody TimeEntryInput submitted) {

        return answer(
                entries.log(UUID.fromString(jwt.getSubject()), submitted),
                HttpStatus.CREATED,
                "You do not have permission to log time against this project.");
    }

    @PatchMapping("/timesheets/entries/{entryId}")
    public ResponseEntity<Object> amend(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID entryId,
            @RequestBody TimeEntryInput submitted) {

        return answer(
                entries.amend(UUID.fromString(jwt.getSubject()), entryId, submitted),
                HttpStatus.OK,
                "You do not have permission to amend this entry.");
    }

    @DeleteMapping("/timesheets/entries/{entryId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID entryId) {

        return answer(
                entries.delete(UUID.fromString(jwt.getSubject()), entryId),
                HttpStatus.NO_CONTENT,
                "You do not have permission to remove this entry.");
    }

    /**
     * One mapping for all three writes, because the outcomes are one sealed set.
     *
     * <p>{@link TimeEntryService.Outcome.Locked} is the {@code 409} the whole feature turns on, and
     * its sentence says which state the period is in rather than only that it is closed — a person
     * whose fortnight was approved and one whose fortnight is waiting need different next steps.
     */
    private static ResponseEntity<Object> answer(
            GateResult<TimeEntryService.Outcome> gate, HttpStatus written, String forbidden) {

        return switch (gate) {
            case GateResult.Unreachable<TimeEntryService.Outcome> ignored ->
                    ResponseEntity.notFound().build();
            case GateResult.Denied<TimeEntryService.Outcome> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(forbidden));
            case GateResult.Allowed<TimeEntryService.Outcome> allowed -> switch (allowed.value()) {
                case TimeEntryService.Outcome.Invalid invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case TimeEntryService.Outcome.Locked locked -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict("This period is closed",
                                "The fortnight this day falls in is " + locked.state()
                                        + ", so nothing in it can be changed. A rejected period is "
                                        + "editable again; an approved one is final."));
                case TimeEntryService.Outcome.Written stored ->
                        ResponseEntity.status(written).body(stored.view());
                case TimeEntryService.Outcome.Deleted ignored -> ResponseEntity.noContent().build();
            };
        };
    }
}
