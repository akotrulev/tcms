package com.tcms.billing;

import com.tcms.api.Problem;
import com.tcms.project.GateResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * One mapping from a gate and an outcome to a status, shared by this module's controllers.
 *
 * <p>{@code crm} and {@code staffing} each write this pair of methods once because each has one
 * controller shape to write it for; this module has three, and three copies of an exhaustive
 * {@code switch} is three places a sixth outcome gets handled in two. The {@code switch} itself is
 * what makes that a compile error rather than a fall-through to a 200 with the wrong body, so the
 * type stays sealed and the mapping stays in one file.
 */
final class BillingResponses {

    private BillingResponses() {
    }

    /** A read: 404 conceals, 403 refuses, 200 answers. */
    static <T> ResponseEntity<Object> read(GateResult<T> gate, String forbidden) {
        return switch (gate) {
            case GateResult.Unreachable<T> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<T> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(forbidden));
            case GateResult.Allowed<T> allowed -> ResponseEntity.ok(allowed.value());
        };
    }

    /** A write, and every state one can end in. */
    static <T> ResponseEntity<Object> answer(
            GateResult<Outcome<T>> gate, HttpStatus written, String forbidden) {

        return switch (gate) {
            case GateResult.Unreachable<Outcome<T>> ignored -> ResponseEntity.notFound().build();
            case GateResult.Denied<Outcome<T>> ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).body(Problem.forbidden(forbidden));
            case GateResult.Allowed<Outcome<T>> allowed -> switch (allowed.value()) {
                case Outcome.Invalid<T> invalid ->
                        ResponseEntity.badRequest().body(Problem.validation(invalid.errors()));
                case Outcome.Missing<T> ignored -> ResponseEntity.notFound().build();
                case Outcome.Conflict<T> clash -> ResponseEntity
                        .status(HttpStatus.CONFLICT)
                        .body(Problem.conflict(clash.title(), clash.detail()));
                case Outcome.Written<T> stored ->
                        ResponseEntity.status(written).body(stored.value());
                case Outcome.Removed<T> ignored -> ResponseEntity.noContent().build();
            };
        };
    }
}
