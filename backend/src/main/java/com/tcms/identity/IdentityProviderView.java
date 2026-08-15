package com.tcms.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * A provider as an administrator sees it.
 *
 * <p><strong>Neither credential appears on this or any other read path</strong> — not the client
 * secret, not the bind password — and {@link #clientSecretSet} / {@link #bindPasswordSet} are what
 * replace them: a screen needs to know whether one is stored so it can say "leave blank to keep"
 * rather than "required", and that is a boolean, not the value. The absence is the design (§6r):
 * neither is ever in a response, a log line, an audit event or an error message.
 *
 * @param clientSecretSet whether a secret is stored. False means a public client, which is a
 *                        configuration rather than an omission
 * @param bindPasswordSet whether a bind password is stored. False means an anonymous search, which
 *                        some directories permit and Active Directory does not
 */
public record IdentityProviderView(
        UUID id,
        String key,
        IdentityProviderKind kind,
        String displayName,
        String issuerUri,
        String clientId,
        boolean clientSecretSet,
        String bindDn,
        boolean bindPasswordSet,
        boolean startTls,
        String userSearchBase,
        String userSearchFilter,
        String userIdAttribute,
        String emailAttribute,
        String displayNameAttribute,
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
                provider.getBindDn(),
                provider.getBindPasswordEncrypted() != null,
                provider.isStartTls(),
                provider.getUserSearchBase(),
                provider.getUserSearchFilter(),
                provider.getUserIdAttribute(),
                provider.getEmailAttribute(),
                provider.getDisplayNameAttribute(),
                provider.isJitProvisioning(),
                provider.isAutoLinkVerifiedEmail(),
                provider.getGroupClaim(),
                provider.isEnabled(),
                provider.getCreatedAt(),
                provider.getUpdatedAt());
    }
}
