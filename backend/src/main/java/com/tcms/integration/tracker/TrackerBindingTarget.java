package com.tcms.integration.tracker;

import java.util.Map;
import java.util.UUID;

/**
 * The binding, as an adapter sees it — id, host, tracker project, and the config it reads.
 *
 * <h2>Why the adapters do not take the entity</h2>
 *
 * <p>{@link TrackerBinding} is a JPA entity with a lifecycle, a version and a company. An adapter
 * has no business holding one: it makes one HTTP call, and passing it a managed entity means an
 * adapter <em>could</em> read a lazily-loaded association mid-request, or hold it past the
 * transaction that loaded it. This record is the whole of what a call needs.
 *
 * @param config the binding's JSONB, parsed. Free-form because the keys are the adapter's — the
 *               Jira adapters read {@code issueType} and {@code jql}, the ADO one reads
 *               {@code workItemType}, {@code areaPath} and {@code wiql} — and each validates its
 *               own on the write, so a key nobody reads is refused where it is typed rather than
 *               discovered on the first failed search.
 */
public record TrackerBindingTarget(
        UUID bindingId,
        TrackerProvider provider,
        String baseUrl,
        String externalProject,
        Map<String, Object> config) {

    /** The config value, or null. Named rather than inlined so an adapter cannot forget the cast. */
    public String setting(String key) {
        Object value = config.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
