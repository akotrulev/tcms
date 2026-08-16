package com.tcms.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How this instance talks to an external issuer.
 *
 * <p>Every value here bounds something an issuer controls and we do not. A provider is configured
 * by a client's own administrator, so the URL a request thread is about to block on was chosen by
 * somebody outside this deployment.
 *
 * @param connectTimeout how long to wait for the issuer to accept a connection
 * @param readTimeout    how long to wait for it to answer once it has. <strong>The one that
 *                       matters</strong>: an issuer that accepts and then never replies holds a
 *                       request thread until the container's own limit, so a misconfigured or
 *                       hostile endpoint is a denial of service against the whole instance rather
 *                       than a failed sign-in
 * @param discoveryTtl   how long a fetched {@code openid-configuration} is reused. Not zero,
 *                       because building the metadata per callback means an extra round trip to a
 *                       third party on every sign-in; not indefinite, because a rotated
 *                       {@code jwks_uri} must eventually be picked up without a restart
 * @param attemptValidity how long an in-flight authorization request stays resolvable. Short: the
 *                       row exists only for the seconds between the redirect and the callback, and
 *                       a long window is a long window in which a leaked {@code state} is still
 *                       worth replaying
 * @param apiBaseUrl     <strong>this API's</strong> public origin, which is what the
 *                       {@code redirect_uri} is built from. Not derived from the request:
 *                       {@code Host} is caller-supplied, and behind Caddy the container only ever
 *                       sees {@code localhost:8080}, so a request-derived value would either send
 *                       people nowhere or let a forged header nominate where an authorization code
 *                       is delivered. Deliberately separate from {@code tcms.invitation.base-url},
 *                       which is the <em>SPA's</em> origin and is a different host in development
 * @param spaCallbackPath where the browser lands afterwards, on the SPA
 */
@ConfigurationProperties(prefix = "tcms.oidc")
@Validated
public record OidcProperties(
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull Duration discoveryTtl,
        @NotNull Duration attemptValidity,
        @NotNull URI apiBaseUrl,
        @NotBlank String spaCallbackPath) {

    /**
     * The one {@code redirect_uri} this instance ever sends, and it is registered with the issuer.
     *
     * <p>Built here rather than at both call sites because the authorize leg and the token exchange
     * must send a <em>byte-identical</em> value — the issuer compares them and refuses the exchange
     * if they differ by so much as a trailing slash.
     */
    public String redirectUri() {
        return apiBaseUrl.toString().replaceAll("/+$", "") + "/api/v1/auth/sso/callback";
    }

    /**
     * {@link #redirectUri()}'s sibling for the social flow — a different callback because the
     * two flows resolve their attempts from different tables, and what is registered with a
     * social provider's OAuth app is exactly this value.
     */
    public String socialRedirectUri() {
        return apiBaseUrl.toString().replaceAll("/+$", "") + "/api/v1/auth/social/callback";
    }
}
