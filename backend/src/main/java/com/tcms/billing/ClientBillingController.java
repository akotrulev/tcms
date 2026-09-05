package com.tcms.billing;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /clients/{clientId}/billing} — <strong>the one path under a {@code clients} URL that a
 * money module answers</strong> (019 D-i, D-n).
 *
 * <p>It lives here rather than in {@code crm} because the answer is invoices and hours, and
 * {@code crm} may not read either. It is tagged {@code clients} on the contract because the tag
 * names the screen a reader is on, and {@code client.html} is where this table is drawn.
 *
 * <p><strong>Gated on {@code client:read}</strong> and not on a money key: an Account Manager reads
 * it in full, and whether the amounts come with it is
 * {@link ClientBillingSummaryView#amountsVisible()} — a field, evaluated server-side, because a
 * {@code null} that means two things is a lie.
 */
@RestController
@RequestMapping("/api/v1")
public class ClientBillingController {

    private static final String NO_READ = "You do not have permission to see this client.";

    private final ClientBillingService billing;

    ClientBillingController(ClientBillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/clients/{clientId}/billing")
    public ResponseEntity<Object> read(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID clientId) {

        return BillingResponses.read(billing.read(caller(jwt), clientId), NO_READ);
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
