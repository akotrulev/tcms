package com.tcms.staffing;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is booked on what, and when.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface — see
 * {@link PersonProfileRepository} for the failure that prevents.
 *
 * <p><strong>Every date predicate here reads backwards and that is the standard form.</strong> Two
 * ranges overlap when each starts on or before the other ends, so a query for "the allocations
 * covering {@code from}..{@code to}" compares {@code startsOn <= to AND endsOn >= from}. Reading it
 * as a typo and "fixing" it produces a window that silently drops every allocation straddling an
 * edge — which is most of them, since the ranges this product asks about are a fortnight and three
 * months.
 */
@Transactional(readOnly = true)
interface AllocationRepository extends JpaRepository<Allocation, UUID> {

    /** Every claim on these people inside the window — the company view and the directory, in one query. */
    List<Allocation> findByCompanyIdAndPersonIdInAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(
            UUID companyId, Collection<UUID> personIds, LocalDate to, LocalDate from);

    /** This project's claims inside the window. */
    List<Allocation> findByCompanyIdAndProjectIdAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(
            UUID companyId, UUID projectId, LocalDate to, LocalDate from);

    Optional<Allocation> findByCompanyIdAndProjectIdAndId(
            UUID companyId, UUID projectId, UUID id);

    /**
     * The overlap check behind 017 D-n's 409.
     *
     * <p>Keyed on {@code (company, project, person)}, which is the pair the decision is about: two
     * allocations of the same person to the same project over overlapping days are not a thing a
     * screen can render — {@code staffing.html} draws one bar per person per project — while the
     * same person on two <em>different</em> projects is the ordinary case and is exactly what
     * over-allocation exists to report. So this may not widen to {@code (company, person)}.
     *
     * <p>{@code excludeId} is the allocation being {@code PATCH}ed, so an edit does not conflict
     * with itself. A create passes a zero UUID rather than null — a null bound into {@code <>}
     * makes the predicate unknown and the query then matches nothing, which would turn the check
     * off on exactly the path it exists for.
     */
    @Query("""
            select a from Allocation a
             where a.companyId = :companyId
               and a.projectId = :projectId
               and a.personId  = :personId
               and a.id       <> :excludeId
               and a.startsOn <= :endsOn
               and a.endsOn   >= :startsOn
             order by a.startsOn
            """)
    List<Allocation> overlapping(
            @Param("companyId") UUID companyId,
            @Param("projectId") UUID projectId,
            @Param("personId") UUID personId,
            @Param("startsOn") LocalDate startsOn,
            @Param("endsOn") LocalDate endsOn,
            @Param("excludeId") UUID excludeId);
}
