package com.tcms.staffing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The three reads {@link Capacity} needs, done once for a whole page rather than once per row.
 *
 * <p>{@link Capacity} is a pure function and must stay one — it is the only thing in this module a
 * test can drive without a container. This is the part that fetches: profiles, allocations and
 * absences for a set of people over a window, in three queries whatever the size of the set, and
 * then one walk per person.
 *
 * <p>It exists because <strong>three surfaces need the same assembly</strong> — the company capacity
 * view, the skills directory and the project's staffing table — and a second copy of "load the rows
 * and call the function" is how one screen comes to subtract absence twice while another does not.
 * The fourth surface, {@code GET /staffing/capacity/me}, reads it for one person.
 */
@Component
class CapacityReader {

    /**
     * A nominal week for somebody with <strong>no profile</strong>, and nothing derived from it
     * reaches the wire.
     *
     * <p>The walk answers two different kinds of question — hours, which need contracted hours, and
     * percentages, which do not — and a person nobody has profiled still has real allocations. A
     * zero here would make every day's capacity zero, and {@link Capacity} excludes a day with no
     * capacity from the peak (correctly: being booked on a day you are away is not
     * over-allocation), so an unprofiled person allocated at 150% would read as not over-allocated.
     * That is a wrong answer, where a null hours figure is the right one.
     *
     * <p>So the percentages are computed against this and <strong>every hours figure for such a
     * person is served as null</strong> — {@code capacityHours}, {@code absenceHours},
     * {@code allocatedCapacityHours}, {@code approvedHours} and {@code utilisationPercent} — which
     * is 016 D-j's refusal to invent 40 hours, kept exactly where it matters: on the wire.
     */
    private static final BigDecimal NOMINAL_WEEK = new BigDecimal("40");

    private final PersonProfileRepository profiles;
    private final AllocationRepository allocations;
    private final AbsenceRepository absences;

    CapacityReader(
            PersonProfileRepository profiles,
            AllocationRepository allocations,
            AbsenceRepository absences) {

        this.profiles = profiles;
        this.allocations = allocations;
        this.absences = absences;
    }

    /**
     * One person's window and the rows behind it.
     *
     * @param profile null when nobody has recorded their contracted hours — see {@link #NOMINAL_WEEK}
     */
    record PersonWindow(
            PersonProfile profile,
            Capacity.Window window,
            List<Allocation> allocations,
            List<Absence> absences) {
    }

    /**
     * The mean daily percentage a <em>subset</em> of somebody's allocations claims.
     *
     * <p>The project screen's {@code percentOnThisProject}, and it goes through the same walk as
     * every other percentage rather than being a sum divided by a day count written out again.
     * Absences are not passed: a mean of daily allocated percentages does not depend on them, and
     * feeding them in would invite a reader to think it did.
     */
    static int meanPercentOf(List<Allocation> rows, LocalDate from, LocalDate to) {
        return Capacity.of(
                        NOMINAL_WEEK,
                        from,
                        to,
                        rows.stream()
                                .map(row -> new Capacity.AllocatedSpan(
                                        row.getStartsOn(), row.getEndsOn(), row.getPercent()))
                                .toList(),
                        List.of())
                .allocatedPercent();
    }

    Map<UUID, PersonWindow> windowsFor(
            UUID companyId, Collection<UUID> personIds, LocalDate from, LocalDate to) {

        if (personIds.isEmpty()) {
            // Not merely an optimization: an empty collection bound into SQL `IN ()` is a syntax
            // error, and a company whose every member is filtered out by a skill is the ordinary
            // outcome of the directory's own filter.
            return Map.of();
        }

        Map<UUID, PersonProfile> profileByPerson = new HashMap<>();
        profiles.findByCompanyIdAndUserIdIn(companyId, personIds)
                .forEach(profile -> profileByPerson.put(profile.getUserId(), profile));

        Map<UUID, List<Allocation>> allocationsByPerson = new HashMap<>();
        allocations
                .findByCompanyIdAndPersonIdInAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(
                        companyId, personIds, to, from)
                .forEach(row -> allocationsByPerson
                        .computeIfAbsent(row.getPersonId(), ignored -> new ArrayList<>())
                        .add(row));

        Map<UUID, List<Absence>> absencesByPerson = new HashMap<>();
        absences
                .findByCompanyIdAndPersonIdInAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(
                        companyId, personIds, to, from)
                .forEach(row -> absencesByPerson
                        .computeIfAbsent(row.getPersonId(), ignored -> new ArrayList<>())
                        .add(row));

        Map<UUID, PersonWindow> windows = new LinkedHashMap<>();
        for (UUID personId : personIds) {
            PersonProfile profile = profileByPerson.get(personId);
            List<Allocation> theirs = allocationsByPerson.getOrDefault(personId, List.of());
            List<Absence> away = absencesByPerson.getOrDefault(personId, List.of());

            Capacity.Window window = Capacity.of(
                    profile == null ? NOMINAL_WEEK : profile.getWeeklyHours(),
                    from,
                    to,
                    theirs.stream()
                            .map(row -> new Capacity.AllocatedSpan(
                                    row.getStartsOn(), row.getEndsOn(), row.getPercent()))
                            .toList(),
                    away.stream()
                            .map(row -> new Capacity.AbsentSpan(
                                    row.getStartsOn(), row.getEndsOn(), row.getDayFraction()))
                            .toList());

            windows.put(personId, new PersonWindow(profile, window, theirs, away));
        }
        return windows;
    }
}
