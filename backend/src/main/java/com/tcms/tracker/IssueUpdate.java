package com.tcms.tracker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code PATCH /issues/{key}} — every field optional, an absent key meaning "leave it".
 *
 * <p>Bound beside a {@link com.tcms.api.PatchBody} so absent and explicitly-null are told apart:
 * Jackson gives them the same Java value, and a service that could not ask what it was sent read
 * both as <em>clear it</em>, which is the defect B1 records — {@code {"title": "x"}} renamed a case
 * and emptied four other columns, and answered 200.
 *
 * <p>{@code prUrl} is here rather than on an endpoint of its own: {@code issue:edit} already gates
 * this path and attaching a PR is an edit. It records its own audit action nonetheless, so the
 * history reads "attached PR" rather than "updated" — see {@code IssueService}.
 *
 * <p><strong>{@code status} is not here.</strong> A move goes through
 * {@code POST /issues/{key}/transitions}, the only path the workflow's legal-move set is checked on.
 * A PATCH that could set a status would be a second, unchecked spelling of the one rule this module
 * exists to enforce.
 */
public record IssueUpdate(
        String title,
        String description,
        String parentKey,
        UUID assigneeId,
        Priority priority,
        Double estimateHours,
        LocalDate startDate,
        LocalDate targetDate,
        LocalDate dueDate,
        UUID releaseId,
        Boolean agentEligible,
        String prUrl,
        List<CriterionUpdate> acceptanceCriteria) {

    /** One row of the checklist as the editor sends it back — no id, because the list is written whole. */
    public record CriterionUpdate(String text, boolean done) {
    }
}
