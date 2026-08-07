package com.tcms.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Bytes under a directory on the box — the body both filesystem adapters are, held once.
 *
 * <h2>Why this is a class the adapters hold and not the interface they implement</h2>
 *
 * <p>{@link AttachmentStore} and {@link ReportStore} are the same four methods, and until §125 they
 * were the same four methods <em>written twice</em>, along with this body and its traversal check —
 * §58's E1, accepted while a module boundary made the type unreachable. Co-located in this package
 * the copy has no argument left, and this class is what the move actually removed: two adapters,
 * one implementation, one place a future S3 form is written rather than two (decision #55 defers
 * that adapter, and this is what makes it one piece of work).
 *
 * <p>The two <em>ports</em> deliberately survive as distinct types. The alternative — one
 * {@code BlobStore} bean type with a qualifier per root — makes "which store am I holding" a string,
 * and the failure of getting it wrong is evidence written under the reports root: no exception, no
 * failing test, and discovered when somebody goes looking for the screenshot behind a verdict. Two
 * interfaces make that a compile error. So the duplication that was real (this body) is gone and the
 * distinction that is load-bearing (the type) is not.
 *
 * <h2>Not a bean</h2>
 *
 * <p>Constructed by each adapter from its own {@code @Value} root, never injected: two beans of one
 * type is precisely the ambiguity above, arriving through the back door.
 */
final class FilesystemBlobStore {

    private final Path root;

    /**
     * What these bytes are, for the exception messages — {@code "evidence"} or {@code "report"}.
     *
     * <p>The one thing that must not collapse when the two bodies do. An operator reading
     * {@code Could not store bytes under …} in a log has to know which root ran out of disk, and
     * the two roots are configured separately for exactly that reason
     * ({@link FilesystemReportStore}'s header on retention and loss).
     */
    private final String kind;

    FilesystemBlobStore(String root, String kind) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.kind = kind;
    }

    void put(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not store " + kind + " bytes under " + key, failure);
        }
    }

    Optional<StoredBlob> open(String key) {
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
            throw new UncheckedIOException("Could not read " + kind + " bytes under " + key, failure);
        }
    }

    void delete(String key) {
        try {
            // deleteIfExists, not delete: evidence deletion runs after the rows are already gone,
            // so a retry of a half-completed delete must not answer 500 for bytes that are absent
            // precisely because the first attempt succeeded at removing them. Nothing deletes a
            // report at all in v1 — the method exists so the port is the whole port rather than
            // the three methods today's callers happen to need, which is the shape that makes the
            // S3 adapter a drop-in.
            Files.deleteIfExists(resolve(key));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not delete " + kind + " bytes under " + key, failure);
        }
    }

    boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    /**
     * The absolute path for a key, refusing any that escapes the root.
     *
     * <p>{@code normalize()} collapses {@code ..} <em>before</em> the check, so the comparison is
     * against the path that would actually be opened rather than against the string that was asked
     * for. Checking the raw key for {@code ".."} instead is the version of this that looks right and
     * misses URL-encoded and platform-specific spellings.
     *
     * <p>Both adapters carried this method verbatim and <strong>only the evidence copy was ever
     * tested</strong> — a fact the move surfaced, because nothing had put the two files side by
     * side. One body is one test away from covering both, which is the second reason this class
     * exists.
     */
    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Storage key resolves outside the " + kind + " storage root");
        }
        return resolved;
    }
}
