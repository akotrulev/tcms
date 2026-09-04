package com.tcms.staffing;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * When people are away.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface — see
 * {@link PersonProfileRepository} for the failure that prevents.
 *
 * <p>The reversed bounds are {@link AllocationRepository}'s, for the same reason: an absence
 * overlaps the window when it starts on or before the window ends and ends on or after it starts.
 */
@Transactional(readOnly = true)
interface AbsenceRepository extends JpaRepository<Absence, UUID> {

    /** Every absence touching the window for a page of people, in one query. */
    List<Absence> findByCompanyIdAndPersonIdInAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(
            UUID companyId, Collection<UUID> personIds, LocalDate to, LocalDate from);

    /**
     * One person's absences in the window, earliest first — the {@code GET …/absences} order.
     *
     * <p>Not expressible through the method above with a one-element collection, because that one
     * answers an unordered bulk question and this one is a list a screen renders in order. Two
     * questions, two indexes' worth of intent, one table.
     */
    List<Absence>
            findByCompanyIdAndPersonIdAndStartsOnLessThanEqualAndEndsOnGreaterThanEqualOrderByStartsOnAsc(
                    UUID companyId, UUID personId, LocalDate to, LocalDate from);

    Optional<Absence> findByCompanyIdAndPersonIdAndId(UUID companyId, UUID personId, UUID id);
}
