package com.tcms.integration;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The key every integration credential in this instance is encrypted with (plan §6r).
 *
 * <h2>No default of any kind, and the spelling is the point</h2>
 *
 * <p>{@code application.yml} says {@code ${TCMS_INTEGRATION_SECRET_KEY}} with <strong>no
 * colon</strong>. {@code ${VAR:?message}} is POSIX-shell and Compose syntax that Spring does not
 * implement — it splits on the first {@code :} and takes the rest as the default — and this
 * repository has already paid for that once, at full price: the JWT signing key resolved to its
 * own error message for a year, in production, past a correct comment and a real
 * {@code @Size(min = 32)}. An unresolvable placeholder throws at bind time and the context does
 * not start, which is the only behaviour worth having for a key that decrypts every webhook in
 * every company.
 *
 * <p>{@code ConfigConventionTest} reads the shipped text rather than the resolved value, because
 * every test in this suite supplies its own key and therefore cannot see a defect in the
 * expression that actually ships.
 *
 * <h2>Why a key and not a passphrase</h2>
 *
 * <p>32 raw bytes, Base64-encoded. Accepting an arbitrary passphrase and hashing it to a key is
 * the friendlier option and it hides the one thing an operator needs to know — that a short,
 * guessable value produces a perfectly valid AES key and a credential store anybody can open. A
 * refusal at startup naming the length is a fixable inconvenience; a weak key is not detectable
 * from anything the running product does.
 *
 * @param secretKey Base64 of exactly 32 bytes — {@code openssl rand -base64 32}
 */
@ConfigurationProperties(prefix = "tcms.integration")
@Validated
public record IntegrationProperties(String secretKey) {

    /** AES-256. Not configurable: a per-deployment cipher strength is a footgun with a knob on it. */
    private static final int KEY_LENGTH_BYTES = 32;

    /**
     * Compact constructor, because Bean Validation can say "not blank" and nothing else useful
     * here — the question is whether the string decodes to the right number of bytes.
     */
    public IntegrationProperties {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException(
                    "tcms.integration.secret-key is required — set TCMS_INTEGRATION_SECRET_KEY to "
                            + "the output of `openssl rand -base64 32`. Every notification channel "
                            + "credential in this instance is encrypted with it.");
        }
        decode(secretKey);
    }

    /** The key itself. Decoded per call rather than cached, so nothing holds it as a field. */
    SecretKey aesKey() {
        return new SecretKeySpec(decode(secretKey), "AES");
    }

    private static byte[] decode(String value) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException(
                    "tcms.integration.secret-key must be Base64 — `openssl rand -base64 32`",
                    notBase64);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "tcms.integration.secret-key must decode to exactly " + KEY_LENGTH_BYTES
                            + " bytes, was " + decoded.length + ". A short key is a valid AES key "
                            + "and an unsafe one, so this is refused rather than padded.");
        }
        return decoded;
    }
}
