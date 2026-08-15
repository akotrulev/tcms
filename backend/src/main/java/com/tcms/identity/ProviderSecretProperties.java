package com.tcms.identity;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The key every OIDC client secret in this instance is encrypted with (§6r).
 *
 * <p>A <strong>second</strong> key rather than {@code tcms.integration.secret-key}, and the reason
 * is the module boundary rather than cryptography: {@code identity} may not depend on
 * {@code integration}, so it cannot reach that key or the cipher that uses it. Separate keys are
 * the better answer anyway — the blast radius of a rotation is one subsystem, and an operator who
 * loses the notification key has not also lost every company's sign-in configuration.
 *
 * <p>{@code application.yml} says {@code ${TCMS_IDENTITY_SECRET_KEY}} with <strong>no colon</strong>,
 * for §108's reason: {@code ${VAR:?message}} is shell and Compose syntax that Spring reads as a
 * <em>default</em>, and this repository has already shipped a production signing key that was the
 * text of its own error message.
 *
 * @param providerSecretKey Base64 of exactly 32 bytes — {@code openssl rand -base64 32}
 */
@ConfigurationProperties(prefix = "tcms.identity")
@Validated
public record ProviderSecretProperties(String providerSecretKey) {

    /** AES-256. Not configurable: a per-deployment cipher strength is a footgun with a knob on it. */
    private static final int KEY_LENGTH_BYTES = 32;

    /**
     * Compact constructor, because Bean Validation can say "not blank" and nothing else useful
     * here — the question is whether the string decodes to the right number of bytes.
     */
    public ProviderSecretProperties {
        if (providerSecretKey == null || providerSecretKey.isBlank()) {
            throw new IllegalArgumentException(
                    "tcms.identity.provider-secret-key is required — set TCMS_IDENTITY_SECRET_KEY "
                            + "to the output of `openssl rand -base64 32`. Every OIDC client "
                            + "secret in this instance is encrypted with it.");
        }
        decode(providerSecretKey);
    }

    /** The key itself. Decoded per call rather than cached, so nothing holds it as a field. */
    SecretKey aesKey() {
        return new SecretKeySpec(decode(providerSecretKey), "AES");
    }

    private static byte[] decode(String value) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException(
                    "tcms.identity.provider-secret-key must be Base64 — `openssl rand -base64 32`",
                    notBase64);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "tcms.identity.provider-secret-key must decode to exactly " + KEY_LENGTH_BYTES
                            + " bytes, was " + decoded.length + ". A short key is a valid AES key "
                            + "and an unsafe one, so this is refused rather than padded.");
        }
        return decoded;
    }
}
