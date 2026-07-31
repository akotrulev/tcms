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
 */
public record UserRefView(UUID id, String displayName, String email, String avatarUrl) {
}
