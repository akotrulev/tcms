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
