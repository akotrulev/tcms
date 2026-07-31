package com.tcms.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code User} — one person in <em>this company's</em> directory.
 *
 * <p>The fields are flat rather than nested under a {@code UserRef}, because the contract composes
 * them with {@code allOf} and that serializes flat. A nested record would put {@code ref.email} on
 * the wire, which no consumer expects.
 *
 * <h2>This is a company directory, not the instance's user table</h2>
 *
 * <p>{@code app_user} is instance-level: one account belongs to several companies (§6s), and email
 * is unique instance-wide. Listing it directly would hand one client the roster of another's. So
 * the list is driven from {@code company_membership} — which is under RLS — and {@code app_user} is
 * read only for the ids that membership already produced.
 *
 * @param lastLoginAt <strong>null for a caller who does not hold {@code company:members}.</strong>
 *                    When a colleague last signed in is an administrative fact rather than a
 *                    directory one; the contract already declares it nullable, so withholding it
 *                    needs no contract change and an ordinary member sees the same shape with one
 *                    less fact in it.
 */
public record UserView(
        UUID id,
        String displayName,
        String email,
        String avatarUrl,
        UserStatus status,
        Instant lastLoginAt,
        List<String> teams) {
}
