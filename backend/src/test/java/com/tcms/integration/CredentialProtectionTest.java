package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Everything that keeps a webhook URL from being readable — the cipher, the hint and the
 * redaction.
 *
 * <h2>No database and no Spring context, deliberately</h2>
 *
 * <p>§150's pure lane. Every subject here is a pure function of its input, and the failures being
 * pinned are failures of <em>text</em>: a leak in a message, a hint that is not a mask, a key
 * length nobody checked. None of them needs a container, and a rule that only runs when Docker is
 * up is one that gets skipped exactly when somebody is in a hurry.
 *
 * <h2>The redaction cases are the point of this class</h2>
 *
 * <p>A leak into {@code notification_delivery.error} is invisible in every green run: nothing
 * fails, no test goes red, and a live credential sits in plaintext in a table an operator browses,
 * a backup copies and a support bundle exports. There is no observation of the running product
 * that would reveal it, which is precisely why it needs a test rather than a review.
 */
@DisplayName("Credential protection — the cipher, the hint and the redaction")
class CredentialProtectionTest {

    /** Base64 of exactly 32 bytes, as {@link IntegrationProperties} requires. */
    private static final String KEY =
            Base64.getEncoder().encodeToString("unit-test-key-of-thirty-two-byte".getBytes(StandardCharsets.UTF_8));

    private static final String WEBHOOK =
            "https://hooks.slack.com/services/T00000000/B00000000/XyZ1aBcDeFgHiJkLmNoPqRsT";

    private final SecretCipher cipher = new SecretCipher(new IntegrationProperties(KEY));

    @Nested
    @DisplayName("The cipher")
    class Cipher {

        @Test
        @DisplayName("a webhook URL survives a round trip unchanged")
        void roundTrips() {
            assertThat(cipher.decrypt(cipher.encrypt(WEBHOOK))).isEqualTo(WEBHOOK);
        }

        @Test
        @DisplayName("the stored bytes contain no fragment of the plaintext")
        void theCiphertextIsNotThePlaintext() {
            String stored = new String(cipher.encrypt(WEBHOOK), StandardCharsets.ISO_8859_1);

            assertThat(stored)
                    .as("the column is what a stolen backup contains")
                    .doesNotContain("hooks.slack.com")
                    .doesNotContain("XyZ1aBcDeFgHiJkLmNoPqRsT")
                    .doesNotContain("https");
        }

        @Test
        @DisplayName("encrypting the same URL twice gives different bytes — the nonce is fresh")
        void theNonceIsNotReused() {
            // A repeated nonce under one key is what makes AES-GCM catastrophically weak rather
            // than merely imperfect, and a hard-coded one is the mistake that looks tidiest.
            // Two channels configured with the same webhook are the ordinary way it would show.
            assertThat(cipher.encrypt(WEBHOOK)).isNotEqualTo(cipher.encrypt(WEBHOOK));
        }

        @Test
        @DisplayName("a tampered ciphertext is refused rather than decrypted into something")
        void tamperingIsRefused() {
            // GCM authenticates, which is the reason it was chosen over CBC: a row somebody edited
            // in the database must be a loud failure and not a POST to an address of their
            // choosing.
            byte[] stored = cipher.encrypt(WEBHOOK);
            stored[stored.length - 1] ^= 0x01;

            assertThatThrownBy(() -> cipher.decrypt(stored))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be decrypted");
        }

        @Test
        @DisplayName("a failure names no value — the diagnostic must not be the credential")
        void theFailureNamesNothing() {
            SecretCipher other = new SecretCipher(new IntegrationProperties(
                    Base64.getEncoder().encodeToString(
                            "a-different-key-of-thirty-two-by".getBytes(StandardCharsets.UTF_8))));
            byte[] stored = cipher.encrypt(WEBHOOK);

            assertThatThrownBy(() -> other.decrypt(stored))
                    .hasMessageNotContaining("hooks.slack.com")
                    .hasMessageNotContaining(WEBHOOK);
        }

        @Test
        @DisplayName("a key that is not 32 bytes is refused at bind time, not padded")
        void aShortKeyIsRefused() {
            String short16 = Base64.getEncoder()
                    .encodeToString("sixteen-byteskey".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> new IntegrationProperties(short16))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32 bytes");
            assertThatThrownBy(() -> new IntegrationProperties("not base64 at all !!"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Base64");
            assertThatThrownBy(() -> new IntegrationProperties(" "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TCMS_INTEGRATION_SECRET_KEY");
        }
    }

    @Nested
    @DisplayName("The hint")
    class Hint {

        @Test
        @DisplayName("it keeps the tail, which is the part that discriminates")
        void itKeepsTheTail() {
            String hint = SecretHint.of(WEBHOOK);

            assertThat(hint).isEqualTo("…qRsT");
            assertThat(WEBHOOK).endsWith("qRsT");
        }

        @Test
        @DisplayName("it carries no usable fragment of the URL")
        void itIsNotReplayable() {
            assertThat(SecretHint.of(WEBHOOK))
                    .doesNotContain("hooks.slack.com")
                    .doesNotContain("services")
                    .hasSizeLessThan(8);
        }

        @Test
        @DisplayName("no secret is null and never the empty string — one spelling of nothing")
        void absenceHasOneSpelling() {
            assertThat(SecretHint.of(null)).isNull();
            assertThat(SecretHint.of("   ")).isNull();
        }

        @Test
        @DisplayName("a secret too short to mask shows none of itself")
        void aTinySecretShowsNothing() {
            assertThat(SecretHint.of("abc")).isEqualTo("…");
        }
    }

    @Nested
    @DisplayName("The redaction — the leak that is invisible in a green run")
    class Redaction {

        @Test
        @DisplayName("the exact secret is removed wherever a transport quoted it")
        void theKnownSecretGoes() {
            String message = "I/O error on POST request for \"" + WEBHOOK + "\": Connection refused";

            String redacted = SecretRedaction.apply(message, WEBHOOK);

            assertThat(redacted).doesNotContain(WEBHOOK);
            assertThat(redacted).contains(SecretRedaction.REPLACEMENT);
        }

        @Test
        @DisplayName("a URL nobody passed in loses its path too — a partial quote is still a leak")
        void anyUrlLosesItsPath() {
            // The second pass, and it is what covers the case the first cannot: a client that
            // quotes only part of the URL, a redirect target, or a proxy naming a second webhook.
            String message = "302 to https://hooks.slack.com/services/T1/B2/SeCrEtPaRt";

            String redacted = SecretRedaction.apply(message, null);

            assertThat(redacted).doesNotContain("SeCrEtPaRt");
            assertThat(redacted)
                    .as("which host refused is the part an operator actually needs")
                    .contains("hooks.slack.com");
        }

        @Test
        @DisplayName("a message with no URL and no secret is left alone")
        void anOrdinaryMessageSurvives() {
            String message = "Connection timed out after 10000 ms";

            assertThat(SecretRedaction.apply(message, WEBHOOK)).isEqualTo(message);
        }

        @Test
        @DisplayName("a null message stays null rather than becoming the string \"null\"")
        void nullSurvives() {
            assertThat(SecretRedaction.apply(null, WEBHOOK)).isNull();
        }
    }
}
