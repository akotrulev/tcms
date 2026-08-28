package com.tcms.integration.tracker;

/**
 * One person's decrypted credential, on its way to one call.
 *
 * <h2>It exists so the plaintext has a short, named life</h2>
 *
 * <p>{@link TrackerIdentity} holds ciphertext; an adapter needs the string. Passing the entity into
 * the adapter would put the cipher, the row and the transport in one place and make "where is the
 * plaintext" a question with several answers. This record is built immediately before a call, is
 * held by nothing, and is the one type an adapter ever sees a secret through.
 *
 * <p><strong>No {@code toString}.</strong> A record's generated one prints every component, and the
 * realistic leak is a log line or an exception message that interpolated an object somebody assumed
 * was safe. {@code SecretRedaction} covers the transport's own messages; this covers ours.
 *
 * @param accountId the person's tracker login, needed beside the token for Jira Cloud's Basic auth
 *                  and null for the two Bearer providers
 */
public record TrackerCredential(
        TrackerProvider provider, String baseUrl, String accountId, String secret) {

    @Override
    public String toString() {
        return "TrackerCredential[provider=" + provider + ", baseUrl=" + baseUrl + ", secret=***]";
    }
}
