package com.tcms.identity;

/** Matches {@code ck_identity_provider_kind}. No SAML member — resolved question #40. */
public enum IdentityProviderKind {
    OIDC,
    LDAP
}
