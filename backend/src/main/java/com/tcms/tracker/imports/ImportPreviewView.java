package com.tcms.tracker.imports;

import java.util.List;
import java.util.UUID;

/**
 * What an import would and would not bring across — the contract's {@code ImportPreview}, and
 * D32's dry run.
 *
 * <p><strong>Producing this writes nothing</strong>, which is not a convention but a test:
 * {@code ImportPreviewWritesNothingTest} snapshots every company-owned table before and after and
 * fails on any difference. {@code import-preview.html} promises it in its own banner, and a promise
 * on a screen with no observer behind it is the one that quietly stops being true.
 *
 * @param sourceKeyPrefix  {@code PROJ} — imported issues keep it (D5)
 * @param projectKeyPrefix {@code ACME} — unchanged, and both resolve afterwards, which is the fact
 *                         the preview exists to show somebody before they agree to it
 * @param types            one row per source type, mapped by name and then by level. Not confirmed
 *                         by anybody, unlike the statuses — see {@link PreviewTypeRow}.
 * @param statuses         one row per source status, each carrying a <em>suggestion</em>. The run
 *                         refuses a status the confirmed request left unmapped.
 * @param blockers         empty means the mapping step may be reached
 */
public record ImportPreviewView(
        UUID projectId,
        String sourceProjectKey,
        String sourceKeyPrefix,
        String projectKeyPrefix,
        ImportPreviewTotalsView totals,
        List<PreviewTypeRow> types,
        List<PreviewStatusRow> statuses,
        List<DroppedFieldView> droppedFields,
        List<ImportBlockerView> blockers) {
}
