package com.tcms.tracker.release;

import com.tcms.project.StatusKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 021 D-k — {@code BoardIssueCard.linkedCases}, as <strong>one batch per board read</strong>.
 *
 * <h2>The same two reads {@code listIssueTestCases} makes, over a whole project at once</h2>
 *
 * <p>{@link VerificationService#list} answers one story's panel from
 * {@link VerificationLinkRepository} plus {@link ReleaseFacts#lastResultsOf}. A card summary is that
 * pair again, so this class is those reads with the issue id widened to a collection — no projection
 * table, no event, no migration, and by construction a card and the story's own panel cannot
 * disagree about a case's last result, because both read the same {@code case_health} row.
 *
 * <p><strong>Three queries per board read, never one per card.</strong> A board is one call to
 * {@link #forIssues} over every live issue of the project; {@code BoardCardService.Facts} holds the
 * answer and every card face reads it out of a map. Asking per card would be two statements per card
 * on the product's busiest read.
 *
 * <h2>Why this lives in {@code release} and not in {@code board}</h2>
 *
 * <p>{@link ReleaseFacts} is the one file in {@code tracker} that names a table this module does not
 * own, and that property is the reason a reviewer can find every crossing by opening one class per
 * module. So {@code board} does not name {@code case_health}: it asks this class a question and gets
 * plain values back — case ids grouped by issue, and a kind per case — leaving the roll-up over the
 * issue tree to the side that has the tree.
 *
 * <h2>Ungated on purpose</h2>
 *
 * <p>There is no {@code userId} here and no permission check. Every caller reaches this <em>after</em>
 * {@code ProjectService} has already gated the project read that produced the issue ids, and a second
 * gate over ids that gate produced would be a check on its own output. RLS still covers both
 * statements: they run inside the caller's transaction, so {@code app.current_company} is set and
 * another company's links and health rows are unreachable regardless of what is asked for.
 */
@Component
public class LinkedCaseFacts {

    private final VerificationLinkRepository links;
    private final ReleaseFacts facts;

    LinkedCaseFacts(VerificationLinkRepository links, ReleaseFacts facts) {
        this.links = links;
        this.facts = facts;
    }

    /**
     * A board read's worth of linked cases.
     *
     * @param casesByIssue  issue id → the live cases of this project linked to it, in link order.
     *                      An issue with no links has no entry, which is the ordinary state of most
     *                      issues in most projects
     * @param lastKindByCase case id → the kind of its newest recorded result <em>anywhere</em>, or
     *                      <strong>no entry</strong> when it has never been executed. Absent and
     *                      "never run" are the same fact and the caller must treat them alike —
     *                      a case the recompute has not reached has no {@code case_health} row at
     *                      all, and one it has reached but nobody has run has a null column
     */
    public record LinkedCases(
            Map<UUID, List<UUID>> casesByIssue, Map<UUID, StatusKind> lastKindByCase) {

        static LinkedCases none() {
            return new LinkedCases(Map.of(), Map.of());
        }
    }

    /**
     * Every {@code TEST_CASE} link held by these issues, and the last result of each case reached.
     *
     * <p>Filtered through {@link ReleaseFacts#casesOf} rather than counted straight off the links,
     * for the reason {@code linkedCasesOf} filters: {@code issue_link.target_id} carries no foreign
     * key (scope §3), so a link can outlive the case it names. A summary counting those would tell a
     * card it has four cases when three exist, and the fourth would be permanently not-run.
     */
    public LinkedCases forIssues(UUID projectId, Collection<UUID> issueIds) {
        if (issueIds.isEmpty()) {
            return LinkedCases.none();
        }
        List<VerificationLink> held =
                links.findByTargetTypeAndIssueIdIn(VerificationLink.TEST_CASE, issueIds);
        if (held.isEmpty()) {
            return LinkedCases.none();
        }

        Set<UUID> named = new LinkedHashSet<>();
        held.forEach(link -> named.add(link.getTargetId()));
        Map<UUID, ReleaseFacts.CaseRef> live = facts.casesOf(projectId, named);
        if (live.isEmpty()) {
            return LinkedCases.none();
        }

        Map<UUID, List<UUID>> byIssue = new LinkedHashMap<>();
        for (VerificationLink link : held) {
            if (live.containsKey(link.getTargetId())) {
                byIssue.computeIfAbsent(link.getIssueId(), id -> new ArrayList<>())
                        .add(link.getTargetId());
            }
        }

        Map<UUID, StatusKind> kinds = new HashMap<>();
        facts.lastResultsOf(live.keySet()).forEach((caseId, last) -> {
            if (last.statusKind() != null) {
                kinds.put(caseId, StatusKind.valueOf(last.statusKind()));
            }
        });
        return new LinkedCases(Map.copyOf(byIssue), Map.copyOf(kinds));
    }
}
