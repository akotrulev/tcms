package com.tcms.provisioning;

import java.util.List;
import java.util.Map;

/**
 * manifest.json — what the archive says about itself.
 *
 * <p>Written <strong>last</strong>, so a killed process cannot leave a manifest-bearing archive.
 * Everything a reader needs in order to decide whether to believe the rest is here: the Flyway head
 * the rows were read at, the per-table row counts and digests, every redaction and every exclusion
 * with its reason, and the two reconciliations.
 *
 * <p>{@code complete} is the machine-readable form of exit code 3, so a script that unzips the
 * archive reaches the same verdict as one that read the exit status.
 *
 * @param formatVersion this document's shape. {@code 1}. A future importer refuses an unknown one
 * @param exportedAt when the walk ran, ISO-8601 UTC
 * @param schemaVersion the Flyway head — the one thing a future importer must check first
 * @param logoBlob what became of {@code company.logo_storage_key}. It has <strong>no writer
 *     anywhere in this build</strong> (branding is M6), so no root can be named for it without
 *     guessing, and a guess would write a company logo under the wrong root silently. The reader is
 *     told rather than left to wonder
 */
public record ExportManifest(
        int formatVersion,
        String exportedAt,
        String schemaVersion,
        CompanyRef company,
        List<TableEntry> tables,
        List<ExcludedTable> excludedTables,
        Map<String, BlobSummary> blobs,
        List<MissingBlob> missingBlobs,
        List<OrphanBlob> orphanBlobs,
        Map<String, Long> sentinels,
        String logoBlob,
        boolean complete) {

    /** Which company this is, in the four fields a reader identifies it by. */
    public record CompanyRef(String id, String slug, String displayName, String status) {
    }

    /**
     * One exported table.
     *
     * @param sha256 of the {@code tables/&lt;name&gt;.jsonl} entry's bytes, so a truncated or
     *     hand-edited archive is detectable months later by a reader who was not there
     */
    public record TableEntry(String name, long rows, String sha256, List<String> redactedColumns) {
    }

    /** A table left out, and why. The reason is {@link ExportTables.Exclusion}'s name. */
    public record ExcludedTable(String name, String reason) {
    }

    /** How much of one blob root travelled. */
    public record BlobSummary(int files, long bytes) {
    }

    /**
     * A walked row naming bytes that are not on disk.
     *
     * <p>Not an error and not a silence: the archive is still the best available copy of the
     * client's data, and exiting {@code 0} would tell an offboarding script that a complete one
     * exists.
     */
    public record MissingBlob(String table, String id, String storageKey) {
    }

    /**
     * A file under this company's prefix that no exported row names.
     *
     * <p>Counted and listed, <strong>not archived</strong>: unreferenced bytes whose provenance
     * nothing can state, and an archive that included them would be claiming they are the client's
     * data. {@code ReportJobRunner} writes bytes before the row on purpose, so a crashed job leaves
     * exactly this — the count is information, not a fault.
     */
    public record OrphanBlob(String root, String path) {
    }
}
