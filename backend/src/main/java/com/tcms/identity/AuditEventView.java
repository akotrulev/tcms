package com.tcms.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One recorded act, on the wire — the contract's {@code AuditEvent}.
 *
 * <h2>Both actor halves are sent, and that is not redundancy</h2>
 *
 * <p>{@code actorUserId} and {@code actorTokenId} are independently nullable and <strong>are set
 * together on the ordinary token-driven act</strong>: {@link com.tcms.tenancy.AuditEvent#record}
 * fills the token beside the person, never instead of them, because the caller identity on a
 * token-authenticated request is the token's owner. So the three shapes a client must render are a
 * person alone (a session), a person <em>and</em> a credential (a CI runner acting for someone),
 * and neither (an unattended job). A view that treated the two as an either/or would report the
 * second as the first and lose which credential was used.
 *
 * <h2>The two names are resolved, and neither offboarding nor revocation takes one away</h2>
 *
 * <p>An audit trail outlives its actors, and both resolvers are chosen so that it does. Removing
 * someone sets their membership to {@code REMOVED} rather than deleting it and {@link
 * AdminDirectoryService#peopleById} does not filter on status, so a departed colleague's acts still
 * carry their name; {@code ApiTokenRepository.findAllByIdIn} includes revoked tokens for the same
 * reason. <strong>This corrects what the plan for this slice assumed</strong> — it expected a
 * removal to null the name, which would have made "who did this" unanswerable on exactly the rows
 * somebody opens this screen to explain.
 *
 * <p>A name is null when its id is null, or when the id names a row that no longer exists at all —
 * an operator-level deletion rather than an ordinary offboarding. So a null name is never evidence
 * about the id: read the id.
 *
 * @param actorUserName the actor's display name <em>in this company</em> — the membership's, not
 *                      the account's canonical one (§6u), resolved through
 *                      {@link AdminDirectoryService#peopleById} so this is not a second
 *                      implementation of that rule
 * @param changes       the recorded JSON document, opaque here and passed through as the string the
 *                      writer stored. Not parsed and not re-serialized: re-emitting it through
 *                      Jackson would make the audit trail's content depend on this application's
 *                      current mapper configuration rather than on what was written.
 */
public record AuditEventView(
        UUID id,
        UUID projectId,
        UUID actorUserId,
        String actorUserName,
        UUID actorTokenId,
        String actorTokenName,
        String action,
        String targetType,
        List<UUID> targetIds,
        String changes,
        UUID bulkOperationId,
        Instant occurredAt) {
}
