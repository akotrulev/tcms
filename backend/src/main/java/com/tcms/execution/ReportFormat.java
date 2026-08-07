package com.tcms.execution;

/**
 * Which CI report dialect arrived — the {@code CHECK} on {@code ingestion_job.format} in {@code V1}.
 *
 * <p><strong>The set is closed by a database constraint, which is why the parsers are a map keyed on
 * this enum rather than an open registry.</strong> A fifth format cannot be added without a
 * migration, so {@link ReportParsers} may fail fast on a format with no bean instead of degrading:
 * the two lists are the same list, and a mismatch is a wiring defect rather than a runtime
 * condition.
 *
 * <p>On the wire because the caller must say which one they are sending. It cannot be inferred:
 * JUnit and TestNG are both {@code application/xml} and both open with {@code <testsuite}-ish
 * elements, so a {@code Content-Type} distinguishes neither, and sniffing the payload would make a
 * malformed report of one dialect read as a valid report of another.
 */
public enum ReportFormat {

    /** The Ant/Surefire XML that most runners emit — {@code <testsuite>/<testcase>}. */
    JUNIT,

    /** TestNG's own {@code testng-results.xml} — {@code <test-method>} with a {@code status}. */
    TESTNG,

    /** Allure's per-test JSON result objects, submitted as a JSON array. */
    ALLURE,

    /** Cucumber's JSON formatter — features, elements, and a step list per scenario. */
    CUCUMBER
}
