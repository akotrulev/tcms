package com.tcms.execution;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A validated upload, so nothing downstream re-decides what a filename or a content type is.
 *
 * <p>The filename is reduced to its last path segment on the way in ({@link #sanitizeFilename}). It
 * is only ever stored in a column and echoed in {@code Content-Disposition} — it never becomes part
 * of a storage key, which is built from three server-generated UUIDs — so a traversal name is
 * already harmless before this runs. It is reduced anyway, for two reasons: a client-supplied string
 * that <em>looks</em> like a path invites the next reader to use it as one, and
 * {@code "../../../etc/passwd"} rendered in the UI as an attachment name is alarming in a way the
 * bytes never were.
 */
record EvidenceUpload(
        String filename, String contentType, long sizeBytes, byte[] bytes, String caption) {

    /**
     * SHA-256 of the bytes, stored in {@code attachment.checksum}.
     *
     * <p>Nothing verifies it yet. It is recorded now because it can only be computed while the bytes
     * are in hand: without it, silent corruption in the blob store is undetectable forever after,
     * and a checksum added later can only ever attest to the file as it already is.
     */
    String checksum() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to provide SHA-256, so this arm cannot be reached; it is not
            // swallowed, because a checksum that silently became null would be worse than a crash.
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /**
     * The last segment of whatever the client called the file, defaulting when there is nothing
     * usable left. Handles both separators: a Windows client sends backslashes, and splitting on
     * {@code '/'} alone would keep {@code ..\..\passwd} intact.
     */
    static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "evidence";
        }
        String tail = raw.replace('\\', '/');
        tail = tail.substring(tail.lastIndexOf('/') + 1).trim();
        return tail.isBlank() ? "evidence" : tail;
    }

    /**
     * The filename as it can safely appear in a {@code Content-Disposition} header.
     *
     * <p>This is the one client-supplied string that reaches a response <em>header</em>, where a
     * carriage return is header injection rather than an odd filename. Quotes are stripped for the
     * same reason one level down: they would end the quoted string early.
     */
    static String headerSafeFilename(String filename) {
        return filename.replaceAll("[\"\\\\\\r\\n]", "_");
    }
}
