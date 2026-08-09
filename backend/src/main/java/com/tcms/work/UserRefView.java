package com.tcms.work;

import java.util.UUID;

/**
 * {@code UserRef} in {@code docs/specs/openapi.yaml} — a person, as much of one as a card needs.
 *
 * <h2>The third copy, and that number is the point of this comment</h2>
 *
 * <p>{@code repository.UserRefView} and {@code execution.UserRefView} already exist.
 * {@code execution}'s header explains why its own copy was forced and asks the next reader to keep
 * an accurate count so that extracting a {@code shared} module has real evidence behind it rather
 * than a hunch. This is that count going up.
 *
 * <p>The duplication is forced, not chosen: {@code work} may import neither of those packages (plan
 * §3, and the {@code package-info} says so), and the shape belongs to the <em>contract</em> rather
 * than to any of the three modules. The right home is the {@code shared} module the architecture
 * lists and which still does not exist. Creating it is a package, a Modulith declaration, an
 * {@code ArchitectureRules.FOUNDATION_MODULES} entry and an edit to {@code ModuleStructureTest}'s
 * pinned list — a decision about what the foundation is, which is not something to make quietly in
 * the middle of a feature slice.
 *
 * <p><strong>Running tally of forced wire-shape copies: {@code ApiProblem} ×2,
 * {@code UserRefView} ×3.</strong> The next one should probably come with the extraction rather
 * than with another comment like this.
 *
 * @param displayName resolved through the <strong>active company's</strong> membership (plan §6u),
 *                    falling back to the account's canonical name — the rule
 *                    {@code repository.CaseOwners} and {@code execution.ExecutionPeople} both
 *                    follow. The same person is "A. Kotrulev" to one client and "Aleksandar
 *                    (Contractor)" to another, and a board is exactly where that matters.
 *
 * <p>~~The {@code shared} module this header asks for does not exist.~~ → <strong>superseded
 * 2026-08-09 (§146): it exists, as {@code com.tcms.api}</strong> — created for the error envelope,
 * whose third caller had no module at all. {@code UserRefView} is the obvious second tenant and was
 * deliberately <em>not</em> moved in that commit: §125's rule is that in one diff an architectural
 * move and anything else are indistinguishable, and {@code api}'s own charter was the thing under
 * review there.
 *
 * <p><strong>And the tally was wrong.</strong> {@code work.UserRefView} recorded "{@code
 * UserRefView} ×3" as the evidence for extracting; the actual count when the module was created was
 * <strong>six</strong> — {@code identity}, {@code project}, {@code repository}, {@code
 * documentation}, {@code execution}, {@code work}. A hand-maintained count of copies drifts exactly
 * the way the copies do, which is the argument it was keeping records for.
 */
public record UserRefView(UUID id, String displayName, String email, String avatarUrl) {
}
