package com.tcms.tracker.imports;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Picks the {@link ImportSource} that answers for a provider.
 *
 * <p>The list is injected rather than switched over, so T5's {@code AzureDevOpsImportSource} is a
 * new file and no edit here — which is the property D-k asks the port for. A provider with no
 * implementation is an {@link IllegalStateException} and not a silent null: the enum member exists
 * because the contract declares it, and the honest failure is "this build has no reader for that"
 * rather than a {@code NullPointerException} three frames later.
 */
@Component
public class ImportSources {

    private final List<ImportSource> sources;

    ImportSources(List<ImportSource> sources) {
        this.sources = sources;
    }

    /**
     * @throws IllegalStateException when no implementation claims the provider — see the header for
     *         why that is louder than a null
     */
    public ImportSource forProvider(ImportProvider provider) {
        return sources.stream()
                .filter(source -> source.supports(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no ImportSource claims " + provider + "; the contract declares the "
                                + "provider and this build has no reader for it"));
    }
}
