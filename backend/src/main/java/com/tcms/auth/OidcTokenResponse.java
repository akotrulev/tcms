package com.tcms.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What the token endpoint answered.
 *
 * <p>Only {@link #idToken} is kept, and that is the design rather than an omission: TCMS issues its
 * own session at the end of this flow (§6r), so the issuer's access and refresh tokens have nothing
 * here to authorize and no place to be stored. Parsing them into fields nothing reads would be an
 * invitation to persist them later.
 */
public record OidcTokenResponse(@JsonProperty("id_token") String idToken) {
}
