package com.tcms.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The four fields of an issuer's {@code openid-configuration} this flow uses. It publishes around
 * thirty; the rest describe capabilities we do not exercise, and a field nothing reads is a field
 * that cannot be wrong here.
 *
 * @param issuer                the issuer's own name for itself. <strong>Compared against the
 *                              configured {@code issuer_uri}</strong> rather than trusted: the
 *                              document was fetched from a URL an administrator typed, and an
 *                              issuer whose metadata claims a different identity is either
 *                              misconfigured or is not the party the client meant to trust
 * @param authorizationEndpoint where the browser is sent
 * @param tokenEndpoint         where the code is exchanged, server to server
 * @param jwksUri               where the signing keys live
 */
public record OidcDiscoveryDocument(
        @JsonProperty("issuer") String issuer,
        @JsonProperty("authorization_endpoint") String authorizationEndpoint,
        @JsonProperty("token_endpoint") String tokenEndpoint,
        @JsonProperty("jwks_uri") String jwksUri) {

    /** Whether the document is usable at all — every field this flow needs is present. */
    public boolean isComplete() {
        return notBlank(issuer) && notBlank(authorizationEndpoint)
                && notBlank(tokenEndpoint) && notBlank(jwksUri);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
