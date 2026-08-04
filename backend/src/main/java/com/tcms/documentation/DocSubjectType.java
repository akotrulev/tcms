package com.tcms.documentation;

/**
 * The kinds of artifact a documentation page can be <em>about</em> — the value set of
 * {@code doc_page.subject_type}, and of {@code DocPageSummary.subjectType} in
 * {@code docs/specs/openapi.yaml}.
 *
 * <p><strong>One value.</strong> That is not an oversight and it is not a generalisation waiting for
 * a second member: the column exists because a test plan's narrative half needed somewhere to live
 * and neither module involved may hold a key into the other. A one-value enum names the shape the
 * next kind will take without pretending a second kind has been designed.
 *
 * <p>The database {@code CHECK} lists exactly these names, so adding a member here without a
 * migration is a row this application will write and Postgres will refuse — loudly, at the insert,
 * which is the failure mode worth having.
 */
enum DocSubjectType {

    /** {@code execution.TestPlan}. The page is the plan's business requirements, scope and scenarios. */
    TEST_PLAN
}
