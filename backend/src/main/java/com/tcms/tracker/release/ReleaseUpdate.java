package com.tcms.tracker.release;

import java.time.LocalDate;

/**
 * {@code PATCH /releases/{releaseId}} — every field optional, an absent key meaning "leave it".
 *
 * <p>Bound beside a {@link com.tcms.api.PatchBody} so absent and explicitly-null are told apart, for
 * B1's reason: Jackson gives them the same Java value, and a service that could not ask what it was
 * sent read both as <em>clear it</em> — so {@code {"version": "2.5"}} would have cleared the
 * description and the target date and answered 200.
 *
 * <p><strong>{@code status} is not here.</strong> Publishing is
 * {@code POST /releases/{releaseId}/publish} and costs {@code release:publish}, which
 * {@code release:manage} does not imply — a status property here would be that state moved behind
 * the other key, which is exactly what the roles spec separates the two for.
 */
public record ReleaseUpdate(String version, String description, LocalDate targetDate) {
}
