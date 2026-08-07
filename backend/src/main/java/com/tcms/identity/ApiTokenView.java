package com.tcms.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's {@code ApiToken} — <strong>everything about a credential except the credential</strong>.
 *
 * <p>{@link #tokenPrefix} is the only trace of the secret that survives issuance, and it is what
 * makes a list usable: an administrator matching a row against a CI configuration has the name they
 * typed and the first {@value ApiToken#PREFIX_LENGTH} characters of what they pasted, which is
 * enough to identify and far too little to use.
 *
 * <p>{@link #revokedAt} is on the wire rather than the row being filtered out. A revoked token is
 * evidence — of when the credential stopped working, and of somebody having acted — and a list that
 * silently drops it reads as though the token never existed, which is the wrong story to tell about
 * a credential that was live in a pipeline yesterday.
 *
 * @param roleName the role the token carries, expanded here because a uuid tells an administrator
 *                 nothing about what a leaked token could do. It is a <em>label</em>: nothing checks
 *                 it, and {@link PermissionEvaluator} resolves the grant from {@code roleId}.
 * @param lastUsedAt always null in this slice — {@code ApiToken.lastUsedAt} has no writer yet, and
 *                 the field is on the wire so the client is built against the shape rather than
 *                 against its absence.
 */
public record ApiTokenView(
        UUID id,
        String name,
        String tokenPrefix,
        UUID ownerId,
        UUID roleId,
        String roleName,
        UUID projectId,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt,
        Instant createdAt) {
}
