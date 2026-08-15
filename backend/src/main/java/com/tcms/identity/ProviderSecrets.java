package com.tcms.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts and decrypts an OIDC client secret.
 *
 * <h2>Encryption, not hashing — and the neighbouring type does the opposite</h2>
 *
 * <p>{@link ApiTokenSecrets} and {@link InvitationTokens} store a SHA-256 <em>digest</em>, because
 * those credentials are only ever <strong>checked</strong>: a stolen backup yields nothing usable.
 * This one is <strong>presented</strong> — a token exchange sends the secret to the issuer — so
 * there is no arrangement in which the stored form is one-way, and reusing the digest here would
 * simply make the secret unusable. The asymmetry between two classes in one package is the
 * confusing part, which is why it is written down at both of them.
 *
 * <p>What encryption buys is a different and weaker property, worth stating plainly: a stolen
 * database alone is not enough, and a stolen database plus the environment is.
 *
 * <h2>This is the second AES-GCM implementation in the codebase</h2>
 *
 * <p>{@code integration.SecretCipher} is the first, byte-for-byte the same construction, and this
 * is a copy rather than a call because {@code identity} may not depend on {@code integration} and
 * the shared home for it does not exist: {@code api}'s charter takes contract shapes and refuses
 * services, and a sixth foundation module is a decision rather than an import (§76, §125, §146).
 * Recorded as a running tally in this package's tradition — <strong>AES-GCM ×2</strong> — so the
 * third caller arrives with the extraction rather than with another comment like this one.
 *
 * <p>The 12-byte nonce is prepended to the ciphertext rather than kept in its own column: it is not
 * a secret, it must never repeat under one key, and keeping it in the same value is what makes that
 * impossible to get wrong by copying a row between columns.
 */
@Component
public class ProviderSecrets {

    /** The size GCM is specified for; a different length is legal and slower for no benefit. */
    private static final int NONCE_BYTES = 12;

    /** Bits, and the full tag: a truncated tag is a weaker forgery bound for no saved storage. */
    private static final int TAG_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final ProviderSecretProperties properties;
    private final SecureRandom random = new SecureRandom();

    public ProviderSecrets(ProviderSecretProperties properties) {
        this.properties = properties;
    }

    /**
     * @return nonce ‖ ciphertext ‖ tag, which is what {@code identity_provider
     *         .client_secret_encrypted} holds, or {@code null} for a public client that has no
     *         secret at all
     */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, properties.aesKey(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] stored = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, stored, 0, nonce.length);
            System.arraycopy(sealed, 0, stored, nonce.length, sealed.length);
            return stored;
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("AES-GCM is unavailable in this JVM", impossible);
        }
    }

    /**
     * The plaintext, for the length of one token exchange.
     *
     * <p><strong>The message names no value</strong>, on either arm. A failure here means the key
     * changed, the row was altered, or a backup was restored across a rotation — and the natural
     * diagnostic, printing what was decrypted, would put a client secret into a log on the one path
     * where somebody is already reading the logs.
     *
     * @return {@code null} for a public client, which is a legitimate configuration rather than a
     *         failure — PKCE alone proves the exchange belongs to the browser that started it
     */
    public String decrypt(byte[] stored) {
        if (stored == null) {
            return null;
        }
        if (stored.length <= NONCE_BYTES) {
            throw new IllegalStateException(
                    "A provider client secret is too short to contain a nonce");
        }
        byte[] nonce = Arrays.copyOfRange(stored, 0, NONCE_BYTES);
        byte[] sealed = Arrays.copyOfRange(stored, NONCE_BYTES, stored.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, properties.aesKey(), new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "A provider client secret could not be decrypted — the key has changed, the "
                            + "row was altered, or this database was restored across a key rotation",
                    failure);
        }
    }
}
