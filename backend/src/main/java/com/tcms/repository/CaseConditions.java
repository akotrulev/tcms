package com.tcms.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * One case's two condition lists, resolved — <strong>the one implementation of the §6f resolution
 * order</strong>.
 *
 * <h2>The order, and why each step of it is the way it is</h2>
 *
 * <p>{@code V1}'s comment on {@code suite_condition} states it: "inherited (walking suite.path
 * upward) then the case's own conditions, deduped by condition_id". Spelled out, per kind:
 *
 * <ol>
 *   <li><strong>Inherited, outermost ancestor first.</strong> The walk collects the case's own
 *       folder and every folder above it, then emits them root-downward. A standing rule set at
 *       <em>Billing</em> applies before one set at <em>Billing › Refunds</em> because it is the
 *       broader precondition — "be logged in" precedes "have a settled payment", and a checklist
 *       that reverses them asks the tester to do the second thing first. Within one suite, the
 *       author's {@code position}.</li>
 *   <li><strong>Then the case's own</strong>, in its own {@code position} order. Last because the
 *       case is the innermost scope, on the same reasoning that ordered the ancestors.</li>
 *   <li><strong>Deduped by the library condition</strong>, first occurrence winning. Two folders
 *       attaching "Log in", or a case attaching what it already inherits, is one action to perform
 *       and one checkbox to tick — and a checklist that lists it twice trains the reader to stop
 *       reading it. First-wins means the surviving row is the <em>inherited</em> one, which is the
 *       more informative answer: it carries the ancestor's name and the warning that editing it
 *       reaches every case beneath. Dropping the inherited copy in favour of the case's own would
 *       hide an inheritance that is still in force.</li>
 * </ol>
 *
 * <p><strong>The two kinds are resolved separately and dedupe separately.</strong> A condition
 * attached as setup at the suite and as cleanup on the case is two genuinely different actions at
 * two different times — "reset the test payment data" before and after is exactly what a
 * {@code BOTH} library entry is for — and folding them together would silently discard one.
 *
 * <h2>The case's own folder counts as inherited, and that is not a rounding error</h2>
 *
 * <p>A condition attached to the suite the case sits in was not attached by the case. Editing it
 * still reaches every sibling in that folder, which is precisely what the SPA's "inherited from
 * <em>Billing</em>" marker warns about (plan §6f). Marking it local would remove the warning from
 * the most common case there is — the one where the standing rule lives on the folder the author
 * is actually looking at.
 *
 * <h2>Three ways a row disappears, all deliberate</h2>
 *
 * <ol>
 *   <li><strong>An opt-out.</strong> A {@code case_condition_optout} row against the inherited
 *       attachment — the toggle beside the inherited marker. Keyed on the attachment, so opting
 *       out at <em>Billing</em> leaves an identical attachment at <em>Refunds</em> in force; see
 *       {@link CaseConditionOptout}.</li>
 *   <li><strong>A retired library entry.</strong> Retirement is a soft delete because attachments
 *       survive it, so the attachment is still there pointing at a withdrawn procedure. Serving it
 *       would present the withdrawal as current — the same omission
 *       {@link SuiteConditionView} records, for the same reason.</li>
 *   <li><strong>An inline attachment.</strong> Excluded at both queries. The contract requires a
 *       {@code title} and an inline row has no column to hold one; nothing can create one today
 *       either. {@link SuiteConditionView} carries the whole argument and what closes it.</li>
 * </ol>
 *
 * <h2>Four queries, none of them per ancestor</h2>
 *
 * <p>The case's own attachments, the ancestors' attachments (one query for the whole chain), the
 * opt-outs, and the library entries behind everything that survived. A tree is where an N+1 stops
 * being cheap first, and walking up a chain is the shape that invites one query per level.
 */
@Component
public class CaseConditions {

    private final CaseConditionRepository own;
    private final SuiteConditionRepository inherited;
    private final CaseConditionOptoutRepository optouts;
    private final ConditionRepository library;

    public CaseConditions(
            CaseConditionRepository own,
            SuiteConditionRepository inherited,
            CaseConditionOptoutRepository optouts,
            ConditionRepository library) {

        this.own = own;
        this.inherited = inherited;
        this.optouts = optouts;
        this.library = library;
    }

    /**
     * Both lists for one case.
     *
     * @param liveSuites the project's live suites — the chain is walked in memory over these,
     *                   rather than through {@code ltree}'s {@code <@} operator, for the reason
     *                   {@code CaseService#suiteScope} gives: the tree is already loaded, the walk
     *                   is over tens of folders, and the operator needs a native query that would
     *                   drop the Hibernate company filter. Only live suites are ever in it, so a
     *                   soft-deleted folder stops inheriting to the cases still beneath it —
     *                   consistent with the case list, which drops those cases entirely.
     */
    Resolved forCase(TestCase testCase, List<Suite> liveSuites) {
        List<Suite> chain = ancestry(testCase.getSuiteId(), liveSuites);

        List<SuiteCondition> inheritedRows = chain.isEmpty()
                // The case's folder is soft-deleted, so nothing above it is reachable either. Not
                // merely an optimization: an empty collection bound into SQL `IN ()` is a syntax
                // error.
                ? List.of()
                : withoutOptouts(
                        testCase.getId(),
                        inherited.findForSuites(chain.stream().map(Suite::getId).toList()));

        List<CaseCondition> ownRows = own.findForCase(testCase.getId());

        Map<UUID, Condition> resolved = resolve(inheritedRows, ownRows);
        Map<UUID, String> suiteNames = new HashMap<>();
        chain.forEach(suite -> suiteNames.put(suite.getId(), suite.getName()));

        return new Resolved(
                list(ConditionKind.PRE, chain, inheritedRows, ownRows, resolved, suiteNames),
                list(ConditionKind.POST, chain, inheritedRows, ownRows, resolved, suiteNames));
    }

    /**
     * The case's OWN attachments of one kind, in order, restricted to library entries the project
     * still offers — the <strong>one</strong> implementation of that filter, read by both places
     * that need it.
     *
     * <p>{@link CaseClassifications#liveValueIdsOf}'s twin, and the bound is the same one for the
     * same reason. A condition is retired by a soft delete because attachments to it survive, and
     * {@link #forCase} above <em>drops</em> an attachment whose entry no longer resolves — so a
     * client is never shown a retired attachment and therefore cannot resubmit one.
     *
     * <p>This feeds {@link CaseEdit#validate} as the value an edit carries when the request omits
     * the field, and it must agree with what {@code GET} serves: two implementations would let the
     * attachments a client is served differ from the ones a save preserves, which shows up as a
     * condition that disappears on a save that did not mention it.
     */
    List<UUID> liveAttachedIdsOf(UUID caseId, ConditionKind kind) {
        List<CaseCondition> rows = own.findForCase(caseId);
        Set<UUID> live = liveLibraryIdsAmong(rows);

        List<UUID> ids = new ArrayList<>();
        for (CaseCondition row : rows) {
            if (row.getKind() == kind && live.contains(row.getConditionId())) {
                ids.add(row.getConditionId());
            }
        }
        return ids;
    }

    /**
     * Brings the case's own attachments in line with the two submitted lists, in the caller's
     * transaction.
     *
     * <h2>The retired bound, which is the whole reason this is not a delete-all-and-reinsert</h2>
     *
     * <p>{@link CaseClassifications#reconcile}'s rule, transferred: only attachments whose library
     * entry the project <em>still offers</em> are candidates for removal. Without it, the first save
     * of any case attaching a since-retired condition would silently discard it — an attachment
     * nobody chose to drop, removed by a save that never mentioned it, and invisible afterwards
     * because the thing that would have shown it is the thing that was dropped.
     *
     * <p>The consequence is the same one stated there: a retired entry's attachment is
     * <strong>immutable through this API</strong>. It cannot be added (validation refuses an entry
     * the project does not offer) and cannot be removed (this does not reach it). It stops existing
     * when the entry is restored and then detached, or when the case row is deleted.
     *
     * <h2>Rows are rewritten in place where they can be, because POSITION is the content</h2>
     *
     * <p>Unlike a classification assignment, these rows carry an order and that order is the
     * procedure. A row already attaching the wanted entry keeps its identity and takes its new
     * position; only genuinely new attachments are inserted and genuinely removed ones deleted.
     *
     * <p>Nothing references {@code case_condition.id} — {@code case_condition_optout} keys on
     * {@code suite_condition_id}, not this table — so a delete-and-reinsert would in fact be
     * <em>safe</em> here, unlike {@link CaseSteps} where results hang off the id. It is still not
     * what happens, for the reason {@link CaseClassifications} gives about churn: rewriting every
     * row of every case on every save makes the table's history unreadable and buys nothing.
     *
     * @param submitted one list per kind, already validated, in the author's order
     * @param companyId written onto every new row — RLS refuses an insert whose value does not match
     *                  {@code app.current_company} (ERD §0.1), so this is what makes the row
     *                  readable again rather than one this connection can write once and never see
     */
    void reconcile(
            TestCase testCase,
            List<UUID> preconditions,
            List<UUID> postconditions,
            UUID companyId) {

        List<CaseCondition> rows = own.findForCase(testCase.getId());
        Set<UUID> live = liveLibraryIdsAmong(rows);

        List<CaseCondition> stale = new ArrayList<>();
        List<CaseCondition> added = new ArrayList<>();

        for (ConditionKind kind : ConditionKind.values()) {
            List<UUID> wanted = kind == ConditionKind.PRE ? preconditions : postconditions;

            Map<UUID, CaseCondition> reusable = new LinkedHashMap<>();
            for (CaseCondition row : rows) {
                if (row.getKind() != kind) {
                    continue;
                }
                if (!live.contains(row.getConditionId())) {
                    // Retired since it was attached, or an inline row nothing renders. Not a
                    // candidate for removal — see the header. Deliberately not reusable either: it
                    // cannot be in `wanted`, because validation would have refused it.
                    continue;
                }
                // First row wins for a duplicate attachment; the extra is stale, which is also how
                // a list that used to carry the same entry twice collapses to one.
                if (reusable.putIfAbsent(row.getConditionId(), row) != null) {
                    stale.add(row);
                }
            }

            int position = 0;
            for (UUID conditionId : wanted) {
                CaseCondition existing = reusable.remove(conditionId);
                if (existing != null) {
                    existing.moveTo(position);
                } else {
                    added.add(CaseCondition.of(
                            testCase.getId(), conditionId, kind, position, companyId));
                }
                position++;
            }
            // Whatever is left was attached, is still offered, and was not resubmitted.
            stale.addAll(reusable.values());
        }

        if (!stale.isEmpty()) {
            own.deleteAll(stale);
            own.flush();
        }
        if (!added.isEmpty()) {
            own.saveAll(added);
        }
    }

    /**
     * Which of these rows' library entries are still live.
     *
     * <p>One query for the whole set. Inline rows carry a null {@code condition_id} and are
     * therefore never in the result, which is what makes them fall on the protected side of the
     * bound above — correct, since nothing renders them and so nothing can resubmit them either.
     */
    private Set<UUID> liveLibraryIdsAmong(List<CaseCondition> rows) {
        Set<UUID> referenced = new HashSet<>();
        for (CaseCondition row : rows) {
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

    /** The two lists, in the shape {@link TestCaseDetailView} takes them. */
    record Resolved(List<CaseConditionRefView> preconditions,
                    List<CaseConditionRefView> postconditions) {
    }

    /**
     * The case's folder and every live folder above it, <strong>outermost first</strong>.
     *
     * <p>Empty when the folder is not live — a case whose suite was soft-deleted inherits nothing,
     * rather than inheriting from whatever survives above the gap. Half a chain is not a rule
     * anyone wrote.
     *
     * <p>The walk is bounded by the number of live suites, not by the chain length, so a
     * {@code parent_id} cycle — which no constraint in {@code V1} prevents — terminates instead of
     * hanging the request. A cycle is a corrupt tree and this is not the place that repairs it;
     * it is the place that must not spin on it.
     */
    private static List<Suite> ancestry(UUID suiteId, List<Suite> liveSuites) {
        Map<UUID, Suite> live = new HashMap<>();
        liveSuites.forEach(suite -> live.put(suite.getId(), suite));

        List<Suite> upward = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        UUID next = suiteId;
        while (next != null && seen.add(next)) {
            Suite suite = live.get(next);
            if (suite == null) {
                break;
            }
            upward.add(suite);
            next = suite.getParentId();
        }

        return upward.reversed();
    }

    private List<SuiteCondition> withoutOptouts(UUID caseId, List<SuiteCondition> attachments) {
        if (attachments.isEmpty()) {
            return attachments;
        }
        Set<UUID> declined = new HashSet<>();
        optouts.findForCase(caseId).forEach(row -> declined.add(row.getSuiteConditionId()));
        if (declined.isEmpty()) {
            // The ordinary state: a case inherits everything above it unless it says otherwise.
            return attachments;
        }
        return attachments.stream()
                .filter(attachment -> !declined.contains(attachment.getId()))
                .toList();
    }

    /** Every referenced library entry that is still live, in one query, keyed by its id. */
    private Map<UUID, Condition> resolve(
            List<SuiteCondition> inheritedRows, List<CaseCondition> ownRows) {

        // LinkedHashSet: de-duplicated, because one condition referenced from several places is
        // the ordinary case — that reuse is what the library is for.
        Set<UUID> referenced = new LinkedHashSet<>();
        inheritedRows.forEach(row -> referenced.add(row.getConditionId()));
        ownRows.forEach(row -> referenced.add(row.getConditionId()));
        if (referenced.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Condition> byId = new HashMap<>();
        library.findLiveByIds(referenced).forEach(condition -> byId.put(condition.getId(), condition));
        return byId;
    }

    /**
     * One kind's list: the ancestors' attachments root-downward, then the case's own, deduped.
     *
     * <p>Ordered by walking the chain rather than by sorting the attachments, because the ordering
     * key is the ancestor's <em>depth</em> and no column carries it. {@code suite_condition.position}
     * is sibling order within one suite and says nothing about which suite comes first.
     */
    private static List<CaseConditionRefView> list(
            ConditionKind kind,
            List<Suite> chain,
            List<SuiteCondition> inheritedRows,
            List<CaseCondition> ownRows,
            Map<UUID, Condition> resolved,
            Map<UUID, String> suiteNames) {

        List<CaseConditionRefView> entries = new ArrayList<>();
        Set<UUID> emitted = new HashSet<>();

        for (Suite suite : chain) {
            for (SuiteCondition attachment : inheritedRows) {
                if (attachment.getKind() != kind || !attachment.getSuiteId().equals(suite.getId())) {
                    continue;
                }
                Condition condition = resolved.get(attachment.getConditionId());
                if (condition == null || !emitted.add(condition.getId())) {
                    // Retired, or already emitted from a folder further up. Both drop out; see the
                    // class header for why first-wins means the inherited copy survives.
                    continue;
                }
                entries.add(CaseConditionRefView.inheritedFrom(
                        attachment, condition, suiteNames.get(suite.getId())));
            }
        }

        for (CaseCondition attachment : ownRows) {
            if (attachment.getKind() != kind) {
                continue;
            }
            Condition condition = resolved.get(attachment.getConditionId());
            if (condition == null || !emitted.add(condition.getId())) {
                continue;
            }
            entries.add(CaseConditionRefView.own(attachment, condition));
        }

        return List.copyOf(entries);
    }
}
