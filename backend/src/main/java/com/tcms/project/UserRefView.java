package com.tcms.project;

import java.util.UUID;

/**
 * The contract's {@code UserRef} — a person, reduced to what a screen renders beside a row.
 *
 * <p><strong>{@code displayName} is the company membership's, never the account's canonical name</strong>
 * (plan §6u): a contractor may be "A. Kotrulev" to one client and "Aleks (Vendor)" to another, and
 * neither may see the other's label. {@link PeopleDirectory} is the one place that rule is applied.
 *
 * <p>{@code identity.UserRefView} and {@code repository.UserRefView} are the same shape in two other
 * modules, and the duplication is the recorded consequence of the {@code shared} module not existing
 * yet — see {@code ProjectController}'s note on the same postponement. This copy exists because the
 * port below hands names to a module that may not import {@code identity}'s types; what it must not
 * become is a third <em>resolution</em>, which is why {@link PeopleDirectory} has exactly one
 * implementation.
 *
 * @param avatarUrl nullable in the contract and null for everyone — nothing uploads an avatar
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
