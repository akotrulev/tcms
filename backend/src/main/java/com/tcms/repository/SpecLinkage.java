package com.tcms.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Derives the two documentation-caused health signals — {@link HealthSignal#UNLINKED}
 * (<em>"no requirement or doc link — invisible to traceability"</em>) and
 * {@link HealthSignal#OUTDATED_SPEC_CHANGED} (<em>"a linked doc block changed after the case was
 * last edited"</em>, plan §6o's table and §6i's mechanism).
 *
 * <p>They are the last two of §156's five, and they were the two nobody could take: {@code
 * HealthSignal}'s header has said since §158 that both "need {@code documentation}, which this
 * module may not depend on, so those two are a seam and not merely a statement". <strong>That was
 * true when it was written and stopped being true in §167</strong>, which built the seam for a
 * different reason — {@code TestCase.relatedDocuments} — and left {@code case_doc_link} sitting in
 * this module holding every row this derivation needs. So the obstacle is gone and no seam was
 * added: {@code DocumentationFacts} is still the one file here that names a {@code documentation}
 * table, and this one names none.
 *
 * <h2>It reads the MIRROR, not {@code doc_link}, and that is the whole design</h2>
 *
 * <p>{@code V46} §3 said so in advance — <em>"phase 2 of §167 derives that signal FROM THIS TABLE
 * rather than from {@code doc_link} a second time"</em> — and the reason is not that the mirror is
 * nearer. It is that the mirror's four predicates already dropped every row a reader must not see: a
 * link into a soft-deleted page, a page belonging to a sibling project, a link off a soft-deleted
 * case, and a suite's links rather than a case's. Deriving from {@code doc_link} would mean writing
 * all four again, in a second file, where they could come to disagree — and the symptom of that
 * disagreement is the one this arrangement exists to prevent: <strong>a case whose Traceability
 * panel lists nothing while its chips say it is linked</strong>, or the reverse.
 *
 * <p>So the strongest case for the mirror is the one that looks like a bug. A case whose only link
 * points into a page somebody deleted <strong>reports {@code UNLINKED}</strong>. Nothing renders on
 * the panel, so nothing is what the chip agrees with; the alternative is a case the product tells
 * you is traced, to a destination it will not show you.
 *
 * <h2>It is not called {@code DocumentationFacts} and not a {@code *Facts} at all</h2>
 *
 * <p>{@code ConditionDrift}'s argument, second instance: the {@code *Facts} suffix in this module
 * means <em>rows another module owns, read through exactly one file</em>, and every table named here
 * ({@code test_case}, {@code case_doc_link}) belongs to {@code repository}. Putting the statement on
 * {@code DocumentationFacts} instead would be worse than a naming slip — that class's entire charter
 * is being the single crossing, and a method there that crosses nothing makes the file's subject
 * "documentation-ish things" instead of "the crossing". Its header's warning is about a second class
 * naming {@code doc_link}; this is the same property from the other side.
 *
 * <p>Native SQL for the reason all three siblings give: the answer is never needed as a value. One
 * boolean pair per live case, folded into the statement that stores it, rather than N rows moved
 * through the application on an hourly job.
 *
 * @see DocumentationFacts#refreshCaseDocLinks() which must run first, and in the same transaction
 */
@Component
class SpecLinkage {

    private final EntityManager entities;

    SpecLinkage(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * Re-derive both signals for every live case in the currently-open company, in one statement.
     *
     * <h2>{@code UNLINKED} counts ANY link, and the two coverage sites that filter {@code VERIFIES}
     * are not being contradicted</h2>
     *
     * <p>{@code documentation.BlockCoverageView} and {@code reporting.CoverageMatrixReport} both
     * count only a {@code VERIFIES} link, because a block carries three relationships and one of them
     * is a coverage claim. The consistent-looking move is to filter here too, so that a case linked
     * only by {@code DERIVED_FROM} or {@code REFERENCES} — which contributes to no coverage marker
     * anywhere — reports {@code UNLINKED}. <strong>It is refused, and the refusal is about where the
     * question is asked from.</strong>
     *
     * <p>Those two sites answer a question about a <em>statement</em>: does anything claim to verify
     * this paragraph. This one answers a question about a <em>case</em>, and it renders as a chip
     * inches from the panel listing that case's links. A chip reading "Unlinked" on a case whose
     * panel shows two links is not a strict reading of traceability, it is the product disagreeing
     * with itself on one screen — which is the defect §157 found between the stale banner and the
     * stale facet, and the one §167's whole arrangement was built against.
     *
     * <p>"Linked, and nothing claims to cover anything" is a real and narrower gap. It is a
     * different signal with a different name, and this enum has no member for it — which is the
     * honest place to leave it rather than lending it {@code UNLINKED}'s.
     *
     * <h2>{@code OUTDATED_SPEC_CHANGED} does NOT filter the relationship, which is the opposite
     * call</h2>
     *
     * <p>Deliberately opposite, and for the same reason read the other way. §6i's question is whether
     * the case is now out of date, not whether a coverage number is. A case <em>written from</em> a
     * statement that has since been rewritten is exactly as wrong as one claiming to verify it; the
     * author read a paragraph that no longer says what it said. Narrowing this to {@code VERIFIES}
     * would silence an ERROR-severity signal on the cases whose relationship says a human copied the
     * text by hand.
     *
     * <h2><s>An external target clears one signal and can never raise the other</s> — true of TWO
     * of the three external kinds, and V75 added the third</h2>
     *
     * <p>{@code EXTERNAL_GIT} and {@code EXTERNAL_URL} rows (§169) are links, so they take a case out
     * of {@code UNLINKED} — a case pinned to a file at a commit, or to a spec on somebody's wiki, is
     * not invisible to traceability. They can never raise {@code OUTDATED_SPEC_CHANGED}, because
     * {@code DocumentationFacts} writes the literal {@code false} into {@code stale} on that arm and
     * {@code V47} §3 argues why: a commit sha pins the lines, and a URL is not a row in this
     * database at all. The asymmetry is stated rather than tidied because both halves are answers —
     * the link exists, and nothing here can observe its target moving.
     *
     * <p><strong>→ amended for {@code EXTERNAL_ISSUE} ({@code V75}, 004 D13). The section above
     * argues the policy for the two kinds §169 built and must not be read as covering the
     * third.</strong> An issue link does <em>not</em> take a case out of {@code UNLINKED}, and the
     * difference is not a hair being split: a git link and a wiki link are somebody saying <em>this
     * case is traceable to that specification</em>, which is what {@code UNLINKED} is asking about.
     * A link to a bug in Jira says nothing of the kind — it is an annotation on the case, made while
     * somebody was looking at a failure, and 004's A1 requires literally that it change no derived
     * fact.
     *
     * <p>The default behaviour is the opposite. {@code UNLINKED} is a bare {@code count(*)} over the
     * mirror's rows in a lateral aggregate with no {@code target_kind} predicate, so an issue link
     * takes a case out of {@code UNLINKED} <em>by doing nothing at all</em> — filing one defect on
     * one failed run would silently mark a case as traceable. So the count carries
     * {@code AND target_kind <> 'EXTERNAL_ISSUE'}, and {@code ExternalLinkEndpointTest} asserts the
     * <em>signal</em> rather than the SQL, because the shape of the regression is somebody tidying
     * the predicate away as redundant.
     *
     * <p>It still cannot raise {@code OUTDATED_SPEC_CHANGED}, for a <strong>different reason</strong>
     * from the git one and the distinction is worth keeping: {@code V47} §3 says {@code stale} is
     * {@code false} for a git link because the sha <em>pins</em> the lines. An issue has no pin. Here
     * {@code false} is honest because the row carries no derived meaning at all, so there is nothing
     * for a freshness marker to be about. One sentence covering both would be wrong about one of
     * them.
     *
     * <h2>Order inside the transaction, and what reading the stale mirror would cost</h2>
     *
     * <p>This must run <strong>after</strong> {@link DocumentationFacts#refreshCaseDocLinks()} and in
     * the same transaction, which is how {@code CaseHealthRecomputeService} calls it. Run before, it
     * would derive both signals from the <em>previous</em> tick's mirror: a case linked a minute ago
     * would keep its {@code UNLINKED} chip for an hour while its own Traceability panel — refreshed
     * by the statement that ran after this one, in the same transaction — already listed the link.
     * One transaction is what makes the panel and the chip one answer; the order is what makes it
     * this tick's.
     *
     * <h2>Both booleans come off one read of one row set</h2>
     *
     * <p>{@code count(*)} and {@code bool_or(stale)} in one lateral aggregate rather than two
     * {@code EXISTS} subqueries. Equivalent under one snapshot, and the aggregate says what is true
     * of the design: these are two readings of one question — what does this case link to — so they
     * are computed from one pass over one set of rows and cannot be given two different answers by a
     * later edit that changes only one of them.
     *
     * <p>{@code bool_or} over no rows is NULL, hence the {@code coalesce}; {@code count(*)} over no
     * rows is 0, so the {@code UNLINKED} term needs none. Both are inside a {@code LEFT JOIN LATERAL}
     * with an aggregate, which always yields exactly one row — so a case with no links joins to a
     * row of zeroes rather than to nothing.
     *
     * <h2>What it inherits and does not restate</h2>
     *
     * <p>{@code stale}'s granularity is the page, not the block ({@code V46} §4): editing an
     * unrelated paragraph on a linked page marks every link into that page stale, and therefore
     * raises this signal. That is the finest granularity the schema supports and the cost is
     * recorded there rather than argued again here. It errs toward telling somebody to re-read a spec
     * they did not need to.
     *
     * <p>{@code ON CONFLICT} names three columns — {@code outdated_spec_changed}, {@code unlinked}
     * and {@code computed_at}. The row is shared with four other writers in two modules and the
     * columns are not; {@code ExecutionFacts} states the rule and this is the fifth statement it
     * binds.
     *
     * @param now stamped into {@code computed_at} — what tells the dashboard "nobody has looked"
     *            apart from "nothing is wrong" (see {@code CaseHealth}'s header).
     * @return how many cases were re-derived, for the caller's log line.
     */
    int recomputeLinkageSignals(Instant now) {
        // No WHERE on company, on either table, for DocumentationFacts' reason: RLS bounds both the
        // SELECT and the INSERT, and a redundant predicate beside it reads as the thing keeping
        // companies apart.
        return entities.createNativeQuery("""
                INSERT INTO case_health (
                        case_id, company_id, project_id,
                        outdated_spec_changed, unlinked, computed_at)
                SELECT c.id, c.company_id, c.project_id,
                       coalesce(links.spec_moved, false),
                       links.total = 0,
                       :now
                  FROM test_case c
                  LEFT JOIN LATERAL (
                           SELECT count(*)         AS total,
                                  bool_or(l.stale) AS spec_moved
                             FROM case_doc_link l
                            WHERE l.case_id = c.id
                              -- 004 A1/D13, V75. An EXTERNAL_ISSUE link is an annotation and not
                              -- a traceability edge, so it must NOT take a case out of UNLINKED.
                              -- Without this clause it does, by default and silently: the count
                              -- is over every mirror row. Do not "tidy" it away — the class
                              -- header argues it and ExternalLinkEndpointTest asserts the signal.
                              AND l.target_kind <> 'EXTERNAL_ISSUE'
                       ) links ON true
                 WHERE c.deleted_at IS NULL
                    ON CONFLICT (case_id) DO UPDATE
                   SET outdated_spec_changed = EXCLUDED.outdated_spec_changed,
                       unlinked              = EXCLUDED.unlinked,
                       computed_at           = EXCLUDED.computed_at
                """)
                .setParameter("now", now)
                .executeUpdate();
    }
}
