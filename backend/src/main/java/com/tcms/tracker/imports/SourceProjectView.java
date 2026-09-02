package com.tcms.tracker.imports;

/**
 * One row of the connect step's project dropdown — the contract's {@code SourceProject}.
 *
 * <p>A view distinct from {@link SourceProject} even though the three components are the same
 * today, because the two answer to different authorities: that one is what a provider's API
 * happened to give us and this one is what {@code openapi.yaml} declares. Collapsing them would
 * make an adapter's convenience field a wire change.
 */
public record SourceProjectView(String key, String name, int issueCount) {

    static SourceProjectView of(SourceProject project) {
        return new SourceProjectView(project.key(), project.name(), project.issueCount());
    }
}
