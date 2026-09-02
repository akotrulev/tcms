package com.tcms.tracker.imports;

import java.time.Instant;
import java.util.List;

/**
 * One issue as the source holds it, read whole.
 *
 * <p>Every field here is either written by the run or counted by the preview's dropped-field list.
 * A field that is neither is not read at all — a snapshot is held in heap for the length of the
 * read pass (D-b), so carrying values nothing consumes is memory spent on nothing.
 *
 * @param key           {@code PROJ-4812}, verbatim, and it survives the import (D5): the run writes
 *                      it to {@code issue.issue_key} while allocating {@code key_seq} from the
 *                      project's own counter, because {@code key_seq} is the only column that may
 *                      order issue keys and Jira's 4812 would move it past four thousand unused
 *                      values
 * @param typeName      the source's type name, mapped by name and then by level
 * @param statusName    the source's status name, mapped by the confirmed mapping and never guessed
 * @param title         becomes {@code issue.title}
 * @param description   becomes {@code issue.description}; null where the source has none
 * @param parentKey     the source's parent, resolved to an id in the write pass once every issue
 *                      has one — which is why it is a key here and not a UUID
 * @param epicKey       Jira's separate epic link, which is a parent by another name for issues that
 *                      have no {@code parent}
 * @param assigneeEmail matched to an {@code app_user}; unmatched leaves the issue unassigned rather
 *                      than assigning it to whoever ran the import
 * @param reporterEmail matched the same way, and reported in the run's {@code people} list either
 *                      way so the report can say who was not found
 * @param labels        become {@code issue_label} rows and assignments
 * @param fixVersions   become releases (D6)
 * @param sprintNames   dropped, or kept as one label per sprint when the option is taken (D-h)
 * @param rankPosition  the source's own backlog position, applied only when
 *                      {@code preserveBacklogOrder} is taken; otherwise the insert trigger's
 *                      {@code max(rank) + 1024} orders them and the report says the order is
 *                      nobody's (D-i)
 * @param createdAt     the source's timestamp
 * @param comments      in the source's order
 */
public record SourceIssue(
        String key,
        String typeName,
        String statusName,
        String title,
        String description,
        String parentKey,
        String epicKey,
        String assigneeEmail,
        String reporterEmail,
        List<String> labels,
        List<String> fixVersions,
        List<String> sprintNames,
        int rankPosition,
        Instant createdAt,
        List<SourceComment> comments) {
}
