package com.tcms.identity;

import java.time.Instant;

/**
 * The contract's {@code UserInvitation} — who was invited, and whether anything was sent.
 *
 * <p><strong>The activation link is deliberately absent.</strong> It is a live single-use secret
 * with exactly one legitimate destination, and {@link InvitationMailer.InvitationMessage} says so:
 * it must not reach a log, an audit entry or an error message. An API response is all three at
 * once — devtools, any proxy in between, and whatever the SPA caches.
 *
 * @param issued    whether this call minted a fresh link. The same fact as {@code 201} vs
 *                  {@code 200}, restated on the body because it is what makes {@code emailed}
 *                  readable: {@code emailed=false} means "no relay here" when a link <em>was</em>
 *                  minted and "there was nothing to send" when it was not. Without it a client
 *                  reports a mail outage on every second click, which the live pass found.
 * @param emailed   whether the link was handed to a configured relay (see
 *                  {@link InvitationMailer#isConfigured()}), never that the invitation failed.
 * @param expiresAt when the outstanding invitation lapses; <strong>null when there is nothing to
 *                  accept</strong>, which is the already-an-active-member case.
 */
public record UserInvitationView(
        UserView user, boolean issued, boolean emailed, Instant expiresAt) {
}
