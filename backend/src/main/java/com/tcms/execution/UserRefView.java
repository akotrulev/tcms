package com.tcms.execution;

import java.util.UUID;

/**
 * {@code UserRef} in {@code docs/specs/openapi.yaml} — a person, as much of one as a list needs.
 *
 * <p>A second declaration of {@code repository.UserRefView}, and the duplication is forced rather
 * than chosen: this module may not import that package (see {@code package-info}), and the shape
 * belongs to the contract rather than to either module. The right home is the {@code shared} module
 * the architecture lists and which does not exist — creating it is a package, a Modulith
 * declaration, an {@code ArchitectureRules.FOUNDATION_MODULES} entry and an edit to
 * {@code ModuleStructureTest}'s pinned list, which is a decision rather than an import and not this
 * slice's to make quietly. Recorded as the third copy of a wire shape (after {@code ApiProblem}),
 * so the extraction has an accurate count of what it reconciles.
 *
 * @param displayName resolved through the <strong>active company's</strong> membership (plan §6u),
 *                    falling back to the account's canonical name. The same rule
 *                    {@code repository.CaseOwners} follows, and for the same reason: the same person
 *                    is "A. Kotrulev" to one client and "Aleksandar (Contractor)" to another.
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
