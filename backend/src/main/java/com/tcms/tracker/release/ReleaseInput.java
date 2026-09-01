package com.tcms.tracker.release;

import java.time.LocalDate;

/**
 * The contract's {@code ReleaseInput} — a version, and optionally a date and a description.
 *
 * <p>Nothing else is required, and there is no field for a sprint, a velocity or a second project:
 * planning has a cadence because releases have dates, not because anybody invented a two-week rhythm
 * they do not work in.
 */
public record ReleaseInput(String version, String description, LocalDate targetDate) {
}
