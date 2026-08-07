package com.tcms.identity;

import org.springframework.stereotype.Component;

/**
 * Mints and digests API-token secrets — the one place either happens for this credential.
 *
 * <h2>It delegates rather than duplicating, and that is the point</h2>
 *
 * <p>{@link InvitationTokens} already owns the CSPRNG and the digest, and its header argues both
 * choices in full: 32 bytes from {@link java.security.SecureRandom} rather than a UUID (122
 * effective bits, six of them constant format markers), and bare unsalted SHA-256 rather than
 * BCrypt, because this is not a password — it comes from a CSPRNG, has no dictionary behind it, and
 * <strong>a salted hash cannot be queried by</strong>, so redemption would have to scan every
 * outstanding credential and verify each one.
 *
 * <p>Every word of that applies here, harder: an API token is verified on <em>every CI request</em>
 * rather than once at redemption, so a slow KDF would be a bcrypt round per request. So there is one
 * implementation of the digest in this codebase and two policies on top of it. A second copy of
 * {@code MessageDigest.getInstance("SHA-256")} is how the two credentials end up hashed differently
 * and nobody notices until one of them cannot be looked up.
 *
 * <p>What this type adds is the <strong>presentation</strong>, and it is not cosmetic:
 *
 * <ul>
 *   <li>the {@link #PREFIX} makes a leaked secret greppable — a scanner can recognise one in a
 *       commit, a log or a paste without knowing this instance exists;</li>
 *   <li>{@link NewSecret#prefix()} is what a list shows, because the plaintext is returned once and
 *       is unrecoverable afterwards.</li>
 * </ul>
 */
@Component
public class ApiTokenSecrets {

    /**
     * The literal every secret starts with. Deliberately a recognisable, product-specific marker:
     * secret-scanning tools match on exactly this shape, and a credential nobody can pattern-match
     * is one that stays in a repository until it is used against you.
     */
    public static final String PREFIX = "tcms_";

    private final InvitationTokens digests;

    public ApiTokenSecrets(InvitationTokens digests) {
        this.digests = digests;
    }

    /**
     * A freshly minted secret, its digest, and the prefix that will stand in for it afterwards.
     *
     * <p>The three exist as one value for {@link InvitationTokens.NewToken}'s reason — so a caller
     * cannot store a digest and forget to return the secret, or return a secret whose digest was
     * never stored.
     *
     * @param secret the plaintext, shown to the issuer exactly once. <strong>Never log this.</strong>
     * @param hash   hex SHA-256 of {@code secret}, for {@code api_token_lookup}
     * @param prefix the leading {@value ApiToken#PREFIX_LENGTH} characters, for the list
     */
    public record NewSecret(String secret, String hash, String prefix) {
    }

    public NewSecret generate() {
        String secret = PREFIX + digests.generate().secret();
        return new NewSecret(secret, digests.hash(secret), prefixOf(secret));
    }

    /** The lookup key for a secret a caller presented. */
    public String hash(String secret) {
        return digests.hash(secret);
    }

    /**
     * Whether a presented string is even shaped like one of ours.
     *
     * <p>Checked before the digest so an {@code Authorization} header holding a session JWT — which
     * is the overwhelmingly common case on this instance — is not hashed and looked up on every
     * request. A cheap prefix test, never a security boundary: the digest lookup is the boundary.
     */
    public boolean looksLikeApiToken(String presented) {
        return presented != null && presented.startsWith(PREFIX);
    }

    private static String prefixOf(String secret) {
        return secret.substring(0, Math.min(ApiToken.PREFIX_LENGTH, secret.length()));
    }
}
