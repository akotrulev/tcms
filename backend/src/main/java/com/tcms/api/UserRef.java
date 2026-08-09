package com.tcms.api;

import java.util.UUID;

/**
 * {@code UserRef} in {@code docs/specs/openapi.yaml} — a person, as much of one as a row, a card or
 * a register needs.
 *
 * <p>Id, display name, email, avatar. Nothing else: this is the shape a list renders <em>beside</em>
 * something, and every screen that needs more asks {@code identity} for it.
 *
 * <h2>This was six records until §147</h2>
 *
 * <p>{@code identity}, {@code project}, {@code repository}, {@code documentation},
 * {@code execution} and {@code work} each declared it, and {@code reporting} imported
 * {@code project}'s. The duplication was <strong>forced rather than chosen</strong> — a module may
 * not import a feature module's types, and the shape belongs to the contract rather than to any of
 * them — and every one of the six headers said so, three of them asking the next reader to keep a
 * count so that an extraction would have evidence behind it. That count was kept by hand and was
 * wrong (§146 found six where it claimed three), which is the argument it was keeping records for.
 *
 * <p>The home did not exist until §146 created {@link Problem}'s. It exists now, and this type is
 * the reason the charter is phrased the way it is: <strong>the contract defines it and more than one
 * module serves it.</strong> Seven consumers is the strongest reading of "more than one" this
 * codebase has.
 *
 * <p><strong>The count is computed now.</strong>
 * {@code ArchitectureRulesTest.noRecordOutsideApiRepeatsAnApiShape} compares every production
 * record's components against this module's, so a seventh copy — under any name, in any module —
 * fails the build rather than being written down in a comment. That is the repair for the finding,
 * not the extraction itself.
 *
 * @param displayName resolved through the <strong>active company's membership</strong> (plan §6u),
 *                    falling back to the account's own canonical name when there is no membership
 *                    row. Per-membership names are the point: the same person can be
 *                    "A. Kotrulev" to one client and "Aleksandar (Contractor)" to another, and
 *                    neither may see the other's label. Resolving from {@code app_user} alone would
 *                    quietly show every client the same string — whichever one was typed first.
 *                    <strong>Applying that rule is not this record's job and must not become it</strong>:
 *                    {@code identity.IdentityPeopleDirectory} is the one implementation, reached
 *                    through {@code project.PeopleDirectory}, and a second resolution here would be
 *                    a second answer to a question §6u says has one.
 * @param email       on the wire because the SPA shows it as the tooltip on an owner cell, which is
 *                    what disambiguates two colleagues who share a first name and a surname initial
 * @param avatarUrl   nullable per the contract, and null for everyone today: nothing uploads one
 */
public record UserRef(UUID id, String displayName, String email, String avatarUrl) {
}
