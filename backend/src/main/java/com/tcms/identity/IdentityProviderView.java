package com.tcms.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * A provider as an administrator sees it.
 *
 * <p><strong>There is no client-secret field, on this or any other read path</strong>, and
 * {@link #clientSecretSet} is what replaces it: a screen needs to know whether one is stored so it
 * can say "leave blank to keep" rather than "required", and that is a boolean, not the value. The
 * absence is the design (§6r) — the secret is never in a response, a log line, an audit event or an
 * error message.
 *
 * @param clientSecretSet whether a secret is stored. False means a public client, which is a
 *                        configuration rather than an omission
 */
public record IdentityProviderView(
        UUID id,
        String key,
        IdentityProviderKind kind,
        String displayName,
        String issuerUri,
        String clientId,
        boolean clientSecretSet,
        boolean jitProvisioning,
        boolean autoLinkVerifiedEmail,
        String groupClaim,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {

    static IdentityProviderView of(IdentityProvider provider) {
        return new IdentityProviderView(
                provider.getId(),
                provider.getKey(),
                provider.getKind(),
                provider.getDisplayName(),
                provider.getIssuerUri(),
                provider.getClientId(),
                provider.getClientSecretEncrypted() != null,
                provider.isJitProvisioning(),
                provider.isAutoLinkVerifiedEmail(),
                provider.getGroupClaim(),
                provider.isEnabled(),
                provider.getCreatedAt(),
                provider.getUpdatedAt());
    }
}
