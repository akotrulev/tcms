package com.tcms.integration.tracker;

import java.util.UUID;

/**
 * {@code TrackerIssueCreateInput} — the body of a file-a-defect.
 *
 * @param bindingId null files through the project's default binding, which every project with any
 *                  binding has exactly one of.
 * @param description the report body, <strong>composed by the client</strong>. For a failed step
 *                  that is the case key, the step, the verdict, the run, the environment and a
 *                  permalink back — a product decision about what a defect report says, made
 *                  visibly in the SPA rather than in a template buried in an adapter.
 * @param executionId the execution this is filed against, when it is. Recorded on the ledger row
 *                  and carried on {@code TrackerIssueCreatedEvent}, which is what lets the outbox
 *                  backstop write the {@code defect_link} if the client's own follow-up never
 *                  arrives. Null when filing from nowhere in particular.
 */
public record TrackerIssueCreateInput(
        UUID bindingId, String title, String description, UUID executionId) {
}
