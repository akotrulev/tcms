package com.tcms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A seed run that refused a documented fixture element must say so in the report, not only in a
 * {@code WARN} line above it. The audit of 2026-08-30 lost two environment builds to a report that
 * read "Seeded demo data" over a fixture with no test plans and no doc pages.
 */
class DemoSeedReportTest {

    @Test
    void refusalsAppearInTheReportAndMarkItIncomplete() {
        DemoSeedReport report = new DemoSeedReport();
        report.line("Northwind", "WEB — 10 cases");
        report.refusal("Plan for 2026.8.0 was refused: entryCriteria ['gte' is not a comparison]");

        assertThat(report.isComplete()).isFalse();
        assertThat(report.render())
                .contains("Refusals")
                .contains("Plan for 2026.8.0 was refused");
    }

    @Test
    void aRunWithNoRefusalsIsComplete() {
        DemoSeedReport report = new DemoSeedReport();
        report.line("Northwind", "WEB — 10 cases");

        assertThat(report.isComplete()).isTrue();
        assertThat(report.render()).doesNotContain("Refusals");
    }

    @Test
    void aNoteIsNotARefusal() {
        DemoSeedReport report = new DemoSeedReport();
        report.note("project WEB already held cases, so it was left alone");

        assertThat(report.isComplete()).isTrue();
        assertThat(report.render()).contains("Notes");
    }
}
