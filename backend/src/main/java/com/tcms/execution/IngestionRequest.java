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
 * @param commitSha the commit the job built — {@code git rev-parse HEAD}. Refused unless it is a
 *                git object name; {@link RunEdit#SHA} is the rule and this endpoint inherits it
 *                rather than keeping a second one, because the create it opens goes through
 *                {@code RunCreateService} like every other.
 * @param branch  the branch the job built. Length-bounded and otherwise unchecked
 *                ({@link RunEdit#MAX_BRANCH}).
 */
public record IngestionRequest(
        ReportFormat format,
        String report,
        UUID runId,
        String runName,
        UUID configurationId,
        String releaseVersion,
        String buildNumber,
        String commitSha,
        String branch) {

    // ~~@param branch the branch under test. @param commitSha the commit under test.~~
    // → REMOVED §170, as two components and nothing else … → AND BACK, in the commit §170 said
    // would bring them: Git traceability (plan §3 phase 2, and §6q's table lists `commitSha`/`branch`
    // beside `releaseVersion` as the field saying which code ran; the ERD annotates both columns with
    // that milestone). What §170 found is worth keeping in front of the next reader, because the
    // shape it names is easy to re-create: these two were BOUND HERE AND DECLARED NOWHERE for two
    // milestones, read by nothing — not by IngestionService, not by IngestionEdit, and `TestRun` did
    // not map the `test_run.branch` / `test_run.commit_sha` columns V1 created for them. A CI job
    // that set them got a 2xx and a run with no branch on it: the worst of the three available
    // behaviours, since refusing would have been honest and storing would have been useful.
    //
    // All three halves of the repair are in one commit, which is the part that matters. The
    // properties are declared on `IngestionInput`, the columns are mapped on `TestRun`, and
    // `IngestionService.openRun` passes both through to the run it opens. A component here that any
    // one of those three is missing is the same defect again, wearing this slice's name.
    //
    // The refusal is now the behaviour a bad value gets, and it costs the report on that build: a
    // malformed `commitSha` 422s the post instead of dropping the field. That is the trade §170's
    // sentence chose in advance — refuse or store, never 2xx-and-discard — and it is cheap in
    // practice, because a value out of `git rev-parse` is malformed on the first build or never.
}
