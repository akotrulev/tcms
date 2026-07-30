package com.tcms.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How session tokens are signed and how long they last.
 *
 * @param secret       the HMAC signing key. <strong>Required, with no default and a
 *                     minimum length</strong> — a defaulted signing key is a forged
 *                     session for anyone who can read this repository, and it is the kind
 *                     of default that survives all the way to production because nothing
 *                     ever fails because of it. 32 bytes is HS256's block size; a shorter
 *                     key is silently zero-padded by the JCA, so "it worked" would prove
 *                     nothing about its strength.
 * @param issuer       stamped as {@code iss} and required on validation, so a token minted
 *                     by another system that happens to share a key is still refused.
 * @param ttl          how long a normal session lasts.
 * @param rememberMeTtl how long a "remember me" session lasts.
 */
@ConfigurationProperties(prefix = "tcms.auth.jwt")
@Validated
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "must be at least 32 characters — HS256 keys "
                + "shorter than the hash block size are padded, not strengthened") String secret,
        @NotBlank String issuer,
        @NotNull Duration ttl,
        @NotNull Duration rememberMeTtl) {

    /**
     * Plan §6b specifies a ~10-minute access token, and that number belongs to a token that
     * <strong>carries grant claims</strong>: stale authorization is the risk a short TTL
     * buys down. This token carries none — every request re-resolves through
     * {@code PermissionEvaluator} behind the account, membership and company-status gates,
     * and the {@code pv} epoch invalidates a session the moment authorization changes.
     *
     * <p>So the default is hours rather than minutes, deliberately, and
     * <strong>the coupling is recorded here because it reverses</strong>: when
     * {@code sys}/{@code cmp}/{@code prj} move into the token, this TTL comes down with
     * them and a refresh flow arrives alongside. Leaving an 8-hour TTL in place at that
     * point would be the actual defect.
     */
    public Duration ttlFor(boolean rememberMe) {
        return rememberMe ? rememberMeTtl : ttl;
    }
}
