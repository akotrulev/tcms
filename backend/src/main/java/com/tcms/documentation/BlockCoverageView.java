package com.tcms.documentation;

import java.util.List;

/**
 * {@code DocBlock.coverage} in {@code docs/specs/openapi.yaml} — the inline coverage marker, and
 * what plan §6i says makes people maintain links at all.
 *
 * <h2>Why this exists now, and what changed</h2>
 *
 * <p>The marker was declared from M0 and served by nothing through three separate arguments, each
 * true when it was written. The last one — {@code passed} / {@code failed} / {@code notRun} are
 * {@code test_execution} joined to {@code test_case}, <em>two</em> modules {@code documentation} may
 * not name — <strong>stopped being true in §166</strong>, which mirrored each case's newest recorded
 * result onto {@code case_health.last_status_kind}. That is one table in {@code repository}, and
 * {@link CatalogueFacts} is already the one file here allowed to name one. Nothing about this record
 * required a new module boundary, a new table or a migration; it required the fact to move, and
 * another slice had already moved it.
 *
 * <p><strong>The consequence worth keeping in mind is that this and {@code TestCase.lastResult} read
 * the same column.</strong> A statement's marker and the case detail's own last-result line are
 * therefore incapable of disagreeing about whether a case passed — which a live join against
 * {@code test_execution} would not have guaranteed, since it would have been a second derivation of
 * the same fact with its own ordering rules.
 *
 * <h2>Absent is a different claim from zeroed, and that argument survived intact</h2>
 *
 * <p>{@link DocBlockView#coverage()} is null on a block nothing claims to verify, and never a zeroed
 * object. A zeroed marker does not read as "unknown", it reads as <em>"no test case verifies this
 * statement"</em> — the SPA renders it in the words "Not tested" — so a zero fallback would print a
 * specific and alarming claim on every paragraph of every specification in the product. The absence
 * of a statement is not a statement, and {@link DocBlockView#links()} already says the answerable
 * half: an empty list truthfully means nobody has claimed this block.
 *
 * <h2>{@code VERIFIES} only, which is the coverage matrix's rule and not a new one</h2>
 *
 * <p>A block carries three relationships and only one of them is a coverage claim.
 * {@code reporting.CoverageMatrixReport}'s spine counts a statement as covered when it carries a
 * {@code VERIFIES} link, so counting {@code REFERENCES} here would put a number on a paragraph that
 * the matrix — reading the same rows about the same statement — says nothing covers. A block linked
 * only by {@code DERIVED_FROM} or {@code REFERENCES} therefore has links and no marker, which is the
 * honest reading of both.
 *
 * @param linkedCases how many distinct live cases of this project claim to verify this block. Never
 *                    zero: a block with none has no marker at all.
 * @param passed      of those, how many have {@link com.tcms.project.StatusKind#PASSED} as their
 *                    newest recorded result.
 * @param failed      how many have {@link com.tcms.project.StatusKind#FAILED}.
 * @param notRun      <strong>the remainder, and it is wider than "never executed"</strong> —
 *                    {@code BLOCKED}, {@code SKIPPED}, {@code RETEST}, {@code UNTESTED} and
 *                    {@code WIP} land here alongside a case that has never been executed at all.
 *                    The contract offers three counts for six kinds, and the three that are not a
 *                    verdict belong with the ones that were never asked: §46's rule is that a
 *                    roll-up which could go either way must not go the way that makes the report
 *                    look better than the truth. {@code passed + failed + notRun == linkedCases},
 *                    always — a marker whose parts do not sum is one a reader cannot subtract with.
 * @param caseKeys    the keys, in the same order as {@link DocBlockView#links()} renders them, so a
 *                    reader comparing the marker against the bullets under it meets one sequence.
 *                    Deliberately not sorted: {@code TC-9} sorts after {@code TC-10} as text, and
 *                    the key comparator that would fix it lives in no module this one may import.
 */
public record BlockCoverageView(
        int linkedCases, int passed, int failed, int notRun, List<String> caseKeys) {
}
