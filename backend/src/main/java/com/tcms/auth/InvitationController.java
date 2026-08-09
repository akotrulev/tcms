package com.tcms.auth;

import com.tcms.api.Problem;
import com.tcms.identity.InvitationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redeeming an invitation — the only two endpoints reachable with no account at all.
 *
 * <p>Under {@code /auth} because that is the family the filter chain leaves open, and being
 * open is the point: someone accepting an invitation has, by definition, nothing to
 * authenticate with.
 *
 * <h2>404, never 403, and never a reason</h2>
 *
 * <p>An unknown token, an expired one, one already redeemed, a membership since removed and
 * an account since deactivated all produce the same 404 with no body. The service collapses
 * them into one rejection constant and this controller does not un-collapse them: told
 * apart, they would let anyone with a token-shaped string learn whether an address is on
 * this instance. Same rule as unreachable display keys (plan §6t), on the one surface a
 * stranger can reach.
 */
@RestController
@RequestMapping("/api/v1/auth/invitations")
public class InvitationController {

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    /** @param password null when the account already has one — see {@link #accept} */
    public record AcceptRequest(String password) {
    }

    /**
     * What this invitation admits its holder to, for the screen that precedes acceptance.
     *
     * <p>The token travels in the <strong>path</strong> here and in the query string of the
     * emailed link. That is a known exposure — URLs reach browser history, {@code Referer}
     * headers and proxy logs — and it is bounded rather than denied: the token is single-use,
     * short-lived, and inert once redeemed.
     */
    @GetMapping("/{token}")
    public ResponseEntity<InvitationService.InvitationPreview> preview(@PathVariable String token) {
        return invitations.preview(token)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Redeems it.
     *
     * <p>Answers <strong>204, not a session</strong>. The invitee signs in afterwards with
     * the password they just chose, which keeps token issuance to a single path and makes
     * the round trip prove the password was stored as intended. Handing back a session here
     * would also mean an invitation link is, for one request, indistinguishable from a
     * credential.
     */
    @PostMapping("/{token}/accept")
    public ResponseEntity<Problem> accept(
            @PathVariable String token, @Valid @RequestBody AcceptRequest request) {

        InvitationService.AcceptOutcome outcome =
                invitations.accept(token, request == null ? null : request.password());

        return switch (outcome) {
            case InvitationService.Accepted ignored -> ResponseEntity.noContent().build();
            case InvitationService.Rejected rejected -> switch (rejected.reason()) {
                case NOT_REDEEMABLE -> ResponseEntity.notFound().build();
                // 422, and it DOES carry a reason: it describes the password the caller just
                // submitted, not anything about the account or the instance, so saying "too
                // short" leaks nothing while saying nothing would leave them guessing.
                case PASSWORD_UNACCEPTABLE, PASSWORD_NOT_REQUIRED ->
                        ResponseEntity.unprocessableEntity().body(Problem.unprocessable(
                                "The password was not accepted", rejected.detail()));
            };
        };
    }

}
