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
 */
public record UserRefView(UUID id, String displayName, String email, String avatarUrl) {
}
