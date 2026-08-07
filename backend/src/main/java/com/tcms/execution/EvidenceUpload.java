package com.tcms.execution;

import com.tcms.storage.BlobUpload;

/**
 * A file on its way onto a result — {@link BlobUpload} plus the one field that is only ever
 * evidence's.
 *
 * <p>Everything about <em>the file</em> — the sanitized filename, the content type, the size, the
 * checksum and the storage key — moved to {@code storage.BlobUpload} in §126, where #49's three new
 * owners can reach it. What is left here is {@link #caption}, which belongs to the {@code evidence}
 * row and not to the blob: it is a person saying what the screenshot shows, and no other owner has
 * one. A case attachment is described by its filename; a caption on it would be a second name for
 * the same thing.
 */
record EvidenceUpload(BlobUpload file, String caption) {
}
