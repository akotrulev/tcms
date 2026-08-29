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

    /** Something the operator should know that is not a created object — a skip, mostly. */
    void note(String note) {
        notes.add(note);
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

        out.append("\nSign in with any of these — the password is the same for all of them.\n\n");
        out.append("  %-28s %-18s %s%n".formatted("EMAIL", "ROLE", "COMPANY"));
        credentials.forEach(line -> out.append(line).append('\n'));
        out.append("\n  password: ").append(DemoSeedPlan.PASSWORD).append('\n');
        return out.toString();
    }
}
