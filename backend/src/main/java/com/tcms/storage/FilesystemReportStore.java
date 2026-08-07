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
 * {@link ReportStore} over a directory on the box — {@link FilesystemEvidenceStore}'s
 * twin, under a root of its own.
 *
 * <h2>A separate root, not a subdirectory of the evidence one</h2>
 *
 * <p>{@code tcms.storage.reports.root} rather than {@code …/evidence/reports}: the two have
 * different retention (evidence is frozen into an evidence bundle at plan close, §6n; reports are
 * regenerable from the fact log and nothing deletes either in v1) and different loss consequences —
 * losing evidence loses the proof behind a verdict, losing a report costs a re-run of the job. A
 * deployment that wants to back up one and not the other can, and a deployment that wants one
 * volume can point both at it.
 *
 * <h2>Regenerable, and that is the only reason this is not backup-critical</h2>
 *
 * <p>Every report here can be produced again from {@code execution_fact} — which is itself an
 * idempotent projection of the executions. What is <em>not</em> recoverable is a report's meaning at
 * the time it was run: §6e's determinism requirement is served by
 * {@code report_artifact.filter_snapshot}, which is in Postgres. So losing this directory costs
 * re-running jobs, not history, and the runbook can treat it accordingly — unlike the evidence root,
 * whose own header says the opposite in as many words.
 *
 * <h2>The traversal check is here as well as in the key</h2>
 *
 * <p>Keys are server-generated (see the runner), so nothing a client sends reaches a path today. The
 * resolved path is re-checked against the root anyway: a guarantee that lives only in how the caller
 * builds its argument is one refactor from being gone, and the failure it prevents — writing outside
 * the root — is not one any test of the endpoint would ever show.
 */
@Component
class FilesystemReportStore implements ReportStore {

    private final Path root;

    FilesystemReportStore(@Value("${tcms.storage.reports.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not store report bytes under " + key, failure);
        }
    }

    @Override
    public Optional<ReportBlob> open(String key) {
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
            return Optional.of(new ReportBlob(stream, size));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read report bytes under " + key, failure);
        }
    }

    @Override
    public void delete(String key) {
        try {
            // deleteIfExists: delete is idempotent per the port's contract, and nothing in v1 calls
            // this at all — there is no retention sweep and no way to delete a report. It exists so
            // the port is the whole port rather than the three methods today's callers happen to
            // need, which is the shape that makes the S3 adapter a drop-in.
            Files.deleteIfExists(resolve(key));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not delete report bytes under " + key, failure);
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
            throw new IllegalArgumentException("Report key resolves outside the storage root");
        }
        return resolved;
    }
}
