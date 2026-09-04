package com.tcms.staffing;

import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

/**
 * {@code PersonProfile} on the wire — contracted hours and the three facts {@code directory.html}
 * shows.
 *
 * @param experienceSince the stored value, a date (017 D-b)
 * @param experienceYears derived from it at read time, and null when that is null. Both are on the
 *                        wire because the screen renders the second and an editor edits the first;
 *                        storing the second is what makes a profile correct on the day it is typed
 *                        and wrong every day after
 */
public record PersonProfileView(
        UUID personId,
        Double weeklyHours,
        String jobTitle,
        String location,
        LocalDate experienceSince,
        Integer experienceYears) {

    /**
     * The stored row as the wire declares it — one mapping, so the profile a {@code PUT} answers
     * with cannot differ from the one the directory shows.
     *
     * @param today the instance's day, from the {@code Clock} bean. Passed rather than read here so
     *              this stays a pure conversion: nothing in this codebase reads the wall clock
     *              directly, and a derived "6 years" that did would be untestable
     */
    static PersonProfileView of(PersonProfile profile, LocalDate today) {
        return new PersonProfileView(
                profile.getUserId(),
                profile.getWeeklyHours().doubleValue(),
                profile.getJobTitle(),
                profile.getLocation(),
                profile.getExperienceSince(),
                profile.getExperienceSince() == null
                        ? null
                        // Derived at read time, which is the whole of 017 D-b: a stored integer is
                        // correct on the day it is typed and wrong every day after, silently.
                        : Period.between(profile.getExperienceSince(), today).getYears());
    }
}
