package com.tcms.identity;

import java.util.UUID;

/**
 * The contract's {@code UserRef} — a person, reduced to what a screen renders beside a row.
 *
 * <p><strong>{@code displayName} is the company membership's, never {@link AppUser#getCanonicalName}.</strong>
 * A contractor may legitimately be "A. Kotrulev" to one client and "Aleks (Vendor)" to another
 * (§6u), and neither client may see or overwrite the other's. Resolving the name through
 * {@link CompanyMembership} is what keeps that true, and it is the reason this record is assembled
 * from two rows rather than mapped from one.
 *
 * <p>{@code repository.UserRefView} is the same shape in another module, and the duplication is
 * deliberate: {@code repository} may not depend on {@code identity}'s types beyond what the port
 * hands it, and a shared DTO would need the {@code shared} module that does not exist yet (see
 * {@code ProjectController}'s note on the same postponement).
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
