package com.tcms.storage;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link RawReportStore} over a directory on the box — {@link FilesystemAttachmentStore}'s and
 * {@link FilesystemReportStore}'s third sibling, under a root of its own and sharing their body
 * ({@link FilesystemBlobStore}).
 *
 * <p>The label passed into that body is {@code "raw report"} rather than {@code "report"}, and it is
 * the one thing that does not collapse into the shared implementation: an operator reading
 * {@code could not store report bytes} in a log needs to know which of two roots is full or gone,
 * and this is the root whose failure means a CI upload was not kept while the results it carried
 * were recorded anyway.
 *
 * <p>The port's header carries why this is a third root at all.
 */
@Component
class FilesystemRawReportStore implements RawReportStore {

    private final FilesystemBlobStore blobs;

    FilesystemRawReportStore(@Value("${tcms.storage.raw-reports.root}") String root) {
        this.blobs = new FilesystemBlobStore(root, "raw report");
    }

    @Override
    public void put(String key, byte[] bytes) {
        blobs.put(key, bytes);
    }

    @Override
    public Optional<StoredBlob> open(String key) {
        return blobs.open(key);
    }

    @Override
    public void delete(String key) {
        blobs.delete(key);
    }

    @Override
    public boolean exists(String key) {
        return blobs.exists(key);
    }
}
