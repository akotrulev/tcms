package com.tcms.project;

import com.tcms.api.ClientRef;
import java.util.UUID;

/**
 * {@code Project} in {@code docs/specs/openapi.yaml} — the wire shape, not the entity.
 *
 * <p>A separate record rather than serializing {@link Project} directly, for two reasons that
 * both bite later:
 *
 * <ul>
 *   <li>{@code companyId} must never leave the building. It is on every company-owned entity
 *       and it is the one field a client has no business seeing — echoing it back invites the
 *       next author to accept it on the way in, which is the whole attack (ERD §0.1 point 2).
 *       A DTO makes that impossible rather than merely discouraged.</li>
 *   <li>{@code caseCount} and {@code myRole} are not on the entity at all. They are computed
 *       per caller, so the entity would need fields that mean nothing to it.</li>
 * </ul>
 *
 * <p>{@code archived} is a boolean here while the entity stores {@code archivedAt} — the
 * contract asked for the fact, not the timestamp, and nothing in the SPA renders the moment
 * of archival.
 *
 * @param description nullable, per the contract
 * @param caseCount   live cases, excluding soft-deleted ones — see
 *                    {@link ProjectRepository#countLiveCasesByProject}
 * @param myRole      the caller's PROJECT-scope role name, or {@code null} when they reach
 *                    this project through a COMPANY-scope grant instead. Display only:
 *                    nothing may branch on it (plan §6b).
 * @param client      the client this project is for, or {@code null} for internal work (scope D1).
 *                    <strong>{@code null} is the common case and is not a deficiency</strong> — the
 *                    owner's own worked example is a company building its own products, and nothing
 *                    in this product renders a project without a client as missing one. Resolved
 *                    through {@link ClientDirectory}, so it is also {@code null} for a client this
 *                    caller cannot see; the two are indistinguishable on purpose, because both mean
 *                    "no client to show here" and neither is worth telling apart.
 */
public record ProjectView(
        UUID id,
        String key,
        String name,
        String description,
        boolean archived,
        long caseCount,
        String myRole,
        ClientRef client) {
}
