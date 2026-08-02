package com.tcms.documentation;

import java.util.UUID;

/**
 * {@code UserRef} in {@code docs/specs/openapi.yaml} — a person, as much of one as a register needs.
 *
 * <p>The fourth copy of this record, beside {@code repository}, {@code execution} and {@code work}.
 * That is a deliberate consequence of "modules are packages with declared dependencies": a single
 * shared copy needs a {@code shared} module, which {@code ModuleStructureTest} treats as a decision
 * rather than an import, and taking that decision quietly inside a feature slice is exactly what the
 * test exists to prevent. The three siblings each record the same note.
 *
 * @param displayName resolved through the <strong>active company's membership</strong> (plan §6u),
 *                    falling back to the account's canonical name when there is no membership row.
 *                    The same person can be "A. Kotrulev" to one client and "Aleksandar
 *                    (Contractor)" to another, and neither may see the other's label.
 * @param avatarUrl   nullable per the contract, and null for everyone today: nothing uploads one
 */
public record UserRefView(UUID id, String displayName, String email, String avatarUrl) {
}
