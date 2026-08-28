package com.tcms.integration.tracker;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Picks the adapter for a provider — the whole of the strategy selection, in one place.
 *
 * <h2>Injected as a list, not switched on</h2>
 *
 * <p>A {@code switch} over {@link TrackerProvider} here would be a second declaration of which
 * adapters exist, and a fourth tracker would then be two edits with a compiler that cannot see the
 * second one is missing. Spring collects every {@link TrackerPort} bean and this asks each whether
 * it speaks for the provider — so adding GitHub is one class and one enum member, which is what
 * 004 D20 claims the design paid for.
 *
 * <p>Under the {@code tracker-fake} profile the list is exactly one element:
 * {@link FakeTrackerAdapter} answers for every provider and the real adapters are absent, so which
 * one serves a request is never a property of bean ordering.
 */
@Component
class TrackerAdapters {

    private final List<TrackerPort> adapters;

    TrackerAdapters(List<TrackerPort> adapters) {
        this.adapters = adapters;
    }

    /**
     * @throws TrackerUnavailableException when nothing claims the provider — which can only happen
     *         for a row whose {@code provider} was written by something other than this application,
     *         since the column is CHECKed to the three members. Rendered as the one unavailable
     *         state rather than as a 500, because from the viewer's side it is the same fact: the
     *         preview cannot be shown.
     */
    TrackerPort forProvider(TrackerProvider provider) {
        return adapters.stream()
                .filter(adapter -> adapter.supports(provider))
                .findFirst()
                .orElseThrow(() -> new TrackerUnavailableException(
                        "This instance has no adapter for " + provider + "."));
    }
}
