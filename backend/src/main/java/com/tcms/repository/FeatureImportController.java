package com.tcms.repository;

import com.tcms.api.Problem;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /projects/{projectId}/feature-imports} — plan or apply an import of {@code .feature}
 * files.
 *
 * <h2>Its own controller, and the reason is the resource</h2>
 *
 * <p>{@link CaseController} maps {@code /cases} and this is not a case: one request creates suites,
 * library conditions and cases together, and a caller with no {@code case:create} still gets a
 * useful answer from it. §72 ended the practice of a second controller on ONE prefix; this is a
 * different prefix with a different gate, which is what the path expresses.
 *
 * <h2>200, not 201, and no {@code Location}</h2>
 *
 * <p>Even {@code APPLY} answers 200. The response is a <em>plan</em> — what happened, row by row —
 * not a representation of one thing that now exists, and there is no single URI to point at: a
 * successful import creates a suite, three conditions and forty cases. A 201 would have to choose
 * one of them.
 *
 * <h2>404, never 403, for the request itself</h2>
 *
 * <p>Same three failures as every read in this module, collapsed into one bodiless 404 by
 * {@link FeatureImportService.Outcome.NotFound}. The permission answers that a caller CAN act on
 * are inside the body, one per row — a 403 here would tell a test engineer nothing about which of
 * their forty files they could import.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/feature-imports")
public class FeatureImportController {

    private final FeatureImportService imports;

    public FeatureImportController(FeatureImportService imports) {
        this.imports = imports;
    }

    @PostMapping
    public ResponseEntity<Object> run(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID projectId,
            @RequestBody FeatureImportRequest request) {

        if (request == null || request.mode() == null || request.onExisting() == null) {
            // Bound as enums, so an unknown VALUE is already a 400 from the binder; this is the
            // absent case, which binds to null and would otherwise NPE in the switch below.
            return ResponseEntity.badRequest().body(new Problem(
                    "validation-failed",
                    "That request could not be read",
                    HttpStatus.BAD_REQUEST.value(),
                    "Send both mode and onExisting.",
                    Map.of("mode", List.of("Send PREVIEW or APPLY."),
                            "onExisting", List.of("Send SKIP or UPDATE."))));
        }

        return switch (imports.run(UUID.fromString(jwt.getSubject()), projectId, request)) {
            case FeatureImportService.Outcome.NotFound ignored -> ResponseEntity.notFound().build();
            case FeatureImportService.Outcome.Invalid invalid -> ResponseEntity.badRequest()
                    .body(new Problem(
                            "validation-failed",
                            "That import request could not be accepted",
                            HttpStatus.BAD_REQUEST.value(),
                            "Correct the fields below and send it again.",
                            invalid.errors()));
            case FeatureImportService.Outcome.Planned planned -> ResponseEntity.ok(planned.plan());
        };
    }

    /** The two unique indexes a genuine race raises; anything else is not this handler's. */
    private static final String AUTOMATION_KEY_INDEX = "test_case_automation_key_idx";
    private static final String CONDITION_KEY_INDEX = "condition_imported_key_idx";

    /**
     * A concurrent writer took a key between the plan and the insert — 409, whole request rolled
     * back.
     *
     * <p>Two unique indexes can raise it and they mean different things to the caller:
     * {@code test_case_automation_key_idx} means somebody created a case for one of these scenarios
     * while this ran, and {@code condition_imported_key_idx} means two imports raced on the same
     * {@code Background:} step. The {@code type} tells them apart because the remedy differs — the
     * first needs the plan re-read, the second is a plain retry — and this is the second place in
     * this API to send a {@code type} that is not {@code about:blank}, for T3's reason: a machine
     * client has to branch on it.
     *
     * <p><strong>Nothing is half-imported.</strong> The service is one {@code @Transactional}, so
     * the rollback is the database's rather than a compensating write this class would have to get
     * right.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> raced(DataIntegrityViolationException raised) {
        String message = String.valueOf(raised.getMostSpecificCause().getMessage());
        boolean condition = message.contains(CONDITION_KEY_INDEX);
        if (!condition && !message.contains(AUTOMATION_KEY_INDEX)) {
            // NOT a race. Every other integrity violation — a null, a foreign key, a check
            // constraint — is a defect in this service or its callers, and answering it with
            // "another writer took your key" tells the caller to retry something that will fail
            // identically for ever while hiding the stack that says why. Rethrown, so it lands on
            // the 500 path with its cause intact.
            throw raised;
        }

        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem(
                condition ? "duplicate-imported-key" : "duplicate-automation-key",
                condition ? "A Background step was created by somebody else" : "That key was taken",
                HttpStatus.CONFLICT.value(),
                condition
                        ? "Another import created the same Background step while this one ran. "
                                + "Nothing was written; send it again."
                        : "Another writer took one of these automation keys while this import ran. "
                                + "Nothing was written; preview it again to see which.",
                null));
    }
}
