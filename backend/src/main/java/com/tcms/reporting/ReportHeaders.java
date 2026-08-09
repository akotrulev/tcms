package com.tcms.reporting;

import com.tcms.api.UserRef;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.CompanyRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the {@link ReportHeader} for a job — the one place the provenance block is assembled.
 *
 * <p>Called from inside {@link ReportJobRunner}'s computing transaction, because every field it
 * fills comes out of the database: the company's display name, the project's key, the requester's
 * name through {@code project}'s naming port. Outside a transaction each of those reads would run
 * with {@code app.current_company} unset, which {@code V4}'s {@code nullif} makes <em>zero rows</em>
 * rather than an error — a header of em dashes on a report whose body is correct.
 *
 * <h2>Nothing here can fail a report</h2>
 *
 * <p>Every lookup falls back to a printable value. A project deleted between the request and the
 * worker, a company row a suspended deployment cannot read, an account offboarded while its job sat
 * in the queue — none of those is a reason to refuse a report whose numbers are right, and a
 * {@code FAILED} job carrying "could not read the company" would be read as the report itself being
 * broken. The fallbacks say what is missing rather than inventing a value: {@code RunSummaryReport}
 * makes the same call for the tester column, for the same reason.
 */
@Component
class ReportHeaders {

    /**
     * {@code 2026-08-04 14:03:22 UTC}.
     *
     * <p>UTC and {@link Locale#ROOT}, both spelled out. A header rendered in the worker's default
     * zone would date a report to whichever machine happened to run it, and a locale-dependent month
     * name would make the same model produce two documents — §6e's determinism bullet, on the one
     * line of a report that is <em>about</em> when it was produced.
     */
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final CompanyRepository companies;
    private final ProjectRepository projects;
    private final PeopleDirectory people;
    private final ReportComputations computations;

    ReportHeaders(
            CompanyRepository companies,
            ProjectRepository projects,
            PeopleDirectory people,
            ReportComputations computations) {

        this.companies = companies;
        this.projects = projects;
        this.people = people;
        this.computations = computations;
    }

    @Transactional(readOnly = true)
    ReportHeader of(ReportJob job, ReportParameters parameters, Instant generatedAt) {
        UUID companyId = job.getCompanyId();
        String project = projectLabel(job.getProjectId());

        return new ReportHeader(
                job.getType(),
                computations.find(job.getType())
                        .map(ReportComputation::description)
                        .orElse("This report type is no longer installed on this server."),
                companies.findById(companyId).map(c -> c.getDisplayName()).orElse("—"),
                project,
                scopeNote(project),
                requesterName(companyId, job.getRequestedBy()),
                STAMP.format(generatedAt),
                selection(parameters));
    }

    /**
     * Plan §390's sentence, and it says what is true of <em>this</em> version rather than what will
     * be true of R6's.
     *
     * <p>A report is per project by its own path, and a project grant is all-or-nothing — there is
     * no partial visibility inside a project anywhere in the product — so this report covers the
     * project in full and two readers of the same report are reading the same numbers. That is
     * exactly what stops being true when cross-project reporting lands (open question 4, not in this
     * plan), and the sentence is worded so that the day it does, this line is visibly the one to
     * change rather than a vague claim that stays technically defensible.
     */
    private String scopeNote(String project) {
        return "This report covers " + project + " in full and no other project. A project grant is "
                + "all-or-nothing, so a reader with access to this project sees the same numbers as "
                + "any other; cross-project reports are not available in this version.";
    }

    private String projectLabel(UUID projectId) {
        Optional<Project> project = projects.findScopedById(projectId);
        return project.map(p -> p.getKey() + " — " + p.getName())
                .orElse("A project that can no longer be read");
    }

    private String requesterName(UUID companyId, UUID requestedBy) {
        if (requestedBy == null) {
            // report_job.requested_by is nullable and no path in the product leaves it null today.
            // Printing "—" beats printing nothing: a header that silently omits its own "generated
            // by" line is indistinguishable from a build that never had one.
            return "—";
        }
        Map<UUID, UserRef> names = people.resolve(companyId, Set.of(requestedBy));
        UserRef person = names.get(requestedBy);
        return person == null ? "Former member" : person.displayName();
    }

    /**
     * Every parameter the job carried, one entry each — <strong>read off the stored JSON and not off
     * what the report understood</strong>.
     *
     * <p>That is the whole point of building this from the raw node. A report ignores parameters it
     * does not know: a client that sends {@code environment} to a report with no environment
     * dimension gets numbers over everything, and a header assembled from what the computation
     * <em>used</em> would print a selection that silently excludes the filter the reader believes
     * narrowed it. Printing what was sent makes the mismatch visible on the document rather than
     * discoverable by re-reading the code.
     *
     * <p>Values are the JSON as stored — a nested object or array prints as its own compact JSON —
     * because this entry is the authoritative record of the request. The readable rendering of the
     * same selection ("Run: TR-1 — Regression") is the report's own metadata, and the two being
     * different things is deliberate: one is for a person, one is for reproducing the report.
     */
    private List<ReportModel.Entry> selection(ReportParameters parameters) {
        List<ReportModel.Entry> entries = parameters.entries();
        return entries.isEmpty()
                ? List.of(new ReportModel.Entry(
                        "Selection", "None — this report was asked for with no parameters"))
                : entries;
    }
}
