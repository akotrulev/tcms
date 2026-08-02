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
 */
public record UserRefView(UUID id, String displayName, String email, String avatarUrl) {
}
