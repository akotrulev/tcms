package com.tcms.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reconciles a case's tags against a submitted set — {@link CaseClassifications}' shape, minus the
 * one thing that class is mostly about.
 *
 * <h2>There is no vocabulary bound here, and that is the difference</h2>
 *
 * <p>A classification value is retired by a soft delete, so a client is never shown a retired
 * assignment and therefore cannot resubmit one — which is why that reconcile may only remove
 * assignments to values the project still offers. A tag has no such table: it is free text on
 * {@code case_tag}, every tag a case carries is served on every read of it, and so every tag a
 * client omits is one it was shown and chose to drop. The submitted list is the whole truth.
 *
 * <p>Stating it rather than leaving the absence to be noticed, because the two classes read alike
 * and the missing bound is the sort of thing a later reader adds "for consistency" — at which point
 * tags become un-removable.
 *
 * <h2>Set semantics, and no update arm</h2>
 *
 * <p>{@code case_tag}'s primary key is {@code (case_id, tag)} and nothing references it: a row is
 * present or it is not, so the operations are insert and delete and there is nothing an update
 * could mean. Rows the case already carries and still wants are left untouched rather than deleted
 * and reinserted — not an optimization but the thing that keeps a save of an unchanged tag list
 * from writing to the table at all.
 *
 * <h2>Not written by {@code applyEdit}, and not in the version hash</h2>
 *
 * <p>Tags are the one thing {@code PATCH /cases/{caseId}} accepts that is <strong>not authored
 * content</strong> — see {@link CaseEdit.Outcome.Valid} for the argument, whose short form is that
 * labelling a case {@code sprint-42} must not write a version of the case whose diff shows nothing
 * about the test having changed. So this runs beside the version write rather than inside the edit,
 * and {@code CaseVersions#hash} never sees a tag.
 */
@Component
public class CaseTags {

    private final CaseTagRepository tags;

    public CaseTags(CaseTagRepository tags) {
        this.tags = tags;
    }

    /**
     * Brings the case's stored tags in line with {@code submitted}, in the caller's transaction.
     *
     * @param submitted the tags the case should carry afterwards — already trimmed, de-duplicated
     *                  and length-checked by {@link CaseEdit}. <strong>Null means "leave them
     *                  alone"</strong> and is the only instruction that reads no rows at all: the
     *                  bulk actions reconstruct a request without mentioning tags, and treating
     *                  that as "clear them" would have every {@code MOVE_TO_SUITE} quietly strip a
     *                  case out of every tag-resolved suite it was in.
     * @param companyId the case's company, written onto every row — RLS refuses an insert whose
     *                  value does not match {@code app.current_company} (ERD §0.1), so this is what
     *                  makes the row readable again rather than one this connection can write once
     *                  and never see.
     */
    void reconcile(TestCase testCase, List<String> submitted, UUID companyId) {
        if (submitted == null) {
            return;
        }

        Set<String> wanted = new HashSet<>(submitted);

        List<CaseTag> stale = new ArrayList<>();
        Set<String> alreadyCarried = new HashSet<>();
        for (CaseTag row : tags.findForCase(testCase.getId())) {
            if (wanted.contains(row.getTag())) {
                alreadyCarried.add(row.getTag());
            } else {
                stale.add(row);
            }
        }

        List<CaseTag> added = new ArrayList<>();
        for (String tag : submitted) {
            if (!alreadyCarried.contains(tag)) {
                added.add(CaseTag.of(testCase.getId(), tag, companyId));
            }
        }

        // Deleted first and flushed, unlike the classification reconcile — where the ordering is
        // forced by V2's trigger — because here it is the primary key: a case re-tagged with a
        // different CASE of the same word is two rows the key allows, but a save that removes and
        // re-adds the identical tag in one flush is an insert Hibernate would order before the
        // delete, and (case_id, tag) would refuse it.
        if (!stale.isEmpty()) {
            tags.deleteAll(stale);
            tags.flush();
        }
        if (!added.isEmpty()) {
            tags.saveAll(added);
        }
    }
}
