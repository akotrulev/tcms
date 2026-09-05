package com.tcms.billing;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /invoices/*} — the draft as a query, the issue as a record (019 D-c).
 *
 * <p>Thin, as every controller here is: resolve the caller from the session, delegate, map the
 * sealed outcome. <strong>The company is never a parameter</strong> — it comes from the
 * authenticated session and {@code CompanyAwareTransactionManager} puts it on the connection.
 *
 * <p>Every path is gated on a money key, so an Account Manager holding {@code client:read} and no
 * {@code billing:*} is refused all five. {@code billing.html}'s own callout says the sidebar entry
 * above them is absent rather than greyed out, because a screen that exists only to say no is the
 * same refusal one click later (019 D-o).
 */
@RestController
@RequestMapping("/api/v1")
public class InvoiceController {

    private static final String NO_READ = "You do not have permission to see invoices.";
    private static final String NO_DRAFT = "You do not have permission to draft an invoice.";
    private static final String NO_ISSUE = "You do not have permission to issue an invoice.";

    private final InvoiceService invoices;

    InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    /**
     * {@code status} defaults to {@code ALL}, which is what {@code billing.html}'s panel renders.
     *
     * <p>Bound as a {@link String} and mapped here rather than as an {@link InvoiceStatus}: the
     * contract's enumeration has a third member, {@code ALL}, that the entity's does not — because
     * "every status" is a question a filter can ask and never a state a row can be in. Binding the
     * enum directly would answer {@code 400} to the documented default.
     */
    @GetMapping("/invoices")
    public ResponseEntity<Object> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) String status) {

        return BillingResponses.read(
                invoices.list(caller(jwt), clientId, filter(status)), NO_READ);
    }

    @GetMapping("/invoices/{invoiceId}")
    public ResponseEntity<Object> read(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID invoiceId) {

        return BillingResponses.read(invoices.read(caller(jwt), invoiceId), NO_READ);
    }

    @PostMapping("/invoices")
    public ResponseEntity<Object> draft(
            @AuthenticationPrincipal Jwt jwt, @RequestBody InvoiceDraftInput body) {

        return BillingResponses.answer(
                invoices.draft(caller(jwt), body), HttpStatus.CREATED, NO_DRAFT);
    }

    @PostMapping("/invoices/{invoiceId}/issue")
    public ResponseEntity<Object> issue(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID invoiceId) {

        return BillingResponses.answer(
                invoices.issue(caller(jwt), invoiceId), HttpStatus.OK, NO_ISSUE);
    }

    @DeleteMapping("/invoices/{invoiceId}")
    public ResponseEntity<Object> discard(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID invoiceId) {

        return BillingResponses.answer(
                invoices.discard(caller(jwt), invoiceId), HttpStatus.NO_CONTENT, NO_DRAFT);
    }

    /** {@code null} — the service's "every status" — for {@code ALL}, for absent, and for junk. */
    private static InvoiceStatus filter(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        for (InvoiceStatus candidate : InvoiceStatus.values()) {
            if (candidate.name().equalsIgnoreCase(status)) {
                return candidate;
            }
        }
        /*
          An unknown value reads as ALL rather than as a 400, and that is the contract's doing
          rather than a shrug: GET /invoices declares no 400, and ErrorBodyPolicyTest would fail a
          refusal this operation never promised. Binding the enum directly would produce one anyway
          — Spring answers 400 on a value it cannot convert — which is why the parameter is a String
          here and mapped by hand.
        */
        return null;
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
