package com.tcms.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How this instance talks to a customer's directory, and how much it will take from a stranger.
 *
 * <p>{@link OidcProperties}' lesson one protocol over: <strong>the host was chosen by somebody
 * outside this deployment.</strong> A directory that accepts a connection and never answers holds a
 * request thread until the container's own limit, so an unreachable or hostile server is a denial
 * of service against the whole instance rather than a failed sign-in.
 *
 * @param connectTimeout how long to wait for the directory to accept a connection
 * @param readTimeout    how long to wait for it to answer once it has. The one that matters
 * @param maxBindAttempts how many failures for one identifier at one provider before this instance
 *                       refuses <strong>without binding</strong>. Unlike local login, which has no
 *                       lockout by decision, every failed bind here increments the
 *                       <em>customer's</em> directory counter — so an unthrottled endpoint is a way
 *                       to lock a company's staff out of Active Directory from the internet, and
 *                       the harm lands on a system this product does not own
 * @param bindLockout    how long that refusal lasts
 */
@ConfigurationProperties(prefix = "tcms.ldap")
@Validated
public record LdapProperties(
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Positive int maxBindAttempts,
        @NotNull Duration bindLockout) {
}
