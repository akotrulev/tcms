package com.tcms.auth;

import jakarta.validation.constraints.AssertTrue;
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
 *                     nothing about its strength. <strong>Length is not the only bar</strong>
 *                     — see {@link #isSecretUsable()}, which refuses the key §108 actually
 *                     shipped on. It is 59 characters and satisfies everything above.
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
     * The key this application actually signed with from §18 until 2026-08-06.
     *
     * <p>Not a secret and never was one — it is in this repository three more times, in
     * {@code application.yml}'s comment block, {@link com.tcms.config.ConfigConventionTest}'s
     * header and the rotation plan. It is here so that the application can <em>refuse</em> it,
     * which is the half §108 left undone: the text checks stop the placeholder being
     * respelled, and nothing at all stops the resulting value being supplied.
     *
     * <p>{@code ConfigConventionTest.theRefusedKeyIsTheOneThatShipped} pins this against
     * {@code application.yml}'s record of it, so the constant cannot become an approximation
     * of the incident it names.
     */
    static final String PUBLISHED_KEY =
            "?a JWT signing secret of at least 32 characters is required";

    /**
     * Refuses a signing key that is published, or that is a shell placeholder Spring resolved.
     *
     * <p><strong>This is the check §108 did not leave behind.</strong> That slice fixed the
     * spelling ({@code ${VAR}}, no colon) and wrote {@code ConfigConventionTest} to keep the
     * spelling fixed — both rules over the <em>source text</em>. Neither can see the value an
     * environment file supplies, and the failing state is reached from there in three ordinary
     * ways: a pre-§108 backup restored, an operator resolving a startup failure by pasting the
     * value out of the repository, or an env file written by copying the old compose line.
     * {@link #PUBLISHED_KEY} clears {@code @NotBlank} and {@code @Size} at 59 characters, so
     * until this method existed every one of those booted a production instance signing
     * forgeable sessions, exactly as before.
     *
     * <p>The second half is the <strong>class</strong> rather than the instance, which is the
     * more useful half. Spring splits a placeholder on the first {@code :} and takes the rest
     * verbatim, so {@code ${VAR:?msg}} resolves to {@code ?msg} and {@code ${VAR:-x}} to
     * {@code -x}: <em>a signing key beginning with {@code ?} or {@code -} is the residue of a
     * shell form Spring does not implement, not a key.</em> Any future property written that
     * way lands here rather than in an incident. A generated key cannot collide — base64
     * output begins with {@code [A-Za-z0-9+/]} — and a hand-chosen passphrase that genuinely
     * starts with one of the two characters is refused for the price of generating another,
     * which is the correct side to be wrong on.
     *
     * <p>What it deliberately does <strong>not</strong> refuse is
     * {@code .env.example}'s {@code local-development-signing-key-not-for-any-server}. That
     * file states its own position — disposable container, bound to {@code 127.0.0.1}, "not a
     * template for a server" — and says the same thing about three database passwords with
     * exactly the same shape. Refusing one of the four would break the documented
     * {@code cp .env.example .env} flow and leave the argument for the other three untouched.
     * The decision belongs to that file, not to this method.
     */
    @AssertTrue(message = "must not be a signing key that is published in the repository, or a "
            + "`${VAR:?…}` / `${VAR:-…}` shell default that Spring resolved into a literal "
            + "value — such a key begins with `?` or `-`. This is the §108 defect, which "
            + "signed every session token with a string anyone who could read the repository "
            + "could forge with. Generate one with `openssl rand -base64 48` and put it in the "
            + "environment file; see docs/plans/2026-08-07-linux-gate-and-key-rotation.md")
    public boolean isSecretUsable() {
        if (secret == null || secret.isBlank()) {
            // @NotBlank owns that failure and says so better. Two messages for one cause reads
            // as two problems.
            return true;
        }
        return !secret.equals(PUBLISHED_KEY)
                && !secret.startsWith("?")
                && !secret.startsWith("-");
    }

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
