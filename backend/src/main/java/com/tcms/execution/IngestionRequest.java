package com.tcms.execution;

import java.util.UUID;

/**
 * {@code IngestionInput} in {@code docs/specs/openapi.yaml} — one posted CI report.
 *
 * <p><strong>The report travels as a string in a JSON body rather than as
 * {@code multipart/form-data}</strong> (slice plan decision A). Two of the four dialects are XML and
 * two are JSON, so a {@code Content-Type}-keyed body would dispatch on a header that says nothing
 * about which dialect arrived — {@code application/xml} is true of both JUnit and TestNG and
 * distinguishes neither. Once {@link #format} has to be an explicit field, the payload may as well
 * be one, and the endpoint keeps the shape every other write in the product has: one JSON body, one
 * {@code ProblemDetail} on refusal, and no second content-negotiation path to secure.
 *
 * <p><strong>The idempotency key is NOT here</strong> — it is the {@code Idempotency-Key} header,
 * {@code RunCreateService}'s spelling, so a CI job that already sets it for run creation sets it the
 * same way here. Unlike there it is <em>required</em>: {@code ingestion_job.idempotency_key} is
 * {@code NOT NULL}, and the caller is the one caller in the product that retries automatically and
 * whose retry re-posts a whole report rather than one row.
 *
 * @param format  which dialect {@link #report} is. Required, and not inferred — see
 *                {@link ReportFormat}.
 * @param report  the report file, verbatim. Capped by {@link IngestionEdit#MAX_REPORT_BYTES}.
 * @param runId   post into this existing run instead of creating one. Optional. When given, the
 *                other run fields are <strong>ignored rather than refused</strong>: a CI
 *                configuration that sets a release version on every post should not start failing
 *                the day somebody adds {@code runId} to it, and none of them can change a run that
 *                already exists.
 * @param runName the created run's name. Defaulted rather than required — a CI job posting results
 *                has a build number and rarely a name, and forcing one produces "run" on every row.
 * @param configurationId which environment these results are from. Optional; the project's default
 *                is used when absent, which is {@code RunCreateService}'s own rule.
 * @param releaseVersion  what was under test. Denormalized onto every execution (V1, §6q).
 * @param buildNumber the CI build. The one field that makes an ingested run traceable back to the
 *                job that posted it, which is why it is on the request rather than left to the name.
 */
public record IngestionRequest(
        ReportFormat format,
        String report,
        UUID runId,
        String runName,
        UUID configurationId,
        String releaseVersion,
        String buildNumber) {

    // ~~@param branch the branch under test. @param commitSha the commit under test.~~
    // → REMOVED §170, as two components and nothing else. They were never declared on
    // `IngestionInput`, so no generated client could name them, and they were read by nothing —
    // not by IngestionService, not by IngestionEdit, and TestRun does not even map the
    // `test_run.branch` / `test_run.commit_sha` columns V1 created for them. A CI job that set
    // them got a 2xx and a run with no branch on it, which is the worst of the three possible
    // behaviours: refusing would have been honest and storing would have been useful.
    //
    // THE COLUMNS STAY AND THIS IS NOT THE FEATURE BEING CANCELLED. Git traceability is M4 (plan
    // §3 phase 2, and §6q's table lists `commitSha`/`branch` beside `releaseVersion` as the field
    // that says which code ran); the ERD annotates both columns with that milestone. The slice
    // that builds it declares the two properties on `IngestionInput`, maps the columns on
    // `TestRun`, and adds them back here in the same commit — at which point they are read.
    //
    // Removing them changes no behaviour today: Spring Boot leaves FAIL_ON_UNKNOWN_PROPERTIES off,
    // so a CI job still posting `branch` still gets its 2xx and the value is still dropped. What
    // changes is that the drop is no longer disguised as a binding.
}
