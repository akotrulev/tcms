package com.tcms.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <h2>The traversal check is here as well as in the key</h2>
 *
 * <p>Keys are server-generated UUID triples ({@code execution.EvidenceService#storageKey}), so
 * nothing a client sends reaches a path today — the filename is stored in a <em>column</em> and never
 * becomes a path segment. The resolved path is re-checked against the root anyway: a guarantee that
 * lives only in how the caller builds its argument is one refactor from being gone, and the failure
 * it prevents — writing outside the root — is not one any test of the endpoint would ever show.
 */
@Component
class FilesystemEvidenceStore implements EvidenceStore {

    private final Path root;

    FilesystemEvidenceStore(@Value("${tcms.storage.evidence.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not store evidence bytes under " + key, failure);
        }
    }

    @Override
    public Optional<StoredBlob> open(String key) {
        Path source = resolve(key);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            // Size before the stream: if the file vanishes between the two, newInputStream throws
            // and the request fails loudly, which is better than a Content-Length that disagrees
            // with the body — a mismatch a browser reports as a corrupt download.
            long size = Files.size(source);
            InputStream stream = Files.newInputStream(source);
            return Optional.of(new StoredBlob(stream, size));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read evidence bytes under " + key, failure);
        }
    }

    @Override
    public void delete(String key) {
        try {
            // deleteIfExists, not delete: this runs after the rows are already gone, so a retry of
            // a half-completed delete must not answer 500 for bytes that are absent precisely
            // because the first attempt succeeded at removing them.
            Files.deleteIfExists(resolve(key));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not delete evidence bytes under " + key, failure);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    /**
     * The absolute path for a key, refusing any that escapes the root.
     *
     * <p>{@code normalize()} collapses {@code ..} <em>before</em> the check, so the comparison is
     * against the path that would actually be opened rather than against the string that was asked
     * for. Checking the raw key for {@code ".."} instead is the version of this that looks right and
     * misses URL-encoded and platform-specific spellings.
     */
    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Evidence key resolves outside the storage root");
        }
        return resolved;
    }
}
