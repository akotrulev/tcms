package com.tcms.integration.tracker;

import java.util.Map;

/**
 * {@code TrackerBindingInput} — the body of a binding create.
 *
 * <p>{@code provider} is a {@code String} and not the enum, for {@code ExternalLinkInput}'s reason:
 * an unbound enum produces Jackson's own 400 describing a Java type, where this endpoint's job is to
 * answer a form with the field that is wrong. {@link TrackerBindingService} parses it and reports
 * {@code errors.provider}.
 *
 * @param isDefault required rather than defaulted, and {@code enabled} likewise. The first binding
 *                  becomes the default regardless and a later {@code true} moves the flag, so the
 *                  field is not load-bearing on a create — but a dropped boolean silently disabling
 *                  a binding is the failure every writer in this API declines to allow.
 */
public record TrackerBindingInput(
        String provider,
        String baseUrl,
        String externalProject,
        Map<String, Object> config,
        Boolean isDefault,
        Boolean enabled) {
}
