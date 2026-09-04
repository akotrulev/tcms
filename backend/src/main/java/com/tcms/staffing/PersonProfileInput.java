package com.tcms.staffing;

import java.time.LocalDate;

/**
 * {@code PUT /staffing/people/{userId}/profile} — create or replace.
 *
 * <p>A {@code PUT} and not a {@code POST} plus a {@code PATCH} because a profile is one row per
 * person and the caller has no id to address it by. It follows that an absent optional field here
 * means <em>there is no job title</em> rather than <em>leave the one that is there</em>, which is
 * the reading a {@code PUT} carries and a {@code PATCH} must not.
 */
public record PersonProfileInput(
        Double weeklyHours,
        String jobTitle,
        String location,
        LocalDate experienceSince) {
}
