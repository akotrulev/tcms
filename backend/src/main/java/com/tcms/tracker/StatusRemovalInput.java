package com.tcms.tracker;

import java.util.UUID;

/**
 * {@code DELETE /projects/{projectId}/workflow/statuses/{statusId}}.
 *
 * <p>Story B1: removing a status that issues hold cannot silently orphan them, so the caller names
 * where they go. Sent as a body on the DELETE — the alternative, a query parameter, makes a
 * destructive decision look like a filter.
 */
public record StatusRemovalInput(UUID reassignToStatusId) {
}
