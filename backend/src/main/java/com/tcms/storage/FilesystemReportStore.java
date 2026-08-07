package com.tcms.storage;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link ReportStore} over a directory on the box — {@link FilesystemAttachmentStore}'s twin, under a
 * root of its own and sharing its body ({@link FilesystemBlobStore}) since §125.
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
 * <p><strong>That difference is the whole reason these are two beans and two types</strong>, and it
 * survives the shared body: the root and the port are this class's, only the four filesystem
 * operations are held in common.
 *
 * <h2>Regenerable, and that is the only reason this is not backup-critical</h2>
 *
 * <p>Every report here can be produced again from {@code execution_fact} — which is itself an
 * idempotent projection of the executions. What is <em>not</em> recoverable is a report's meaning at
 * the time it was run: §6e's determinism requirement is served by
 * {@code report_artifact.filter_snapshot}, which is in Postgres. So losing this directory costs
 * re-running jobs, not history, and the runbook can treat it accordingly — unlike the evidence root,
 * whose own header says the opposite in as many words.
 */
@Component
class FilesystemReportStore implements ReportStore {

    private final FilesystemBlobStore blobs;

    FilesystemReportStore(@Value("${tcms.storage.reports.root}") String root) {
        this.blobs = new FilesystemBlobStore(root, "report");
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
