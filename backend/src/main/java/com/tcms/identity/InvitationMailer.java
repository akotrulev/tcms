package com.tcms.identity;

import java.time.Instant;

/**
 * Delivers an invitation to the person it names.
 *
 * <p>An interface with two implementations rather than one class with an {@code if}: SMTP
 * when a relay is configured, and a loud no-op when one is not. The second is not a
 * convenience — a self-hosted install may legitimately have no relay on day one, and the
 * alternative to naming that case is a context that refuses to start or a send that fails
 * silently.
 */
public interface InvitationMailer {

    /**
     * Sends, or reports why it could not.
     *
     * <p><strong>Must not throw.</strong> Callers dispatch this after the transaction has
     * committed, so there is nothing left to roll back — an exception here would abandon a
     * company that was successfully provisioned, in a code path whose only job was to be
     * helpful. Failures are logged; the activation link is also printed by
     * {@code provision-company}, which is what makes a mail outage degrade to hand delivery
     * rather than to an unusable deployment.
     */
    void send(InvitationMessage message);

    /**
     * Whether a relay exists to send through — asked <strong>before</strong> the send, because
     * the send happens after the transaction commits and nobody is left to tell.
     *
     * <p>{@code POST /users} reports this to the caller as {@code emailed}. An administrator who
     * invites a colleague on an install with no relay otherwise watches a success toast and waits
     * for a message that is never coming, while the only record of it is a WARN in a log they do
     * not read. The invitation is valid either way; what changes is who has to carry the link.
     *
     * <p>Deliberately not a {@code default} method. A third implementation must state which of the
     * two it is rather than inherit the optimistic answer — {@code provision-company} prints the
     * link regardless, so a wrong {@code true} here is invisible on the path that has a fallback
     * and misleading only on the one that does not.
     */
    boolean isConfigured();

    /**
     * @param acceptUrl the single-use activation link. <strong>The one place a live token
     *                  legitimately travels</strong> — it must not reach a log, an audit
     *                  entry or an error message.
     */
    record InvitationMessage(
            String toEmail,
            String toDisplayName,
            String companyName,
            String acceptUrl,
            Instant expiresAt) {
    }
}
