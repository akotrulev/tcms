package com.tcms.staffing;

import com.tcms.api.PatchBody;
import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The project's own staffing — who is on it, and the three writes that change that.
 *
 * <h2>PROJECT scope, and the breakdown stops at this project's edge (017 D-f)</h2>
 *
 * <p>{@code staffing:read} and {@code staffing:allocate} are granted per project, and a Project
 * Manager holds no company-wide key. So {@link ProjectStaffingPersonView} carries a person's
 * company-wide <em>total</em> and the over-allocation flag and <strong>names no other
 * project</strong>: listing "Acme Loyalty 50% + Bergstrom Portal 30%" here would disclose, through
 * a project-scope grant, the existence and staffing of projects the caller cannot see. The number is
 * what makes the flag actionable; the breakdown is {@link StaffingService}'s, under
 * {@code staffing:directory}.
 *
 * <h2>An allocation may not overlap itself (017 D-n)</h2>
 *
 * <p>Two allocations of the same person to the same project over overlapping days are refused with
 * a {@code 409} rather than merged: {@code staffing.html} draws one bar per person per project, so
 * an overlap is not a thing a screen can render, and merging them silently would change a number
 * somebody typed. The same person on two <em>different</em> projects is the ordinary case and is
 * exactly what over-allocation exists to report, which is why the check keys on
 * {@code (company, project, person)} and must not widen.
 *
 * <p>The check lives here rather than in an {@code EXCLUDE USING gist} constraint, and {@code V10}
 * §4 records why: the constraint would need {@code btree_gist} for the two UUID equality terms, and
 * the failure it produces is a Postgres violation string this service would then have to
 * pattern-match to name the conflicting range.
 */
@Service
public class AllocationService {

    private static final String READ = "staffing:read";
    private static final String ALLOCATE = "staffing:allocate";

    private static final int MIN_PERCENT = 1;
    private static final int MAX_PERCENT = 100;

    /**
     * The "exclude nothing" argument to the overlap query, for a create.
     *
     * <p>A zero UUID rather than {@code null}: a null bound into {@code a.id <> :excludeId} makes
     * the predicate unknown, the row is then dropped from the result, and the check silently passes
     * for every allocation — which would turn it off on exactly the path it exists for.
     */
    private static final UUID NOTHING_TO_EXCLUDE = new UUID(0L, 0L);

    private final ProjectService projects;
    private final PeopleDirectory people;
    private final AllocationRepository allocations;
    private final CapacityReader capacity;
    private final Clock clock;

    public AllocationService(
            ProjectService projects,
            PeopleDirectory people,
            AllocationRepository allocations,
            CapacityReader capacity,
            Clock clock) {

        this.projects = projects;
        this.people = people;
        this.allocations = allocations;
        this.capacity = capacity;
        this.clock = clock;
    }

    /** What a write did. Sealed, so the controller's mapping over it is exhaustive. */
    public sealed interface Outcome {

        /** A field the caller can fix. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** 017 D-n's 409, naming the range it collided with so the caller can see what to move. */
        record Overlaps(LocalDate startsOn, LocalDate endsOn) implements Outcome {
        }

        /**
         * The allocation id names nothing on this project — a 404 <em>past</em> the project gate.
         *
         * <p>Distinct from {@link GateResult.Unreachable}, which is the project itself being
         * invisible. Both answer 404 and they are different facts; keeping them apart is what stops
         * a later reader concluding the gate covers the row.
         */
        record NoSuchAllocation() implements Outcome {
        }

        record Written(AllocationView view) implements Outcome {
        }

        record Removed() implements Outcome {
        }
    }

    /**
     * {@code GET /projects/{projectId}/staffing} — story S1's "I open a project's staffing".
     *
     * <p>{@code totalPercent} is the person's mean daily allocated percentage <strong>across every
     * project in the company</strong>, and {@code overAllocated} is read off their busiest single
     * day (017 D-e). The two are deliberately different statistics: a mean is what a row label can
     * carry, and a peak is the only thing a flag can honestly be read from — a month's sum would
     * report two consecutive half-month allocations at 200%.
     */
    @Transactional(readOnly = true)
    public GateResult<ProjectStaffingView> staffingOf(
            UUID userId, UUID projectId, LocalDate from, LocalDate to) {

        return projects.readGranted(userId, projectId, READ, project -> {
            UUID companyId = CompanyContext.require();
            StaffingWindow window = StaffingWindow.of(from, to, clock);

            List<Allocation> mine = allocations
                    .findByCompanyIdAndProjectIdAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(
                            companyId, project.getId(), window.to(), window.from());

            Set<UUID> personIds = mine.stream().map(Allocation::getPersonId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<UUID, UserRef> named = people.resolve(companyId, personIds);
            Map<UUID, CapacityReader.PersonWindow> windows =
                    capacity.windowsFor(companyId, personIds, window.from(), window.to());

            List<ProjectStaffingPersonView> onIt = new ArrayList<>();
            for (UUID personId : personIds) {
                CapacityReader.PersonWindow held = windows.get(personId);
                List<Allocation> here = mine.stream()
                        .filter(row -> row.getPersonId().equals(personId))
                        .toList();
                onIt.add(new ProjectStaffingPersonView(
                        named.get(personId),
                        CapacityReader.meanPercentOf(here, window.from(), window.to()),
                        held.window().allocatedPercent(),
                        held.window().overAllocated(),
                        held.profile() == null
                                ? null
                                : held.window().capacityHours().doubleValue()));
            }

            return new ProjectStaffingView(
                    window.from(),
                    window.to(),
                    mine.stream().map(row -> viewOf(row, named)).toList(),
                    onIt);
        });
    }

    /**
     * {@code POST /projects/{projectId}/allocations}.
     *
     * <h2>{@code Idempotency-Key} is declared by the contract and not honoured here yet</h2>
     *
     * <p>Stated rather than left to be discovered, exactly as {@code time.TimeEntryController}
     * states it: the contract's {@code 200} arm is a <em>replay</em>, and answering one needs the key
     * stored beside the row it created — which is what {@code test_case.created_idempotency_key} does
     * one module over. {@code V10} gave {@code allocation} no such column, so a retry after a timeout
     * creates a second allocation. What limits the damage is D-n: a retry with the same range is
     * refused as an overlap, so the duplicate a lost response can produce is only one whose dates
     * differ from the first. The fix is a column and a lookup, not a change here.
     */
    @Transactional
    public GateResult<Outcome> create(UUID userId, UUID projectId, AllocationInput submitted) {
        return projects.writeGranted(userId, projectId, ALLOCATE, project -> {
            UUID companyId = CompanyContext.require();
            Map<String, List<String>> errors = problemsWith(companyId, submitted);
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }
            Optional<Allocation> clash = firstOverlap(
                    companyId, project.getId(), submitted.personId(),
                    submitted.startsOn(), submitted.endsOn(), NOTHING_TO_EXCLUDE);
            if (clash.isPresent()) {
                return new Outcome.Overlaps(
                        clash.get().getStartsOn(), clash.get().getEndsOn());
            }
            Allocation created = allocations.save(Allocation.of(
                    UUID.randomUUID(),
                    companyId,
                    project.getId(),
                    submitted.personId(),
                    submitted.percent(),
                    submitted.startsOn(),
                    submitted.endsOn(),
                    submitted.note(),
                    userId,
                    clock.instant()));
            return new Outcome.Written(
                    viewOf(created, people.resolve(companyId, List.of(created.getPersonId()))));
        });
    }

    /**
     * {@code PATCH …/allocations/{allocationId}} — a true delta.
     *
     * <p>An absent field is left alone, which is only answerable because the body arrives twice: once
     * bound to {@link AllocationPatch} and once as the {@link PatchBody} that can be asked what was
     * actually sent. Jackson gives an absent field and an explicit null the same Java value, and a
     * service that could not tell them apart read both as <em>clear it</em> — B1's defect.
     *
     * <p>The overlap rule applies to the <em>result</em>, so a range moved onto another allocation of
     * the same person on this project is a 409. The allocation being edited is excluded from the
     * check, or every edit would collide with itself.
     */
    @Transactional
    public GateResult<Outcome> update(
            UUID userId, UUID projectId, UUID allocationId,
            AllocationPatch submitted, PatchBody sent) {

        return projects.writeGranted(userId, projectId, ALLOCATE, project -> {
            UUID companyId = CompanyContext.require();
            Optional<Allocation> found =
                    allocations.findByCompanyIdAndProjectIdAndId(companyId, project.getId(), allocationId);
            if (found.isEmpty()) {
                return new Outcome.NoSuchAllocation();
            }
            Allocation existing = found.get();

            int percent = sent.has("percent") && submitted.percent() != null
                    ? submitted.percent() : existing.getPercent();
            LocalDate startsOn = sent.has("startsOn") && submitted.startsOn() != null
                    ? submitted.startsOn() : existing.getStartsOn();
            LocalDate endsOn = sent.has("endsOn") && submitted.endsOn() != null
                    ? submitted.endsOn() : existing.getEndsOn();
            String note = sent.has("note") ? submitted.note() : existing.getNote();

            Map<String, List<String>> errors = problemsWith(percent, startsOn, endsOn);
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }
            Optional<Allocation> clash = firstOverlap(
                    companyId, project.getId(), existing.getPersonId(),
                    startsOn, endsOn, existing.getId());
            if (clash.isPresent()) {
                return new Outcome.Overlaps(
                        clash.get().getStartsOn(), clash.get().getEndsOn());
            }

            existing.amend(percent, startsOn, endsOn, note, userId, clock.instant());
            Allocation saved = allocations.save(existing);
            return new Outcome.Written(
                    viewOf(saved, people.resolve(companyId, List.of(saved.getPersonId()))));
        });
    }

    /**
     * {@code DELETE …/allocations/{allocationId}}.
     *
     * <p>The person keeps their profile, their skills and every other project's claim on them; what
     * goes is this project's.
     */
    @Transactional
    public GateResult<Outcome> delete(UUID userId, UUID projectId, UUID allocationId) {
        return projects.writeGranted(userId, projectId, ALLOCATE, project -> {
            Optional<Allocation> found = allocations.findByCompanyIdAndProjectIdAndId(
                    CompanyContext.require(), project.getId(), allocationId);
            if (found.isEmpty()) {
                return new Outcome.NoSuchAllocation();
            }
            allocations.delete(found.get());
            return new Outcome.Removed();
        });
    }

    // =====================================================================

    private Optional<Allocation> firstOverlap(
            UUID companyId, UUID projectId, UUID personId,
            LocalDate startsOn, LocalDate endsOn, UUID excludeId) {

        return allocations
                .overlapping(companyId, projectId, personId, startsOn, endsOn, excludeId)
                .stream()
                .findFirst();
    }

    private Map<String, List<String>> problemsWith(UUID companyId, AllocationInput submitted) {
        if (submitted == null || submitted.personId() == null) {
            return Map.of("personId", List.of("Say who is being allocated."));
        }
        boolean member = people.membersOf(companyId).stream()
                .map(UserRef::id)
                .anyMatch(submitted.personId()::equals);
        if (!member) {
            // A field error rather than a 404, because the target of this operation is the PROJECT
            // and the project resolved. A 404 here would say the project had gone.
            return Map.of("personId", List.of(
                    "That account is not a present member of this company."));
        }
        return problemsWith(
                submitted.percent() == null ? 0 : submitted.percent(),
                submitted.startsOn(), submitted.endsOn());
    }

    private static Map<String, List<String>> problemsWith(
            int percent, LocalDate startsOn, LocalDate endsOn) {

        if (percent < MIN_PERCENT || percent > MAX_PERCENT) {
            return Map.of("percent", List.of(
                    "An allocation runs from " + MIN_PERCENT + " to " + MAX_PERCENT
                            + "% of one person's own capacity. Booking somebody across two "
                            + "projects is two allocations, and the total is what over-allocation "
                            + "reports."));
        }
        if (startsOn == null || endsOn == null) {
            return Map.of("startsOn", List.of("An allocation needs a first and a last day."));
        }
        if (endsOn.isBefore(startsOn)) {
            return Map.of("endsOn", List.of("The last day cannot be before the first."));
        }
        return Map.of();
    }

    private static AllocationView viewOf(Allocation row, Map<UUID, UserRef> named) {
        return new AllocationView(
                row.getId(),
                row.getProjectId(),
                row.getPersonId(),
                named.get(row.getPersonId()),
                row.getPercent(),
                row.getStartsOn(),
                row.getEndsOn(),
                row.getNote());
    }
}
