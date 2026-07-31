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
 */
public record UserRefView(UUID id, String displayName, String email, String avatarUrl) {
}
