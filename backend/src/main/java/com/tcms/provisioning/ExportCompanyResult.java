package com.tcms.provisioning;

import com.tcms.provisioning.ExportManifest.MissingBlob;
import com.tcms.provisioning.ExportManifest.OrphanBlob;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one {@code export-company} run produced.
 *
 * <p>Reported by {@link ExportCompanyCommand}, which turns it into the exit code. The outcome is
 * three-valued and not two: {@link #wasComplete()} false means <em>an archive exists at
 * {@code archive()} and something it references does not</em>, which is neither the success an
 * offboarding script may act on nor the failure that leaves nothing behind.
 *
 * @param bytes the size of the finished archive on disk
 * @param tableRows row count per exported table, in the archive's own order
 * @param blobCount files actually written into the archive's blobs/ prefix
 */
public record ExportCompanyResult(
        UUID companyId,
        String slug,
        Path archive,
        long bytes,
        Map<String, Long> tableRows,
        int blobCount,
        List<MissingBlob> missing,
        List<OrphanBlob> orphans) {

    public ExportCompanyResult {
        tableRows = Map.copyOf(tableRows);
        missing = List.copyOf(missing);
        orphans = List.copyOf(orphans);
    }

    /** Whether every row's bytes were found. False is exit code 3, not exit code 1. */
    public boolean wasComplete() {
        return missing.isEmpty();
    }

    /** Every exported row, across every table. */
    public long totalRows() {
        return tableRows.values().stream().mapToLong(Long::longValue).sum();
    }
}
