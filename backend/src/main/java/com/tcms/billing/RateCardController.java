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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /rate-cards/*} — what an hour is charged at, for one client.
 *
 * <p>Thin, as every controller here is: resolve the caller from the session, delegate, map the
 * sealed outcome. <strong>The company is never a parameter</strong> — it comes from the
 * authenticated session and {@code CompanyAwareTransactionManager} puts it on the connection.
 *
 * <p>Every path is gated on a money key, so an Account Manager holding {@code client:read} and no
 * {@code billing:*} is refused all five — and {@code billing.html}'s own callout says the sidebar
 * entry above them is absent rather than greyed out, because a screen that exists only to say no is
 * the same refusal one click later (019 D-o).
 */
@RestController
@RequestMapping("/api/v1")
public class RateCardController {

    private static final String NO_READ = "You do not have permission to see rate cards.";
    private static final String NO_MANAGE = "You do not have permission to change a rate card.";

    private final RateCardService cards;

    RateCardController(RateCardService cards) {
        this.cards = cards;
    }

    @GetMapping("/rate-cards")
    public ResponseEntity<Object> list(@AuthenticationPrincipal Jwt jwt) {
        return BillingResponses.read(cards.listSlots(caller(jwt)), NO_READ);
    }

    @GetMapping("/rate-cards/{rateCardId}")
    public ResponseEntity<Object> read(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID rateCardId) {

        return BillingResponses.read(cards.read(caller(jwt), rateCardId), NO_READ);
    }

    @PostMapping("/rate-cards")
    public ResponseEntity<Object> create(
            @AuthenticationPrincipal Jwt jwt, @RequestBody RateCardWriteRequest body) {

        return BillingResponses.answer(
                cards.create(caller(jwt), body), HttpStatus.CREATED, NO_MANAGE);
    }

    @PutMapping("/rate-cards/{rateCardId}")
    public ResponseEntity<Object> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID rateCardId,
            @RequestBody RateCardWriteRequest body) {

        return BillingResponses.answer(
                cards.update(caller(jwt), rateCardId, body), HttpStatus.OK, NO_MANAGE);
    }

    @DeleteMapping("/rate-cards/{rateCardId}")
    public ResponseEntity<Object> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID rateCardId) {

        return BillingResponses.answer(
                cards.delete(caller(jwt), rateCardId), HttpStatus.NO_CONTENT, NO_MANAGE);
    }

    private static UUID caller(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
