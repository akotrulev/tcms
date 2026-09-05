package com.tcms.billing;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /billing/summary} — the four figures at the top of the Billing screen.
 *
 * <p>Gated on {@code billing:read}, like everything behind the rail entry that is absent for
 * everybody who does not hold it (019 D-o).
 */
@RestController
@RequestMapping("/api/v1")
public class BillingSummaryController {

    private static final String NO_READ = "You do not have permission to see billing.";

    private final BillingSummaryService summaries;

    BillingSummaryController(BillingSummaryService summaries) {
        this.summaries = summaries;
    }

    @GetMapping("/billing/summary")
    public ResponseEntity<Object> summary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {

        return BillingResponses.read(summaries.summary(caller(jwt), on), NO_READ);
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
