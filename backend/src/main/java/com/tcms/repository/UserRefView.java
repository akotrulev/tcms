package com.tcms.repository;

import java.util.UUID;

/**
 * {@code UserRef} in {@code docs/specs/openapi.yaml} — a person, as much of one as a list needs.
 *
 * <p>Id, display name and email, and nothing else. The email is there because the SPA shows it as
 * the tooltip on the owner cell, which is what disambiguates two colleagues who share a first name
 * and a surname initial.
 *
 * @param displayName resolved through the <strong>active company's membership</strong> (plan §6u),
 *                    falling back to the account's own canonical name when there is no membership
 *                    row. Per-membership names are the point: the same person can be "A. Kotrulev"
 *                    to one client and "Aleksandar (Contractor)" to another, and neither may see
 *                    the other's label. Resolving from {@code app_user} alone would quietly show
 *                    every client the same string — including whichever one was typed first.
 * @param avatarUrl   nullable per the contract, and null for everyone today: nothing uploads one
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
