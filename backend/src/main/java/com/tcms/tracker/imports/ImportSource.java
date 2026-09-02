package com.tcms.tracker.imports;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * One read pass against a source system. <strong>Nothing here writes.</strong>
 *
 * <p>This is the seam between the two providers. {@link JiraImportSource} serves Jira Cloud and
 * Jira Data Center — they differ in the auth header and in nothing else this module reads — and
 * {@code AzureDevOpsImportSource} serves the third (D-k). {@code FakeImportSource} in the test tree
 * is the third implementation and the only way the failures this module exists to handle can be
 * driven at all.
 *
 * <p>Everything above this interface is provider-agnostic: {@link ImportPreviewService} takes a
 * {@link SourceProjectSnapshot} and the project's own types and statuses and is a pure function of
 * the two. That is what makes T5 a file rather than a slice.
 */
public interface ImportSource {

    /** Which provider this implementation answers for. {@link ImportSources} asks. */
    boolean supports(ImportProvider provider);

    /**
     * The projects this credential can see, for the connect step's dropdown.
     *
     * @throws ImportSourceUnavailableException when the source rejects the credential or does not
     *         answer — one state for every source-side failure, D16's argument
     */
    List<SourceProject> projects(ImportConnection connection);

    /**
     * The names of every status the source project's workflow defines.
     *
     * <p><strong>Names only, and no counts</strong>, unlike {@link SourceStatus}: a status's issue
     * count is a fact about the issues, which only {@link #snapshot} has read, and returning a zero
     * here would be a number a screen could show. What this answers is the one question
     * {@code POST /imports} has to settle <em>before</em> it accepts a run — whether the confirmed
     * mapping covers every status the source can produce.
     *
     * <p>It exists because that refusal is a <strong>400</strong> in the contract and cannot be one
     * without a cheap call. The snapshot takes minutes, so a run that discovered an unmapped status
     * inside its own read pass would answer 202 and then fail — telling somebody their migration
     * started before telling them it was never going to work. This is one request against a
     * workflow definition; it is what {@link #projects} is for the issue count, asked of the other
     * half of the same validation.
     *
     * <p>Added by plan 014 T4, which the port did not predict: T3 had no caller that needed the
     * statuses without the issues.
     *
     * @throws ImportSourceUnavailableException on every source-side failure, as the two above
     */
    List<String> statusNames(ImportConnection connection, String projectKey);

    /**
     * Everything one source project holds, read whole.
     *
     * <p>Whole, and not streamed, for D-b's reason: the write is one all-or-nothing transaction, so
     * it cannot begin until the read has finished. Bounded by
     * {@code tcms.tracker.imports.max-issues} — a source above the cap is refused by the preview
     * with the number in the message, rather than by a run that dies at minute four.
     *
     * @param onProgress called once per page with the running issue count, so a long read is
     *                   visible; <strong>never with a credential</strong>, and never with anything
     *                   a log line could not carry
     */
    SourceProjectSnapshot snapshot(
            ImportConnection connection, String projectKey, IntConsumer onProgress);
}
