package com.tcms.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * A connected AI assistant, as the caller's own profile lists it — the contract's {@code Assistant}.
 *
 * <p>There is no assistant table and no agent principal (D26). An assistant <em>is</em> an
 * {@link ApiToken} the caller owns carrying {@link Role#AGENT_ID}, so this is a projection of that
 * row narrowed to what a person needs to recognise one of their own machines: no owner (it is
 * always the caller), no role (it is always Agent), no prefix and no expiry, because none of them
 * tells the owner which laptop this is.
 *
 * <p>A revoked assistant stays in the list carrying {@link #revokedAt}, for
 * {@link ApiTokenRepository#findByOwnerIdAndRoleIdOrderByCreatedAtDesc}'s reason.
 *
 * @param projectKey the scoped project's key, resolved for display. Null when the assistant is
 *                   scoped to every project its owner can reach.
 */
public record AssistantView(
        UUID id,
        String name,
        UUID projectId,
        String projectKey,
        Instant lastUsedAt,
        Instant revokedAt,
        Instant createdAt) {

    static AssistantView of(ApiTokenView token, String projectKey) {
        return new AssistantView(
                token.id(),
                token.name(),
                token.projectId(),
                projectKey,
                token.lastUsedAt(),
                token.revokedAt(),
                token.createdAt());
    }

    static AssistantView of(ApiToken token, String projectKey) {
        return new AssistantView(
                token.getId(),
                token.getName(),
                token.getProjectId(),
                projectKey,
                token.getLastUsedAt(),
                token.getRevokedAt(),
                token.getCreatedAt());
    }
}
