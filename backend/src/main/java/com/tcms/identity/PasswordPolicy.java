package com.tcms.identity;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The rules a chosen password must satisfy. One place, so activation and a future password
 * reset cannot disagree about what is acceptable.
 *
 * <h2>The maximum is not a style choice</h2>
 *
 * <p>BCrypt hashes at most <strong>72 bytes</strong> and <em>silently ignores</em>
 * everything beyond. Without this bound a 100-character passphrase is accepted, the user
 * believes all of it protects their account, and only the first 72 bytes are ever
 * verified — so two different passphrases sharing a 72-byte prefix both open the account.
 * Rejecting is the honest behaviour; truncating quietly is not.
 *
 * <p>It is measured in <strong>bytes, not characters</strong>, because the limit is on the
 * byte array BCrypt digests. A 40-character passphrase of non-Latin script can exceed 72
 * bytes in UTF-8, and a character-based check would let exactly those users past.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>No composition rules — no "one uppercase, one digit, one symbol". Length is the
 * property that resists guessing; composition rules push people towards
 * {@code Password1!} and are why NIST SP 800-63B dropped them. No dictionary/breach check
 * either, which would be a genuine improvement and needs a data source this deployment
 * does not have; recorded as a gap rather than pretended away.
 */
@Component
public class PasswordPolicy {

    static final int MIN_LENGTH = 12;

    /** BCrypt's own ceiling — see the class comment. */
    static final int MAX_BYTES = 72;

    /**
     * @return the reason it was rejected, or empty when acceptable. A returned reason is
     *         safe to show the caller: it describes the password they just typed, not
     *         anything about the account or the instance.
     */
    public Optional<String> rejectionReason(String password) {
        if (password == null || password.isEmpty()) {
            return Optional.of("A password is required.");
        }
        if (password.length() < MIN_LENGTH) {
            return Optional.of("The password must be at least " + MIN_LENGTH + " characters.");
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            return Optional.of("The password must be at most " + MAX_BYTES
                    + " bytes (this one is " + bytes + "). Longer passwords are rejected rather "
                    + "than silently truncated to the part that would actually be checked.");
        }
        return Optional.empty();
    }
}
