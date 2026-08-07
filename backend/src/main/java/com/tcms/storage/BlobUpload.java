package com.tcms.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * A validated upload, so nothing downstream re-decides what a filename or a content type is.
 *
 * <p>This was {@code execution.EvidenceUpload}'s whole body until §126, package-private in the one
 * module that had an upload. #49 gives {@code attachment} three more owners in two more modules, and
 * a filename sanitizer written four times is four subtly different answers to "what is a filename" —
 * the {@code lib/labels.ts} rule, one layer down. What stayed behind in {@code execution} is the
 * only field that was ever evidence's: the caption.
 *
 * <h2>The filename is reduced on the way in, and it was already harmless</h2>
 *
 * <p>{@link #sanitizeFilename} reduces it to its last path segment. It is only ever stored in a
 * column and echoed in {@code Content-Disposition} — it never becomes part of a storage key, which
 * is built from {@link #key} out of server-generated UUIDs — so a traversal name could not have
 * reached the filesystem. It is reduced anyway, for two reasons: a client-supplied string that
 * <em>looks</em> like a path invites the next reader to use it as one, and
 * {@code "../../../etc/passwd"} rendered in the UI as an attachment name is alarming in a way the
 * bytes never were.
 */
public record BlobUpload(String filename, String contentType, long sizeBytes, byte[] bytes) {

    /**
     * SHA-256 of the bytes, stored in {@code attachment.checksum}.
     *
     * <p>Nothing verifies it yet. It is recorded now because it can only be computed while the bytes
     * are in hand: without it, silent corruption in the blob store is undetectable forever after,
     * and a checksum added later can only ever attest to the file as it already is.
     */
    public String checksum() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to provide SHA-256, so this arm cannot be reached; it is not
            // swallowed, because a checksum that silently became null would be worse than a crash.
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /**
     * Where one file's bytes live. <strong>Every segment is a UUID this server produced</strong> —
     * the company, the thing the file is attached to, and a fresh random id per file.
     *
     * <p>Nothing the client sent appears here, which is the whole traversal defence: the uploaded
     * filename is stored in a column and rendered from there. The company leads so an operator
     * looking at the directory can see whose bytes are whose, and so a per-company sweep is a
     * subtree rather than a scan.
     *
     * <p>The middle segment is the <strong>owner</strong>, not the owner <em>type</em>, and the two
     * differ in a way worth stating: an execution id and a case id cannot collide, so the key needs
     * no discriminator, and adding one would make the path claim something the key does not have to
     * be right about. This method is the only place a key is built — {@link AttachmentStore} treats
     * one as opaque and has no notion of what it means.
     */
    public static String key(UUID companyId, UUID ownerId) {
        return companyId + "/" + ownerId + "/" + UUID.randomUUID();
    }

    /**
     * The last segment of whatever the client called the file, defaulting when there is nothing
     * usable left. Handles both separators: a Windows client sends backslashes, and splitting on
     * {@code '/'} alone would keep {@code ..\..\passwd} intact.
     *
     * <p>The fallback is generic rather than {@code "evidence"}, which is what it said while this
     * lived in that module — a case's unnamed attachment called "evidence" would be describing the
     * wrong thing to the one person who has no other clue what the file is.
     */
    public static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "attachment";
        }
        String tail = raw.replace('\\', '/');
        tail = tail.substring(tail.lastIndexOf('/') + 1).trim();
        return tail.isBlank() ? "attachment" : tail;
    }

    /**
     * The filename as it can safely appear in a {@code Content-Disposition} header.
     *
     * <p>This is the one client-supplied string that reaches a response <em>header</em>, where a
     * carriage return is header injection rather than an odd filename. Quotes are stripped for the
     * same reason one level down: they would end the quoted string early.
     */
    public static String headerSafeFilename(String filename) {
        return filename.replaceAll("[\"\\\\\\r\\n]", "_");
    }
}
