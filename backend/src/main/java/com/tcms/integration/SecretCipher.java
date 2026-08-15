package com.tcms.integration;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts and decrypts an integration credential — <strong>the only place in this codebase that
 * stores something recoverable</strong>.
 *
 * <h2>Why this is not a digest, unlike every other secret here</h2>
 *
 * <p>An API token is checked, so {@code V36} stores a digest and a stolen backup yields nothing.
 * A webhook URL is <em>used</em>: the server has to present the value itself, so there is no
 * arrangement in which the stored form is one-way. Encryption is what is available, and its
 * property is different and worth stating plainly — a stolen database alone is not enough, and a
 * stolen database plus the environment is.
 *
 * <h2>AES-GCM, and the nonce is part of the ciphertext</h2>
 *
 * <p>GCM rather than CBC because it authenticates: a tampered ciphertext fails to decrypt instead
 * of yielding plausible bytes, so a row somebody edited in the database is a loud failure rather
 * than a POST to an attacker's URL.
 *
 * <p>The 12-byte nonce is prepended to the ciphertext rather than stored in its own column. It is
 * not a secret, it must never repeat under one key, and keeping it in the same value is what makes
 * that impossible to get wrong by copying a row between columns. A separate column is one more
 * thing a migration can forget to carry.
 */
@Component
class SecretCipher {

    /** The size GCM is specified for; a different length is legal and slower for no benefit. */
    private static final int NONCE_BYTES = 12;

    /** Bits, and the full tag: a truncated tag is a weaker forgery bound for no saved storage. */
    private static final int TAG_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final IntegrationProperties properties;
    private final SecureRandom random = new SecureRandom();

    SecretCipher(IntegrationProperties properties) {
        this.properties = properties;
    }

    /** @return nonce ‖ ciphertext ‖ tag, which is what {@code notification_channel.secret} holds */
    byte[] encrypt(String plaintext) {
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
            // AES-GCM is in every JRE this runs on, and the key length is validated at bind time,
            // so reaching here means the platform is not the one this was built against.
            throw new IllegalStateException("AES-GCM is unavailable in this JVM", impossible);
        }
    }

    /**
     * The plaintext.
     *
     * <p><strong>The message names no value.</strong> A failure here means the key changed, the
     * row was tampered with, or a backup was restored across a key rotation — and the natural
     * diagnostic, printing what was decrypted, would put a credential into a log on the one path
     * where somebody is already looking at the logs.
     */
    String decrypt(byte[] stored) {
        if (stored == null || stored.length <= NONCE_BYTES) {
            throw new IllegalStateException(
                    "A channel secret is missing or too short to contain a nonce");
        }
        byte[] nonce = Arrays.copyOfRange(stored, 0, NONCE_BYTES);
        byte[] sealed = Arrays.copyOfRange(stored, NONCE_BYTES, stored.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, properties.aesKey(), new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "A channel secret could not be decrypted — the key has changed, the row was "
                            + "altered, or this database was restored across a key rotation",
                    failure);
        }
    }
}
