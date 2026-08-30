package com.tcms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a seed run did, in the shape an operator reads at the end of it.
 *
 * <p>Mutable and accumulated as the run goes, rather than assembled at the end: a run that fails
 * half way has still created everything up to that point, and a report built only on success would
 * leave the operator guessing which half. {@code SeedDemoDataCommand} prints it on the way out.
 */
final class DemoSeedReport {

    /** Ordered so the output reads company by company rather than by whichever finished first. */
    private final Map<String, List<String>> linesByCompany = new LinkedHashMap<>();

    private final List<String> credentials = new ArrayList<>();

    private final List<String> notes = new ArrayList<>();

    void line(String company, String line) {
        linesByCompany.computeIfAbsent(company, key -> new ArrayList<>()).add(line);
    }

    void credential(String email, String role, String company) {
        credentials.add("  %-28s %-18s %s".formatted(email, role, company));
    }

    private final List<String> refusals = new ArrayList<>();

    /** Something the operator should know that is not a created object — a skip, mostly. */
    void note(String note) {
        notes.add(note);
    }

    /**
     * A documented fixture element the run asked for and did not get.
     *
     * <p>Separate from {@link #note} on purpose: a note is a skip the operator chose (a project that
     * already had content), a refusal is the run disagreeing with itself. Only the second one makes
     * the exit code non-zero, because only the second one leaves the next reader hunting a frontend
     * bug that is not there. Refusals used to be {@code WARN} lines above a report that then said
     * "Seeded demo data" over a fixture with no test plans and no doc pages.
     */
    void refusal(String refusal) {
        refusals.add(refusal);
    }

    boolean isComplete() {
        return refusals.isEmpty();
    }

    String render() {
        StringBuilder out = new StringBuilder("\nSeeded demo data\n================\n");
        linesByCompany.forEach((company, lines) -> {
            out.append('\n').append(company).append('\n');
            lines.forEach(line -> out.append("  ").append(line).append('\n'));
        });

        if (!notes.isEmpty()) {
            out.append("\nNotes\n");
            notes.forEach(note -> out.append("  ").append(note).append('\n'));
        }

        if (!refusals.isEmpty()) {
            out.append("\nRefusals — this fixture is INCOMPLETE\n");
            refusals.forEach(refusal -> out.append("  ").append(refusal).append('\n'));
        }

        out.append("\nSign in with any of these — the password is the same for all of them.\n\n");
        out.append("  %-28s %-18s %s%n".formatted("EMAIL", "ROLE", "COMPANY"));
        credentials.forEach(line -> out.append(line).append('\n'));
        out.append("\n  password: ").append(DemoSeedPlan.PASSWORD).append('\n');
        return out.toString();
    }
}
