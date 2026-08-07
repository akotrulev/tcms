package com.tcms.storage;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link EvidenceStore} over a directory on the box — the adapter open question #28 chose, and the
 * reason evidence uploads proxy through the API instead of going browser-direct to a signed URL
 * (§51 stated that consequence when the option was picked: a filesystem cannot issue one).
 *
 * <h2>The root directory is stateful and backup-critical</h2>
 *
 * <p>Losing it loses every screenshot behind every failed result, and no migration brings them back
 * — the rows survive and their bytes do not, so the product renders a list of attachments that all
 * 404. That is the price of the single-deployable story, and the Hetzner runbook has to cover this
 * path the way it covers the database.
 *
 * <h2>The body is {@link FilesystemBlobStore}, and this class is the type</h2>
 *
 * <p>Four delegating methods, since §125. What is here rather than there is the <em>identity</em> of
 * the store — its property key, its root, and the fact that it is an {@code EvidenceStore} and can
 * therefore never be handed to a caller that wanted reports. The traversal check the header used to
 * argue for lives in the shared body and is argued there; it is no less present for being written
 * once, and it is tested for the first time on both sides because of it.
 */
@Component
class FilesystemEvidenceStore implements EvidenceStore {

    private final FilesystemBlobStore blobs;

    FilesystemEvidenceStore(@Value("${tcms.storage.evidence.root}") String root) {
        this.blobs = new FilesystemBlobStore(root, "evidence");
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
