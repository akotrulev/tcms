package com.tcms.integration.tracker;

import java.util.Map;

/**
 * {@code TrackerBindingPatch} — every field optional, none clearable, an absent field unchanged.
 *
 * <h2>{@code provider} and {@code baseUrl} are not here, and their absence is the decision</h2>
 *
 * <p>Identities are keyed to {@code (provider, baseUrl)}. A binding that could change host would
 * start using a different set of people's credentials against a server they never consented to —
 * and the {@code OutboundUrlGuard} that vetted the original host would not have seen the new one.
 * So changing a host is a delete and a create, which invalidates nothing quietly: the identities
 * stop matching, the connections page says so, and somebody decides.
 *
 * <p>A {@code PATCH} rather than a {@code PUT} because the four fields here are independently
 * editable and none of them is clearable — the shape {@code NotificationChannelInput} could not use,
 * because a channel's credential genuinely needs "unchanged" to be expressible.
 */
public record TrackerBindingPatch(
        String externalProject,
        Map<String, Object> config,
        Boolean isDefault,
        Boolean enabled) {
}
