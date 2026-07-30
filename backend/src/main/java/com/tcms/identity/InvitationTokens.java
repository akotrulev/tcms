package com.tcms.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Mints and digests invitation secrets — the one place either happens.
 *
 * <h2>Why 32 bytes, and why not a UUID</h2>
 *
 * <p>An invitation token is a bearer credential: whoever holds it becomes the
 * administrator of a client company. {@link SecureRandom} gives 256 bits of entropy, so
 * guessing is not a threat model. {@link java.util.UUID#randomUUID()} would have been the
 * reflex and it is weaker in two ways — 122 effective bits, and six of its bits are
 * constant version/variant markers that advertise the format.
 *
 * <h2>Why the digest is bare SHA-256</h2>
 *
 * <p>Deliberately <em>not</em> BCrypt, which is the instinct because this looks like a
 * password. It is not: a password is chosen by a human from a small space and must be made
 * expensive to guess, while this secret comes from a CSPRNG and has no dictionary behind
 * it. A slow KDF would add latency to what has to be an indexed equality lookup, and would
 * make the token unfindable — a salted hash cannot be queried by, so redemption would have
 * to scan every outstanding invitation and verify each one.
 *
 * <p>Unsalted is safe for the same reason: with 256 bits of entropy there is no rainbow
 * table and no cross-row correlation to gain.
 */
@Component
public class InvitationTokens {

    /** 256 bits. Base64url-encodes to 43 characters, which still fits a URL comfortably. */
    private static final int SECRET_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    /**
     * A freshly minted secret and its digest.
     *
     * <p>The pair exists as one value so a caller cannot persist the digest and forget to
     * deliver the secret, or deliver a secret whose digest was never stored. The secret is
     * returned exactly once and is unrecoverable afterwards — {@link MembershipInvitation}
     * holds only {@link #hash}.
     *
     * @param secret the plaintext, for the invitation link. <strong>Never log this.</strong>
     * @param hash   hex SHA-256 of {@code secret}, for storage and lookup
     */
    public record NewToken(String secret, String hash) {
    }

    public NewToken generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        String secret = encoder.encodeToString(bytes);
        return new NewToken(secret, hash(secret));
    }

    /** The lookup key for a secret a caller presented. */
    public String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-256. If one does not, failing loudly is
            // the only correct outcome — a fallback digest here would silently weaken the
            // one thing standing between a stranger and a client's administrator account.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }
}
