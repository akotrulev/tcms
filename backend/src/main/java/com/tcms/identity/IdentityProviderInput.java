package com.tcms.identity;

/**
 * What a Company Admin submits to register or reconfigure a provider.
 *
 * <p>No {@code companyId} and no {@code id} — the company comes from the session's context and the
 * id from the path, because request input never selects the tenancy discriminator (ERD §0.1
 * point 2).
 *
 * <p><strong>Two halves, and {@link #kind} says which one is read.</strong> An OIDC provider
 * populates {@code clientId} and {@code clientSecret}; a directory populates the eight fields from
 * {@code bindDn} to {@code displayNameAttribute}. {@code ck_identity_provider_shape} (V57) refuses a
 * row that mixes them, and {@code IdentityProviderService.validate} refuses the request before it
 * gets that far.
 *
 * @param key         stable, unique within the company, and <strong>set once</strong>: it is what
 *                    a client's own documentation and bookmarks name, so an edit that changed it
 *                    would break links nobody here can see. Ignored on update
 * @param kind        {@code OIDC} or {@code LDAP} — a redirect to a third party that collects the
 *                    password, or a bind against a directory with the password collected here
 *                    (#40 ruled SAML out entirely). Set once; ignored on update
 * @param displayName the label on the sign-in button. <strong>The only string discovery puts on
 *                    the wire</strong> to an anonymous caller, so it is the client's own to choose
 *                    and their own to disclose
 * @param issuerUri   where the authority lives, and its meaning follows {@code kind}. OIDC: the
 *                    issuer, {@code https}, because the discovery document and therefore every
 *                    signing key is fetched from it. LDAP: the directory URL, {@code ldaps://} or
 *                    {@code ldap://} with {@code startTls} — a plain bind puts the person's own
 *                    directory password on the wire and there is no flag that permits it
 * @param clientId    OIDC only: the relying-party identifier the issuer knows this instance by, and
 *                    the audience every {@code id_token} is checked against. Null on a directory
 * @param clientSecret the plaintext, sent once when it is set or rotated. <strong>Null keeps the
 *                    stored secret</strong> rather than clearing it, because no read path can echo
 *                    it back and a caller therefore has nothing to resend. Blank on a public
 *                    client, which uses PKCE alone
 * @param bindDn      LDAP only: the service account the search runs as, before the person's own
 *                    bind. Null means an anonymous search, which some directories permit and Active
 *                    Directory does not
 * @param bindPassword the plaintext service-account password. <strong>Null keeps the stored
 *                    one</strong>, for {@code clientSecret}'s reason exactly
 * @param startTls    LDAP only: whether to upgrade an {@code ldap://} connection. An
 *                    {@code ldaps://} URL is already encrypted and does not use it
 * @param userSearchBase LDAP only: the subtree searched for the person signing in
 * @param userSearchFilter LDAP only, and it <strong>must contain {@code {0}}</strong> — that is
 *                    where the typed address is substituted with RFC 4515 escaping applied.
 *                    Interpolating it any other way is filter injection on a surface an anonymous
 *                    caller reaches
 * @param userIdAttribute LDAP only: which attribute holds the directory's immutable identifier —
 *                    {@code objectGUID} on Active Directory, {@code entryUUID} on OpenLDAP.
 *                    <strong>Never a DN</strong>, which encodes tree position, so moving somebody
 *                    between OUs would orphan their account and their authorship
 * @param emailAttribute LDAP only: which attribute carries the address
 * @param displayNameAttribute LDAP only: which attribute carries the person's name
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
 *                    configuring an authority and turning it on are two acts
 */
public record IdentityProviderInput(
        String key,
        IdentityProviderKind kind,
        String displayName,
        String issuerUri,
        String clientId,
        String clientSecret,
        String bindDn,
        String bindPassword,
        Boolean startTls,
        String userSearchBase,
        String userSearchFilter,
        String userIdAttribute,
        String emailAttribute,
        String displayNameAttribute,
        Boolean jitProvisioning,
        Boolean autoLinkVerifiedEmail,
        String groupClaim,
        Boolean enabled) {
}
