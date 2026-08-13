package com.tcms.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * One suite's two condition lists on the <strong>write</strong> side — the writer
 * {@code suite_condition} did not have until §186.
 *
 * <h2>What was missing, and how long it was missing for</h2>
 *
 * <p>The table, its {@code kind} column, its index ({@code V10}), the resolution order that reads it
 * ({@link CaseConditions}), the response shape ({@link SuiteConditionView}) and the library's
 * {@code suiteUsageCount} all shipped in M0 or shortly after. Nothing ever created a row.
 * {@code new SuiteCondition(} occurred nowhere in {@code src/main}, {@code SuiteInput} had no field
 * for it, and {@code SuiteController}'s write surface was {@code POST} / {@code PATCH} /
 * {@code DELETE} / {@code PUT status}. The rows that exist in this repository were written by
 * hand-rolled SQL in a test fixture, which is exactly what a fixture doing the product's job looks
 * like.
 *
 * <h2>{@link CaseConditions#reconcile}'s algorithm, with its central argument INVERTED</h2>
 *
 * <p>The case-side reconcile rewrites rows in place and its javadoc records that it did not have to:
 * "Nothing references {@code case_condition.id} — {@code case_condition_optout} keys on
 * {@code suite_condition_id}, not this table — so a delete-and-reinsert would in fact be
 * <em>safe</em> here". That sentence names this table as the one where it is not.
 *
 * <p>{@code case_condition_optout (case_id, suite_condition_id)} is the per-case toggle beside the
 * inherited marker, and it references {@code suite_condition(id)} {@code ON DELETE CASCADE}. So a
 * reconcile that cleared the list and reinserted it would, on <em>every save of the suite</em>,
 * silently discard every opt-out every case had recorded — a save that changed nothing an author
 * could see would quietly re-impose a condition twenty cases had declined, and the rows that proved
 * they declined it would be gone. Rewriting in place keeps the attachment's identity across a
 * reorder, which is what keeps the opt-out pointing at the thing it is about.
 *
 * <p>Everything else is transferred verbatim, because divergence between the two reconciles is a
 * defect and not a feature:
 *
 * <ul>
 *   <li><strong>The retired bound.</strong> Only attachments whose library entry the project still
 *       offers are candidates for removal. Without it the first save of any suite attaching a
 *       since-retired condition would silently drop it — and the thing that would have shown the
 *       loss is the thing that was lost.</li>
 *   <li><strong>Duplicates collapse, first row winning</strong>, which is also how a list that used
 *       to carry the same entry twice becomes one.</li>
 *   <li><strong>The two kinds are independent.</strong> The same entry as setup and as cleanup is
 *       two genuinely different actions at two different times.</li>
 *   <li><strong>Inline rows are never reached</strong>, because {@link
 *       SuiteConditionRepository#findForSuites} excludes them. They cannot be added (validation
 *       refuses anything but a library reference) and are not removed here, which leaves them
 *       exactly as untouchable through the API as they were — see {@link SuiteConditionView} for
 *       what closes that.</li>
 * </ul>
 */
@Component
public class SuiteConditions {

    private final SuiteConditionRepository attachments;
    private final ConditionRepository library;

    public SuiteConditions(SuiteConditionRepository attachments, ConditionRepository library) {
        this.attachments = attachments;
        this.library = library;
    }

    /**
     * The suite's attachments of one kind, in order, restricted to library entries the project still
     * offers — {@link CaseConditions#liveAttachedIdsOf}'s twin, and it feeds the same two callers
     * for the same two reasons.
     *
     * <p>It is the stored half of {@code acceptableConditionIds}: without it, the day a condition is
     * retired every suite attaching it becomes unrenameable, and a description fix on an untouched
     * suite would be refused over an attachment the author never made and cannot see. It is also the
     * value an edit carries when the request omits the field, and the two <em>must</em> be the same
     * implementation — two would let the attachments a client is served differ from the ones a save
     * preserves, which shows up as a condition that disappears on a save that never mentioned it.
     */
    List<UUID> liveAttachedIdsOf(UUID suiteId, ConditionKind kind) {
        List<SuiteCondition> rows = attachments.findForSuites(List.of(suiteId));
        Set<UUID> live = liveLibraryIdsAmong(rows);

        List<UUID> ids = new ArrayList<>();
        for (SuiteCondition row : rows) {
            if (row.getKind() == kind && live.contains(row.getConditionId())) {
                ids.add(row.getConditionId());
            }
        }
        return ids;
    }

    /**
     * Brings the suite's attachments in line with the two submitted lists, in the caller's
     * transaction.
     *
     * <p>See the class header for the one way this differs from {@link CaseConditions#reconcile} and
     * why the difference is the point.
     *
     * @param submitted lists already validated by {@link SuiteEdit}, in the author's order
     * @param companyId written onto every new row — RLS refuses an insert whose value does not match
     *                  {@code app.current_company} (ERD §0.1), so this is what makes the row
     *                  readable again rather than one this connection can write once and never see
     */
    void reconcile(
            Suite suite,
            List<UUID> preconditions,
            List<UUID> postconditions,
            UUID companyId) {

        List<SuiteCondition> rows = attachments.findForSuites(List.of(suite.getId()));
        Set<UUID> live = liveLibraryIdsAmong(rows);

        List<SuiteCondition> stale = new ArrayList<>();
        List<SuiteCondition> added = new ArrayList<>();

        for (ConditionKind kind : ConditionKind.values()) {
            List<UUID> wanted = kind == ConditionKind.PRE ? preconditions : postconditions;

            Map<UUID, SuiteCondition> reusable = new LinkedHashMap<>();
            for (SuiteCondition row : rows) {
                if (row.getKind() != kind) {
                    continue;
                }
                if (!live.contains(row.getConditionId())) {
                    // Retired since it was attached. Not a candidate for removal — see the header.
                    // Not reusable either: validation would have refused it, so it cannot be in
                    // `wanted`.
                    continue;
                }
                if (reusable.putIfAbsent(row.getConditionId(), row) != null) {
                    stale.add(row);
                }
            }

            int position = 0;
            for (UUID conditionId : wanted) {
                SuiteCondition existing = reusable.remove(conditionId);
                if (existing != null) {
                    existing.moveTo(position);
                } else {
                    added.add(SuiteCondition.of(
                            suite.getId(), conditionId, kind, position, companyId));
                }
                position++;
            }
            // Whatever is left was attached, is still offered, and was not resubmitted. Deleting it
            // takes every case's opt-out against it with it, which is right — an opt-out is a
            // refusal of a specific attachment, and that attachment is being withdrawn.
            stale.addAll(reusable.values());
        }

        if (!stale.isEmpty()) {
            attachments.deleteAll(stale);
            attachments.flush();
        }
        if (!added.isEmpty()) {
            attachments.saveAll(added);
        }
    }

    /**
     * Which of these rows' library entries are still live — one query for the whole set.
     *
     * <p>{@link CaseConditions}' method, and inline rows fall out of it the same way: they carry a
     * null {@code condition_id}, so they are never in the result and therefore always on the
     * protected side of the retired bound.
     */
    private Set<UUID> liveLibraryIdsAmong(List<SuiteCondition> rows) {
        Set<UUID> referenced = new HashSet<>();
        for (SuiteCondition row : rows) {
            if (row.getConditionId() != null) {
                referenced.add(row.getConditionId());
            }
        }
        if (referenced.isEmpty()) {
            // An empty IN () is a syntax error rather than an empty result.
            return Set.of();
        }
        Set<UUID> live = new HashSet<>();
        for (Condition condition : library.findLiveByIds(referenced)) {
            live.add(condition.getId());
        }
        return live;
    }
}
