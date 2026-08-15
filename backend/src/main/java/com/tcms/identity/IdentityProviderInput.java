package com.tcms.identity;

/**
 * What a Company Admin submits to register or reconfigure a provider.
 *
 * <p>No {@code companyId} and no {@code id} — the company comes from the session's context and the
 * id from the path, because request input never selects the tenancy discriminator (ERD §0.1
 * point 2).
 *
 * @param key         stable, unique within the company, and <strong>set once</strong>: it is what
 *                    a client's own documentation and bookmarks name, so an edit that changed it
 *                    would break links nobody here can see. Ignored on update
 * @param kind        {@code OIDC} today; {@code LDAP} is the schema's other member and has no
 *                    implementation yet (#40 ruled SAML out entirely)
 * @param displayName the label on the sign-in button. <strong>The only string discovery puts on
 *                    the wire</strong> to an anonymous caller, so it is the client's own to choose
 *                    and their own to disclose
 * @param issuerUri   must be {@code https}; the discovery document and therefore every signing key
 *                    is fetched from it
 * @param clientId    the relying-party identifier the issuer knows this instance by, and the
 *                    audience every {@code id_token} is checked against
 * @param clientSecret the plaintext, sent once when it is set or rotated. <strong>Null keeps the
 *                    stored secret</strong> rather than clearing it, because no read path can echo
 *                    it back and a caller therefore has nothing to resend. Blank on a public
 *                    client, which uses PKCE alone
 * @param jitProvisioning whether a first-time signer with no invitation gets an account. Defaults
 *                    off (#38): an IdP authoritative for one organisation is not authoritative for
 *                    who may enter a client's company
 * @param autoLinkVerifiedEmail whether a <em>verified</em> address may bind a new subject to an
 *                    existing account. Off by default, because on it the issuer's word about an
 *                    address is enough to reach somebody else's authorship
 * @param groupClaim  which claim carries group membership. Stored and not yet consulted — the
 *                    claim-to-role mapping is its own plan, and the column is here so that plan
 *                    needs no migration against this table
 * @param enabled     whether the provider is a live sign-in route. Always false on registration:
 *                    configuring an issuer and turning it on are two acts
 */
public record IdentityProviderInput(
        String key,
        IdentityProviderKind kind,
        String displayName,
        String issuerUri,
        String clientId,
        String clientSecret,
        Boolean jitProvisioning,
        Boolean autoLinkVerifiedEmail,
        String groupClaim,
        Boolean enabled) {
}
