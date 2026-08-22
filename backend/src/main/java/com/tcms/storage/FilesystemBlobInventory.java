package com.tcms.storage;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link BlobInventory} over the three directories on the box.
 *
 * <p>Holds its own {@link FilesystemBlobStore} per root rather than reaching into the three
 * adapters, for the reason their own headers give: each store is constructed from its own
 * {@code @Value} root and never injected, so that "which store am I holding" can never become a
 * qualifier string. Constructing them again here is cheap and idempotent — the constructor's
 * root probe is the same refusal-to-start check the adapters already make, so a misprovisioned
 * deployment fails at the same moment either way.
 */
@Component
class FilesystemBlobInventory implements BlobInventory {

    private final Map<BlobRoot, FilesystemBlobStore> stores = new EnumMap<>(BlobRoot.class);

    FilesystemBlobInventory(
            @Value("${tcms.storage.evidence.root}") String evidenceRoot,
            @Value("${tcms.storage.reports.root}") String reportsRoot,
            @Value("${tcms.storage.raw-reports.root}") String rawReportsRoot) {
        stores.put(BlobRoot.EVIDENCE, new FilesystemBlobStore(evidenceRoot, "evidence"));
        stores.put(BlobRoot.REPORTS, new FilesystemBlobStore(reportsRoot, "report"));
        stores.put(BlobRoot.RAW_REPORTS, new FilesystemBlobStore(rawReportsRoot, "raw report"));
    }

    @Override
    public List<String> keysUnder(BlobRoot root, String prefix) {
        return stores.get(root).list(prefix);
    }
}
