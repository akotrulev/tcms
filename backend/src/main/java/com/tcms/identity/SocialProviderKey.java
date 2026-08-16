package com.tcms.identity;

/**
 * Matches {@code ck_user_social_identity_provider} (V58). Instance-level authorities for
 * people — deliberately not a member of {@link IdentityProviderKind}, which enumerates a
 * company's own issuers.
 */
public enum SocialProviderKey {
    GITHUB,
    GITLAB,
    GOOGLE
}
