package com.tcms.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * <strong>The last writerless column on {@code case_health}</strong> — {@link HealthSignal#INCOMPLETE},
 * derived from the case's own steps, expected results and classification (plan §6o:
 * <em>"Approved but missing steps, expected results, or classification"</em>).
 *
 * <p>{@code ConditionDrift}'s shape and its module argument: every table this reads
 * ({@code test_case}, {@code test_step}, {@code case_classification}) belongs to
 * {@code repository}, so unlike {@code ApprovalFacts} and {@code OwnerStanding} it crosses nothing
 * at all. SQL through the {@link EntityManager} for the reason those two give: this is a set
 * statement over every live case, and the entity graph is not the shape of the question.
 *
 * <h2>What this closes, and the direction the previous entry had backwards</h2>
 *
 * <p>{@code case_health.incomplete} has been a mapped column with no derivation since {@code V1},
 * and the reason recorded at the field until §161 was that <em>the contract's enum has no such
 * member and inventing one would put a value on the wire the SPA cannot label</em>. That was false
 * the day it was written: {@code openapi.yaml} has carried {@code INCOMPLETE} since 2026-07-30 and
 * {@code lib/labels.ts} has labelled it for just as long. So this slice adds no contract change and
 * no migration — it adds a constant, a statement and a <em>decision</em>, which is the same bill
 * §181 paid for {@link HealthSignal#ORPHANED}.
 *
 * <h2>The decision</h2>
 *
 * <blockquote>An <strong>approved</strong> case is incomplete when it has no steps, or nothing
 * anywhere in it says what should happen, or nobody has classified it.</blockquote>
 *
 * <p><strong>Approved, and only approved.</strong> A draft that is missing things is a draft; that
 * is what the word means, and a signal on it would fire on the whole of an active project's
 * authoring queue and report ordinary work in progress as a fault. {@code DEPRECATED} is excluded
 * for the mirror reason — a retired case is not going to be finished, and a chip asking somebody to
 * finish it is asking for work nobody wants done. The gate is therefore the narrowest of §6o's own
 * two-axis model: <em>lifecycle status APPROVED</em> crossed with a derived signal, which is the
 * exact combination that section says the dashboard exists to surface.
 *
 * <p><strong>The three terms are three separate answers to "can somebody run this".</strong> Steps
 * are the instructions, an expected result is how the tester knows whether it passed, and a
 * classification is what makes the case findable and reportable. Any one of them missing is a
 * signed-off case that does not do its job, and the signal does not say which — the chip is a
 * pointer at the case, and the case is where the gap is visible.
 *
 * <h2>Format is a term in two of the three, and it is not a special case</h2>
 *
 * <p>{@code step_format} makes {@code test_step} rows and {@code gherkin_source} <em>alternatives</em>
 * (plan §6a, and {@code TestCase.gherkinSource}'s field comment): a {@code GHERKIN} case legitimately
 * has no steps at all, and {@code CaseEdit} refuses to save one with an empty scenario. A predicate
 * that read {@code test_step} alone would report every Gherkin case in the product as having no
 * steps — which is not a near-miss but the majority of a Gherkin-first team's repository.
 *
 * <p>The scenario also settles the expected-result term for that format, and <strong>this is the
 * one place the derivation deliberately says less than it could</strong>: a scenario's {@code Then}
 * clause <em>is</em> its expected result, and finding it means parsing Gherkin. In SQL. In a
 * scheduled job. That is {@code OwnerStanding}'s refusal one module over — a second implementation
 * of something the product already does elsewhere, whose disagreements surface as a chip nobody can
 * explain. A non-empty scenario is taken as saying what should happen, because the editor is what
 * makes sure it does.
 *
 * <h2>For a structured case, EITHER expected result counts</h2>
 *
 * <p>{@code test_case.expected_outcome} and {@code test_step.expected_result} are different
 * questions — the entity's own comment says so: the step's is what the tester checks at that step,
 * the case's is what the case as a whole proves. Either satisfies this term, and neither is
 * required in the presence of the other:
 *
 * <ul>
 *   <li>Requiring <em>both</em> fires on almost every case in the product, because almost nobody
 *       fills in both. That is {@link HealthSignal#UNLINKED}'s volume failure deliberately chosen.
 *   <li>Requiring only the per-step one calls a case with a stated overall outcome and terse steps
 *       unfinished, which is a house style, not a gap.
 * </ul>
 *
 * <p>What the term is really asking is whether a tester handed this case can tell a pass from a
 * fail. One statement of that anywhere in the case is enough to answer yes.
 *
 * <h2>Classification means "any", because there is no such thing as a required dimension</h2>
 *
 * <p>{@code classification_dimension} has {@code cardinality}, {@code controlled},
 * {@code hierarchical} and {@code locked}, and no {@code required}. So "missing its classification"
 * has no per-project spelling to read, and the only thing the schema can answer is whether the case
 * carries <em>any</em> {@code case_classification} row. Inventing the missing concept here would be
 * a project setting implemented in a health signal and configurable nowhere, which is the wrong end
 * of the product to add it from; if required dimensions are ever built, this term becomes the
 * narrower predicate and nothing else here moves.
 *
 * <h2>EXISTS rather than a join, and it is the opposite call from §181's</h2>
 *
 * <p>{@code OwnerStanding} joins {@code company_membership} and carries a company predicate to stop
 * one owner's three memberships contributing three copies of every case they own. Both subqueries
 * here are semi-joins, which <em>cannot</em> duplicate a row however many steps or classifications
 * a case has, so the whole class of failure that predicate exists for is unreachable rather than
 * guarded against. They carry no company predicate for the same reason and one more: both are keyed
 * on {@code case_id}, and a case id is a UUID unique across the instance, so there is no row in
 * another company's data these could match even with RLS switched off.
 */
@Component
class CaseCompleteness {

    private final EntityManager entities;

    CaseCompleteness(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * Re-derive {@link HealthSignal#INCOMPLETE} for every live case in the currently-open company,
     * in one statement.
     *
     * <h2>Two of the three terms have no reachable producer today, and are here anyway</h2>
     *
     * <p>{@code CaseEdit.validate} refuses a {@code STRUCTURED} case with no steps and a
     * {@code GHERKIN} case with no scenario, on <em>both</em> the create and the update path — and
     * every other way a case comes into existence goes through that same validation
     * ({@code CaseCloneService} and {@code AiProposalDecisionService} both say so in their headers,
     * and both give the same reason: a second assembler of a legal case is the one that diverges).
     * So a saved case with no steps is not a state this product can currently produce.
     *
     * <p>It is in the predicate anyway, and §181's {@code DEACTIVATED} term is the precedent — with
     * one difference worth naming, because it points the other way. That term was missing a
     * <em>writer</em>; this one is refused by a <em>validator</em>, and a validator is a rule about
     * new writes that says nothing about rows already stored. The import path §6t will need, a
     * repair script, a future format, or a relaxation of that rule in a slice thinking about
     * something else — each of those produces the state without touching this file, and a term left
     * out until then is a signal that silently stops covering it on the day it becomes reachable.
     * One {@code CASE} expression now is cheaper than that, and the fixtures are what will still be
     * asserting the right thing.
     *
     * <p>The third term — no classification — needs nothing to change and fires today.
     *
     * <h2>{@code ON CONFLICT} names two columns</h2>
     *
     * <p>{@code incomplete} and {@code computed_at}. The row is shared with six other writers and
     * the columns are not — {@code ExecutionFacts} has the account, and
     * {@code CaseHealthRecomputeTest.Boundaries} is what would notice. <strong>That test had to
     * change shape for this slice</strong>: it proved the property by leaving one unwritten column
     * alone, and this statement is what takes the last unwritten column away. It asserts over each
     * statement individually now, which needs no witness at all.
     *
     * @param now stamped into {@code computed_at}.
     * @return how many cases were re-derived, for the caller's log line.
     */
    int recomputeIncomplete(Instant now) {
        return entities.createNativeQuery("""
                INSERT INTO case_health (
                        case_id, company_id, project_id,
                        incomplete, computed_at)
                SELECT c.id, c.company_id, c.project_id,
                       c.lifecycle_status = 'APPROVED'
                           AND (CASE WHEN c.step_format = 'GHERKIN'
                                     THEN coalesce(btrim(c.gherkin_source), '') = ''
                                     ELSE NOT EXISTS (SELECT 1 FROM test_step s
                                                       WHERE s.case_id = c.id)
                                       OR (coalesce(btrim(c.expected_outcome), '') = ''
                                           AND NOT EXISTS (SELECT 1 FROM test_step s
                                                            WHERE s.case_id = c.id
                                                              AND coalesce(btrim(s.expected_result), '') <> ''))
                                END
                                OR NOT EXISTS (SELECT 1 FROM case_classification cc
                                                WHERE cc.case_id = c.id)),
                       :now
                  FROM test_case c
                 WHERE c.deleted_at IS NULL
                    ON CONFLICT (case_id) DO UPDATE
                   SET incomplete  = EXCLUDED.incomplete,
                       computed_at = EXCLUDED.computed_at
                """)
                .setParameter("now", now)
                .executeUpdate();
    }
}
