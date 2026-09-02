package com.tcms.tracker.imports;

import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The report — {@code import-report.html}, and the poll target while {@code status} is
 * {@code RUNNING}.
 *
 * <p><strong>Self-contained by construction.</strong> The dropped-field list, the mapping as
 * applied and the people it matched are all read off the run's own row and its own child table, so
 * nothing here calls the source system and nothing here joins to a status or a user that may since
 * have been removed. That is the whole promise: a year from now, with the Jira site decommissioned
 * and its statuses long since renamed here, this still says what happened.
 *
 * @param backlogOrderPreserved false ⇒ the report says the backlog order is insertion order, chosen
 *                              by nobody, rather than letting it read as imported (D-i)
 * @param droppedFields         the preview's list, element for element including the reason strings
 *                              — one derivation, two responses (D-f)
 * @param startedBy             who ran the migration, or null when the account no longer resolves
 *                              ({@code import_run.started_by} carries no foreign key).
 *                              ~~A two-component shape of this module's own~~ → {@code UserRef}:
 *                              the bespoke record was {@code api.TeamRef}'s shape under different
 *                              names, which {@code ArchitectureRulesTest} refuses, and every other
 *                              "who did this" position in the contract is a {@code UserRef} anyway
 */
public record ImportRunView(
        UUID id,
        UUID projectId,
        ImportProvider provider,
        String siteUrl,
        String accountEmail,
        String sourceProjectKey,
        ImportRunStatus status,
        Instant startedAt,
        Instant finishedAt,
        UserRef startedBy,
        int issuesImported,
        int releasesCreated,
        int commentsImported,
        int labelsCreated,
        int droppedValueCount,
        boolean labelPerSprint,
        boolean backlogOrderPreserved,
        String failureReason,
        List<DroppedFieldView> droppedFields,
        List<AppliedStatusMappingView> statusMapping,
        List<ImportedPersonView> people) {
}
